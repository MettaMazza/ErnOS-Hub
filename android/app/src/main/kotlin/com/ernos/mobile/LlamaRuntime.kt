package com.ernos.mobile

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LlamaRuntime
 *
 * Kotlin wrapper around the llama_bridge JNI library.
 * Exposes a [Flow<String>] that streams generated tokens from C++.
 *
 * Usage:
 *   val runtime = LlamaRuntime()
 *   val handle  = runtime.initModel("/path/to/model.gguf")
 *   runtime.streamGenerate(handle, "Hello!").collect { token -> ... }
 *   runtime.freeModel(handle)
 */
class LlamaRuntime {

    // ── JNI interface ────────────────────────────────────────────────────────

    /**
     * Called from C++ to deliver tokens during generation.
     * The outer class holds an instance reference that C++ keeps as a jobject.
     */
    interface TokenCallback {
        fun onToken(token: String)
        fun onComplete()
        fun onError(message: String)
    }

    private external fun nativeBackendInit()

    /**
     * Load a GGUF model file and return an opaque handle (pointer).
     * @return handle > 0 on success, 0 on failure (exception is also thrown).
     */
    private external fun nativeInitModel(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
    ): Long

    /** Release all native resources associated with [handle]. */
    private external fun nativeFreeModel(handle: Long)

    /**
     * Tokenise [text] using the model vocabulary.
     * @return int array of token IDs, or null on failure.
     */
    private external fun nativeTokenize(handle: Long, text: String): IntArray?

    /**
     * Decode (evaluate) a sequence of tokens through the model.
     * @return true on success.
     */
    private external fun nativeDecode(handle: Long, tokenIds: IntArray): Boolean

    /**
     * Signal the in-progress generation to stop at the next token boundary.
     * Safe to call from any thread (sets an atomic flag checked in the native loop).
     */
    private external fun nativeCancelGenerate(handle: Long)

    /**
     * Serialize the KV cache associated with [handle] to [filePath].
     *
     * Returns true on success.  The file is a raw binary blob in llama.cpp's
     * native format (llama_state_save_file) and should be treated as opaque.
     * It is only valid for the exact model and nCtx combination used to create
     * [handle]; loading it with a different model or context size is undefined.
     *
     * If the native library is unavailable or [handle] is 0, returns false silently.
     */
    private external fun nativeSaveKvCache(handle: Long, filePath: String): Boolean

    /**
     * Restore the KV cache from [filePath] into [handle].
     *
     * Returns the number of tokens restored on success (≥ 0), or -1 on failure.
     * Must be called with the same model handle and nCtx that was used to save.
     *
     * The caller should decode only the tokens *after* the restored position to
     * continue generation seamlessly from the saved point.
     */
    private external fun nativeRestoreKvCache(handle: Long, filePath: String): Int

    /**
     * Stream text generation.  Calls [callback] for every produced token piece,
     * then calls [callback.onComplete] or [callback.onError].
     * Runs synchronously on the calling thread.
     */
    private external fun nativeStreamGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        presencePenalty: Float,
        callback: TokenCallback,
    )

    // ── Initialisation ───────────────────────────────────────────────────────

    companion object {
        private val libraryLoaded = AtomicBoolean(false)

        init {
            try {
                System.loadLibrary("llama_bridge")
                libraryLoaded.set(true)
                Log.i(TAG, "llama_bridge library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load llama_bridge: ${e.message}")
            }
        }

        private const val TAG = "LlamaRuntime"
    }

    private val backendReady = AtomicBoolean(false)

    init {
        if (libraryLoaded.get() && backendReady.compareAndSet(false, true)) {
            nativeBackendInit()
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Load a model from [modelPath].
     *
     * @param modelPath  Absolute path to the GGUF file.
     * @param nCtx       Context window size (default 4096).
     * @param nThreads   CPU inference threads (default 4).
     * @param nGpuLayers Number of layers to offload to GPU/Vulkan (default 0).
     * @return           Opaque handle; 0 means failure.
     */
    fun initModel(
        modelPath: String,
        nCtx: Int = 4096,
        nThreads: Int = 4,
        nGpuLayers: Int = 0,
    ): Long {
        check(libraryLoaded.get()) { "llama_bridge native library is not loaded" }
        Log.i(TAG, "initModel path=$modelPath nCtx=$nCtx threads=$nThreads gpu=$nGpuLayers")
        return nativeInitModel(modelPath, nCtx, nThreads, nGpuLayers)
    }

    /** Release native resources for [handle]. */
    fun freeModel(handle: Long) {
        if (handle == 0L) return
        Log.i(TAG, "freeModel handle=$handle")
        nativeFreeModel(handle)
    }

    /**
     * Tokenise [text] using the loaded model vocabulary.
     */
    fun tokenize(handle: Long, text: String): IntArray? {
        if (handle == 0L) return null
        return nativeTokenize(handle, text)
    }

    /**
     * Evaluate [tokenIds] through the model, updating the KV cache.
     */
    fun decode(handle: Long, tokenIds: IntArray): Boolean {
        if (handle == 0L) return false
        return nativeDecode(handle, tokenIds)
    }

    /**
     * Signal native generation to halt at the next token boundary.
     * Call this from any thread — it sets an atomic flag the C++ loop polls.
     * Generation completes normally (calling [TokenCallback.onComplete]) after
     * the current token finishes.
     */
    fun cancelGenerate(handle: Long) {
        if (handle == 0L) return
        nativeCancelGenerate(handle)
    }

    /**
     * Serialize the KV cache for [handle] to [filePath].
     *
     * The saved state captures the exact token history the model has already
     * processed.  Call [restoreKvCache] with the same model/nCtx to resume
     * from this point, skipping re-processing of the already-decoded prefix.
     *
     * @param handle   Handle from [initModel].
     * @param filePath Absolute path for the binary state file.
     * @return true on success.
     */
    fun saveKvCache(handle: Long, filePath: String): Boolean {
        if (handle == 0L || !libraryLoaded.get()) {
            Log.w(TAG, "saveKvCache: skipped (handle=$handle loaded=${libraryLoaded.get()})")
            return false
        }
        Log.i(TAG, "saveKvCache handle=$handle → $filePath")
        return nativeSaveKvCache(handle, filePath)
    }

    /**
     * Restore KV cache from [filePath] into [handle].
     *
     * Must be called with the same model and nCtx that were active when
     * [saveKvCache] was invoked.  After a successful restore the caller should
     * only submit *new* tokens to [decode] / [streamGenerate]; re-submitting
     * already-processed tokens will corrupt the KV cache.
     *
     * @param handle   Handle from [initModel].
     * @param filePath Absolute path to the binary state file written by [saveKvCache].
     * @return Number of tokens restored (≥ 0), or -1 if the restore failed.
     */
    fun restoreKvCache(handle: Long, filePath: String): Int {
        if (handle == 0L || !libraryLoaded.get()) {
            Log.w(TAG, "restoreKvCache: skipped (handle=$handle loaded=${libraryLoaded.get()})")
            return -1
        }
        if (!java.io.File(filePath).exists()) {
            Log.w(TAG, "restoreKvCache: file not found: $filePath")
            return -1
        }
        Log.i(TAG, "restoreKvCache handle=$handle ← $filePath")
        return nativeRestoreKvCache(handle, filePath)
    }

    /**
     * Stream token generation for [prompt].
     *
     * Returns a cold [Flow] that, when collected, runs inference on [Dispatchers.IO].
     * Each emitted [String] is one raw token piece (may be a partial UTF-8 character
     * or a word fragment — concatenate for display).
     *
     * The flow completes normally at end-of-generation and throws an exception
     * if the native bridge reports an error.
     *
     * @param handle      Handle from [initModel].
     * @param prompt      Full prompt string (including any chat template).
     * @param maxTokens   Maximum new tokens to generate.
     * @param temperature     Sampling temperature (0 = greedy, 1 = random).
     * @param topP            Top-p nucleus sampling threshold.
     * @param presencePenalty Additive penalty applied to tokens that have already
     *                        appeared in the context.  0.0 = disabled.
     */
    fun streamGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.95f,
        presencePenalty: Float = 0.0f,
    ): Flow<String> = callbackFlow {
        if (handle == 0L) {
            close(IllegalStateException("Model handle is invalid (0)"))
            return@callbackFlow
        }

        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                trySend(token)
            }
            override fun onComplete() {
                close()
            }
            override fun onError(message: String) {
                close(RuntimeException("LlamaRuntime error: $message"))
            }
        }

        // Runs synchronously; callbackFlow keeps the channel alive until close()
        try {
            nativeStreamGenerate(handle, prompt, maxTokens, temperature, topP,
                presencePenalty, callback)
        } catch (e: Exception) {
            close(e)
        }

        awaitClose { /* no cleanup needed — native call is already done */ }
    }.flowOn(Dispatchers.IO)
}
