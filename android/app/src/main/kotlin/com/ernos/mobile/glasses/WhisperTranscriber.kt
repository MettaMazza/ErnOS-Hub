package com.ernos.mobile.glasses

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WhisperTranscriber
 *
 * Local speech-to-text pipeline for audio arriving from Meta Ray-Ban glasses.
 *
 * ## Audio paths
 *
 * ### Path A – Glasses PCM (primary, when glasses are connected)
 * The glasses TCP stream delivers raw PCM 16-kHz mono 16-bit chunks via [submitPcm].
 * These are accumulated in a ring buffer.  A simple energy-threshold VAD detects when
 * a speech segment ends (300 ms of near-silence after at least 200 ms of speech).
 * The accumulated segment is written to a temp WAV file and submitted to Android's
 * on-device [SpeechRecognizer] via `EXTRA_AUDIO_SOURCE` (Android 13+) or via a short
 * loopback [AudioTrack] playback captured by a concurrent `SpeechRecognizer` session
 * (Android 9–12).  The transcript is published to [transcripts].
 *
 * ### Path B – Phone microphone (fallback)
 * When no PCM data arrives (BLE-only mode or glasses audio disabled), the recognizer
 * listens to `VOICE_RECOGNITION` audio source from the phone microphone so hands-free
 * prompts still work.
 *
 * ### Future path – whisper.cpp JNI
 * When a Whisper GGUF model is placed at `<externalFilesDir>/whisper.gguf`, the native
 * whisper.cpp JNI bridge can be swapped in here for fully offline transcription of the
 * raw PCM buffer (no Android speech API needed).
 *
 * Usage:
 * ```kotlin
 * val transcriber = WhisperTranscriber(context)
 * transcriber.start()
 * // From the TCP data loop:
 * transcriber.submitPcm(pcmBytes)
 * // Collect results:
 * transcriber.transcripts.collect { text -> vm.sendMessage(text) }
 * transcriber.stop()
 * ```
 */
class WhisperTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "WhisperTranscriber"

        // ── Audio format (must match glasses firmware) ──────────────────────────
        const val SAMPLE_RATE    = 16_000    // Hz
        const val CHANNEL_COUNT  = 1         // mono
        const val BITS_PER_SAMPLE = 16       // PCM-S16LE

        // ── VAD thresholds ──────────────────────────────────────────────────────
        /** RMS energy below this is considered silence (raw int16, range 0..32767). */
        private const val SILENCE_RMS_THRESHOLD = 300

        /** Consecutive silent frames (each ~20 ms) before the utterance is finalized. */
        private const val SILENCE_FRAMES_REQUIRED = 15   // ≈ 300 ms

        /** Minimum speech frames before we commit (avoid tiny clicks). */
        private const val MIN_SPEECH_FRAMES = 10   // ≈ 200 ms

        /** Maximum utterance buffer before we force-flush (10 s). */
        private const val MAX_BUFFER_FRAMES = 500

        /** Bytes per 20-ms frame at 16 kHz mono 16-bit. */
        private val FRAME_BYTES = SAMPLE_RATE * 20 / 1000 * 2   // 640 bytes
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _transcripts = Channel<String>(capacity = Channel.UNLIMITED)

    /** Emits fully-recognized utterance strings. Collect on any coroutine context. */
    val transcripts: Flow<String> get() = _transcripts.receiveAsFlow()

    private var recognizer:   SpeechRecognizer? = null
    private var isListening   = false
    private var pcmModeActive = false   // true once submitPcm() has been called at least once

    // ── VAD state ──────────────────────────────────────────────────────────────
    private val vadBuffer     = ByteArrayOutputStream()
    private var speechFrames  = 0
    private var silenceFrames = 0
    private val pcmOverflow   = ByteArrayOutputStream()   // leftover partial-frame bytes

    // ── Start / Stop ───────────────────────────────────────────────────────────

    /** Start the transcriber.  Begins phone-mic listening; switches to PCM mode on first [submitPcm]. */
    fun start() {
        mainHandler.post { startOnMain() }
    }

    /** Stop all recognition and release resources. */
    fun stop() {
        mainHandler.post { stopOnMain() }
        scope.cancel()
    }

    // ── PCM ingestion (glasses audio path) ────────────────────────────────────

    /**
     * Submit a chunk of raw PCM data from the glasses TCP stream.
     * May be called from any thread; frame processing is offloaded to [scope].
     * Format: 16-kHz, mono, signed 16-bit little-endian (PCM-S16LE).
     */
    fun submitPcm(pcmBytes: ByteArray) {
        if (!isListening) return
        scope.launch {
            // Stop phone-mic recognizer when first real PCM arrives
            if (!pcmModeActive) {
                pcmModeActive = true
                mainHandler.post {
                    recognizer?.stopListening()
                    Log.i(TAG, "Switched to PCM-from-glasses transcription mode")
                }
            }
            processPcmChunk(pcmBytes)
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────────

    private fun startOnMain() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(micListener)
        }
        isListening = true
        // Start phone-mic mode; will be suspended if PCM data arrives
        beginMicListening()
        Log.i(TAG, "WhisperTranscriber started (phone-mic mode)")
    }

    private fun stopOnMain() {
        isListening   = false
        pcmModeActive = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        vadBuffer.reset()
        pcmOverflow.reset()
        Log.i(TAG, "WhisperTranscriber stopped")
    }

    private fun beginMicListening() {
        if (!isListening || pcmModeActive) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        recognizer?.startListening(intent)
    }

    // ── VAD + WAV transcription ────────────────────────────────────────────────

    /**
     * Process an arbitrary-length PCM chunk by splitting it into 20-ms frames,
     * running a simple energy-threshold VAD, and flushing completed utterances
     * to [recognizeWav].
     */
    private fun processPcmChunk(chunk: ByteArray) {
        // Prepend any leftover bytes from the previous call
        val combined: ByteArray
        if (pcmOverflow.size() > 0) {
            combined = pcmOverflow.toByteArray() + chunk
            pcmOverflow.reset()
        } else {
            combined = chunk
        }

        var offset = 0
        while (offset + FRAME_BYTES <= combined.size) {
            val frame = combined.copyOfRange(offset, offset + FRAME_BYTES)
            offset += FRAME_BYTES
            processVadFrame(frame)
        }
        // Save remaining bytes for the next call
        if (offset < combined.size) {
            pcmOverflow.write(combined, offset, combined.size - offset)
        }
    }

    private fun processVadFrame(frame: ByteArray) {
        val rms = computeRms(frame)
        val isSpeech = rms > SILENCE_RMS_THRESHOLD

        if (isSpeech) {
            vadBuffer.write(frame)
            speechFrames++
            silenceFrames = 0
        } else {
            if (speechFrames > 0) {
                // Trailing silence after speech
                vadBuffer.write(frame)
                silenceFrames++

                if (silenceFrames >= SILENCE_FRAMES_REQUIRED && speechFrames >= MIN_SPEECH_FRAMES) {
                    flushUtterance()
                }
            }
            // If no speech yet, discard the silence frame
        }

        // Force-flush if buffer is too large
        if (speechFrames + silenceFrames >= MAX_BUFFER_FRAMES) {
            flushUtterance()
        }
    }

    private fun flushUtterance() {
        val pcm = vadBuffer.toByteArray()
        vadBuffer.reset()
        speechFrames  = 0
        silenceFrames = 0

        if (pcm.size < FRAME_BYTES * MIN_SPEECH_FRAMES) return   // too short

        scope.launch(Dispatchers.IO) {
            recognizeWav(pcm)
        }
    }

    /**
     * Write [pcm] to a temporary WAV file and submit it for recognition.
     *
     * On Android 13+ (API 33): uses `EXTRA_AUDIO_SOURCE` to pass the WAV
     * content URI directly to the recognizer.
     *
     * On Android 9–12: plays the PCM through [AudioTrack] and captures via
     * the phone mic.  This is inherently racy (ambient noise may leak in), but
     * it is the only fully-offline path available without root or system privileges.
     * When whisper.cpp JNI is available (Milestone 6+), this branch is replaced.
     */
    private fun recognizeWav(pcm: ByteArray) {
        val wavFile = writeWav(pcm)
        if (wavFile == null) {
            Log.e(TAG, "Failed to write WAV — skipping utterance")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: pass the file URI to the recognizer
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                wavFile,
            )
            mainHandler.post {
                if (!isListening || !pcmModeActive) return@post
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra("android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE", SAMPLE_RATE)
                    // On Android 13 the recognizer can accept an audio file URI directly
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                recognizer?.startListening(intent)
            }
        } else {
            // Android 9–12: play through AudioTrack so the mic can capture it.
            // The phone must be held close to the speaker, or use a headset loopback.
            // This is a best-effort path; whisper.cpp JNI replaces it in Milestone 6.
            playAndCapture(pcm)
            wavFile.delete()
        }
    }

    /** Write raw PCM bytes to a 16-kHz mono 16-bit WAV file in the app's cache dir. */
    private fun writeWav(pcm: ByteArray): File? {
        return try {
            val f = File(context.cacheDir, "glasses_utterance_${System.currentTimeMillis()}.wav")
            FileOutputStream(f).use { out ->
                out.write(wavHeader(pcm.size))
                out.write(pcm)
            }
            f
        } catch (e: Exception) {
            Log.e(TAG, "writeWav error: ${e.message}", e)
            null
        }
    }

    /** Build a minimal 44-byte WAV header for PCM-S16LE mono 16 kHz. */
    private fun wavHeader(pcmSize: Int): ByteArray {
        val totalSize  = pcmSize + 36
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(totalSize)
        buf.put("WAVEfmt ".toByteArray())
        buf.putInt(16)                       // PCM chunk size
        buf.putShort(1)                      // PCM format
        buf.putShort(CHANNEL_COUNT.toShort())
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8)   // byte rate
        buf.putShort((CHANNEL_COUNT * BITS_PER_SAMPLE / 8).toShort())   // block align
        buf.putShort(BITS_PER_SAMPLE.toShort())
        buf.put("data".toByteArray())
        buf.putInt(pcmSize)
        return buf.array()
    }

    /** Play PCM through [AudioTrack] so the phone mic (and thus SpeechRecognizer) can hear it. */
    private fun playAndCapture(pcm: ByteArray) {
        try {
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(pcm, 0, pcm.size)

            mainHandler.post {
                if (!isListening || !pcmModeActive) {
                    audioTrack.release()
                    return@post
                }
                // Start capturing from mic before playback so recognizer hears the audio
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                recognizer?.startListening(intent)

                audioTrack.play()
                // Release after estimated playback duration + buffer
                val durationMs = (pcm.size.toLong() * 1000L) / (SAMPLE_RATE * 2)
                mainHandler.postDelayed({ audioTrack.release() }, durationMs + 500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playAndCapture error: ${e.message}", e)
        }
    }

    /** Compute RMS energy of a PCM-S16LE frame (range 0..32767). */
    private fun computeRms(frame: ByteArray): Double {
        var sum = 0.0
        var i   = 0
        while (i + 1 < frame.size) {
            val sample = (frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample.toDouble()
            i   += 2
        }
        val numSamples = frame.size / 2
        return if (numSamples == 0) 0.0 else Math.sqrt(sum / numSamples)
    }

    // ── Phone-mic SpeechRecognizer listener ─────────────────────────────────────

    private val micListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best    = matches?.firstOrNull()
            if (!best.isNullOrBlank()) {
                Log.i(TAG, "Transcript: $best")
                _transcripts.trySend(best)
            }
            // Auto-restart for hands-free continuous listening (mic mode only)
            if (isListening && !pcmModeActive) beginMicListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            val reason = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH                  -> "NO_MATCH"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT            -> "SPEECH_TIMEOUT"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY           -> "RECOGNIZER_BUSY"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS  -> "INSUFFICIENT_PERMISSIONS"
                SpeechRecognizer.ERROR_NETWORK                   -> "NETWORK"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT           -> "NETWORK_TIMEOUT"
                SpeechRecognizer.ERROR_SERVER                    -> "SERVER"
                SpeechRecognizer.ERROR_CLIENT                    -> "CLIENT"
                else                                              -> "UNKNOWN($error)"
            }
            Log.w(TAG, "SpeechRecognizer error: $reason")
            if (isListening && !pcmModeActive &&
                error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                mainHandler.postDelayed({ beginMicListening() }, 500)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
