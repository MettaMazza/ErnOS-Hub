package com.ernos.mobile

import android.app.Application
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ernos.mobile.bridge.BridgeState
import com.ernos.mobile.engine.InferenceBackend
import com.ernos.mobile.engine.ReActLoopManager
import com.ernos.mobile.engine.ReActLoopResult
import com.ernos.mobile.engine.SystemPrompter
import com.ernos.mobile.engine.ToolCall
import com.ernos.mobile.engine.ToolRegistry
import com.ernos.mobile.engine.ToolResult
import com.ernos.mobile.glasses.GlassesFrame
import com.ernos.mobile.glasses.GlassesService
import com.ernos.mobile.glasses.GlassesServiceState
import com.ernos.mobile.glasses.GlassesState
import com.ernos.mobile.memory.MemoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

// ── Data models ──────────────────────────────────────────────────────────────

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    /** Non-null while the ReAct loop is executing a tool (shown in UI). */
    val toolActivity: String? = null,
)

enum class ModelStatus {
    NOT_LOADED,
    LOADING,
    READY,
    ERROR,
}

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ChatViewModel
 *
 * Milestone 2: inference is now routed through [ReActLoopManager].
 * The loop handles tool calling, result injection, and the reply_to_request
 * terminal automatically. Direct streaming (Milestone 1) is replaced entirely.
 *
 * UI state is exposed as Compose snapshot-backed observable properties so the
 * Compose [ChatScreen] recomposes incrementally as each token or status arrives.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"

        /** Default context window for the loaded model. Overridden via Settings. */
        private const val DEFAULT_N_CTX = 4096

        /** Thread count cap — leave headroom for OS and UI. */
        private const val MAX_INFERENCE_THREADS = 8
    }

    // ── Live inference params — read from DataStore on each sendMessage ────────

    /**
     * Read the user's current sampling parameters from [Tier5Scratchpad].
     * Called at the start of every [sendMessage] so settings changes take effect
     * immediately without restarting the app.
     */
    private suspend fun readInferenceParams(): ReActLoopManager.InferenceParams {
        val sp = memoryManager.tier5
        return ReActLoopManager.InferenceParams(
            temperature     = sp.getOrDefault(
                com.ernos.mobile.settings.SettingsViewModel.KEY_TEMPERATURE,
                com.ernos.mobile.settings.SettingsViewModel.DEFAULT_TEMPERATURE),
            topP            = sp.getOrDefault(
                com.ernos.mobile.settings.SettingsViewModel.KEY_TOP_P,
                com.ernos.mobile.settings.SettingsViewModel.DEFAULT_TOP_P),
            presencePenalty = sp.getOrDefault(
                com.ernos.mobile.settings.SettingsViewModel.KEY_PRESENCE_PENALTY,
                com.ernos.mobile.settings.SettingsViewModel.DEFAULT_PRESENCE_PENALTY),
            maxTurns        = sp.getOrDefault(
                com.ernos.mobile.settings.SettingsViewModel.KEY_MAX_TURNS,
                com.ernos.mobile.settings.SettingsViewModel.DEFAULT_MAX_TURNS),
        )
    }

    /**
     * Read nCtx from DataStore. Called in [loadModel] overload that uses the
     * default parameter so that the setting takes effect without requiring the
     * caller to pass an explicit value.
     */
    private suspend fun readNCtx(): Int =
        memoryManager.tier5.getOrDefault(
            com.ernos.mobile.settings.SettingsViewModel.KEY_N_CTX,
            com.ernos.mobile.settings.SettingsViewModel.DEFAULT_N_CTX)

    // ── State ─────────────────────────────────────────────────────────────────

    val messages      = mutableStateListOf<ChatMessage>()
    val modelStatus   = mutableStateOf(ModelStatus.NOT_LOADED)
    val statusMessage = mutableStateOf("No model loaded")
    val isGenerating  = mutableStateOf(false)

    /** Exposes the current ReAct turn number to the UI (0-based). */
    val reactTurn = mutableStateOf(0)

    /**
     * Absolute path of the most recently loaded model file.
     * Null if no model has been loaded yet in this process.
     * Observed by SettingsScreen to offer an immediate context-window reload.
     */
    val loadedModelPath = mutableStateOf<String?>(null)

    /**
     * Reload the currently-loaded model with a new context window size.
     *
     * Called automatically when the user changes the nCtx slider so that
     * the inference engine reflects the new window immediately — without
     * requiring the user to manually re-select a model from the hub.
     *
     * [nCtx] is passed directly (not read from DataStore) to avoid a race
     * condition where the DataStore write launched by [SettingsViewModel.setNCtx]
     * has not yet completed when this function is called.
     *
     * If no model is loaded ([loadedModelPath] is null) this is a no-op.
     *
     * @param nCtx The new context window size to apply. Must be in 512..16384.
     */
    fun reloadCurrentModel(nCtx: Int) {
        val path = loadedModelPath.value ?: return
        Log.i(TAG, "reloadCurrentModel: reloading '$path' with explicit nCtx=$nCtx")
        loadModel(path, nCtx)
    }

    // ── Glasses state (Milestone 5) ───────────────────────────────────────────

    /** True when GlassesService is in STREAMING state. */
    val glassesLive   = mutableStateOf(false)

    /** The latest JPEG frame from the glasses (null when not streaming). */
    val glassesFrame  = mutableStateOf<GlassesFrame?>(null)

    /** Human-readable glasses connection label for the UI. */
    val glassesLabel  = mutableStateOf("Glasses: off")

    // ── Engine instances ──────────────────────────────────────────────────────

    private val runtime       = LlamaRuntime()

    /**
     * Use the process-wide singleton from [ErnOSApplication] so that the ONNX
     * model loaded during [ErnOSApplication.onCreate] is shared between the
     * application and the ViewModel.  Creating a second [MemoryManager] would
     * start a separate Tier 2 session without the model.
     */
    private val memoryManager = ErnOSApplication.memoryManager

    private val systemPrompter = SystemPrompter(application)

    /**
     * ToolRegistry is recreated on each [sendMessage] call so it picks up the
     * current [BridgeState.offloadClient] state (connected/disconnected).
     * The stateless tools (web_search, file_read/write, memory_query) are
     * unaffected by this because they hold no mutable state between calls.
     */
    private fun buildToolRegistry() = ToolRegistry(
        context         = getApplication(),
        memoryManager   = memoryManager,
        offloadClient   = BridgeState.offloadClient,
        isMultimodal    = modelConfig.isMultimodal,
    )

    /** Model configuration — Milestone 6 wires this to SettingsScreen. */
    private var modelConfig = SystemPrompter.ModelConfig()

    /**
     * Whether the loaded model can process image tokens (Qwen-VL, LLaVA, etc.).
     * Set to [true] when a model whose filename contains VL/vision/multimodal indicators is loaded.
     * Used together with [glassesLive] to set [modelConfig.isMultimodal]:
     *   isMultimodal = modelSupportsVision && glassesStreaming
     *
     * Glasses enable the *input* (camera frames), but vision only works if the
     * *model* was trained with vision tokens. Separating these two conditions
     * prevents sending image tokens to a text-only model.
     */
    private var modelSupportsVision = false

    private var modelHandle: Long = 0L
    private var generateJob: Job? = null

    // ── Glasses observers (Milestone 5) ──────────────────────────────────────

    init {
        // Mirror GlassesServiceState.state into our Compose-observable fields.
        viewModelScope.launch {
            GlassesServiceState.state.collectLatest { state ->
                val streaming = state == GlassesState.STREAMING
                glassesLive.value  = streaming
                glassesLabel.value = when (state) {
                    GlassesState.DISCONNECTED  -> "Glasses: off"
                    GlassesState.SCANNING      -> "Glasses: scanning…"
                    GlassesState.PAIRING       -> "Glasses: pairing…"
                    GlassesState.STREAMING     -> "Glasses: live ●"
                    GlassesState.RECONNECTING  -> "Glasses: reconnecting…"
                    GlassesState.ERROR         -> "Glasses: error"
                }
                // isMultimodal = model supports vision AND glasses are streaming.
                // Never force-true for a text-only model just because glasses are on,
                // and never force-false when glasses stop if the model flag was already false.
                val effectiveMultimodal = modelSupportsVision && streaming
                modelConfig = modelConfig.copy(isMultimodal = effectiveMultimodal)
                Log.d(TAG, "Glasses state: $state — modelSupportsVision=$modelSupportsVision " +
                    "streaming=$streaming → isMultimodal=$effectiveMultimodal")
            }
        }

        // Mirror latest camera frame
        viewModelScope.launch {
            GlassesServiceState.currentFrame.collectLatest { frame ->
                glassesFrame.value = frame
            }
        }

        // Route hands-free transcripts as user messages
        viewModelScope.launch {
            for (transcript in GlassesServiceState.transcriptChannel) {
                Log.i(TAG, "Hands-free transcript: $transcript")
                sendMessage(transcript)
            }
        }
    }

    // ── Glasses control (Milestone 5) ─────────────────────────────────────────

    /**
     * Enable the Glasses Live toggle.
     * Starts [GlassesService] which scans for Meta Ray-Ban glasses via BLE.
     */
    fun enableGlasses() {
        GlassesService.start(getApplication())
        Log.i(TAG, "GlassesService start requested")
    }

    /**
     * Disable the Glasses Live toggle.
     * Stops [GlassesService] and resets isMultimodal.
     */
    fun disableGlasses() {
        GlassesService.stop(getApplication())
        glassesLive.value  = false
        glassesFrame.value = null
        glassesLabel.value = "Glasses: off"
        // isMultimodal is false when glasses are off, regardless of model capability.
        // The GlassesServiceState collector will fire a DISCONNECTED event which
        // re-evaluates (modelSupportsVision && false) = false, but we eagerly update here
        // so the UI and next sendMessage() see the correct value immediately.
        modelConfig = modelConfig.copy(isMultimodal = false)
        Log.i(TAG, "GlassesService stop requested")
    }

    // ── Model management ──────────────────────────────────────────────────────

    /**
     * Load a GGUF model from [modelPath] on a background thread.
     * Path may be absolute or relative to [Application.getExternalFilesDir].
     */
    fun loadModel(modelPath: String, nCtx: Int = -1) {
        if (modelStatus.value == ModelStatus.LOADING) return

        val file = File(modelPath)
        if (!file.exists()) {
            statusMessage.value = "Model file not found:\n${file.absolutePath}"
            modelStatus.value   = ModelStatus.ERROR
            return
        }

        modelStatus.value   = ModelStatus.LOADING
        statusMessage.value = "Loading model…"

        // Detect vision capability from filename before loading (fast heuristic).
        val lowerPath = File(modelPath).name.lowercase()
        val isVl = lowerPath.contains("vl") ||
                   lowerPath.contains("vision") ||
                   lowerPath.contains("multimodal") ||
                   lowerPath.contains("llava") ||
                   lowerPath.contains("qwen2-vl") ||
                   lowerPath.contains("qwen2vl")
        modelSupportsVision = isVl
        Log.i(TAG, "loadModel: modelSupportsVision=$isVl (from filename: ${File(modelPath).name})")

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // When nCtx is -1 (caller did not specify), read from DataStore so that
            // the Settings screen value is always honoured.
            val effectiveNCtx = if (nCtx == -1) readNCtx() else nCtx
            try {
                if (modelHandle != 0L) {
                    runtime.freeModel(modelHandle)
                    modelHandle = 0L
                }
                modelHandle = runtime.initModel(
                    modelPath  = modelPath,
                    nCtx       = effectiveNCtx,
                    nThreads   = Runtime.getRuntime().availableProcessors()
                        .coerceAtMost(MAX_INFERENCE_THREADS),
                    nGpuLayers = 0,
                )
                if (modelHandle != 0L) {
                    modelStatus.value     = ModelStatus.READY
                    statusMessage.value   = "Model ready" +
                        if (isVl) " (vision-language)" else " (text-only)"
                    loadedModelPath.value = modelPath
                    Log.i(TAG, "Model loaded: handle=$modelHandle nCtx=$effectiveNCtx vl=$isVl")
                    // Update modelConfig with the model name, and recompute isMultimodal
                    // now that we know both the model's vision capability AND the current
                    // glasses state.  This handles the case where glasses were enabled
                    // BEFORE a vision model was loaded (collector won't re-fire).
                    val currentlyStreaming = glassesLive.value
                    modelConfig = modelConfig.copy(
                        modelName   = File(modelPath).nameWithoutExtension,
                        isMultimodal = isVl && currentlyStreaming,
                    )
                    Log.d(TAG, "After model load: isMultimodal=${modelConfig.isMultimodal} " +
                        "(vl=$isVl glassesLive=$currentlyStreaming)")
                    // Expose to BridgeService so bridges can run inference
                    BridgeState.llamaRuntime = runtime
                    BridgeState.modelHandle  = modelHandle
                } else {
                    modelSupportsVision     = false
                    modelStatus.value       = ModelStatus.ERROR
                    statusMessage.value     = "Failed to load model (null handle)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadModel error: ${e.message}", e)
                modelSupportsVision     = false
                modelStatus.value       = ModelStatus.ERROR
                statusMessage.value     = "Load error: ${e.message}"
            }
        }
    }

    fun unloadModel() {
        cancelGeneration()
        runtime.freeModel(modelHandle)
        modelHandle            = 0L
        modelSupportsVision    = false
        modelConfig            = modelConfig.copy(isMultimodal = false)
        BridgeState.llamaRuntime = null
        BridgeState.modelHandle  = 0L
        modelStatus.value      = ModelStatus.NOT_LOADED
        statusMessage.value    = "No model loaded"
    }

    // ── Message generation (ReAct loop) ───────────────────────────────────────

    /**
     * Send [text] through the full ReAct loop.
     *
     * The loop generates, parses tool calls, executes tools, re-injects results,
     * and loops until reply_to_request is called or MAX_TURNS is reached.
     *
     * UI updates happen inline via Compose snapshot state — no explicit dispatcher
     * switch is needed for state mutations (AndroidViewModel / Compose handles it).
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (modelStatus.value != ModelStatus.READY) {
            statusMessage.value = "Load a model first"
            return
        }
        if (isGenerating.value) return

        // Add user bubble
        messages.add(ChatMessage(content = text.trim(), isUser = true))

        // Add streaming AI placeholder
        val placeholderIdx = messages.size
        messages.add(ChatMessage(content = "", isUser = false, isStreaming = true))

        isGenerating.value = true
        reactTurn.value    = 0

        generateJob = viewModelScope.launch {

            // Buffer for the current generation turn's tokens
            val tokenBuffer = StringBuilder()

            try {
                // ── Retrieval-first: retrieve BEFORE storing the user message ──────
                val memoryContext = try {
                    memoryManager.retrieveContext(text)
                } catch (e: Exception) {
                    Log.w(TAG, "Memory retrieval error (non-fatal): ${e.message}")
                    ""
                }

                // Store user message AFTER retrieval
                try {
                    memoryManager.storeUserMessage(text, modelConfig.contextWindow)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to store user message in memory: ${e.message}")
                }

                // Choose backend and build fresh ToolRegistry + ReActLoopManager
                // per send so connection state is always current.
                val currentOffload  = BridgeState.offloadClient
                val isOffloaded     = currentOffload?.isConnected == true
                val backend: InferenceBackend = if (isOffloaded) {
                    InferenceBackend.OffloadBackend(currentOffload!!)
                } else {
                    InferenceBackend.LocalBackend(runtime)
                }
                val toolRegistry    = buildToolRegistry()
                val effectiveConfig = modelConfig.copy(isOffloaded = isOffloaded)
                val reactLoop       = ReActLoopManager(backend, systemPrompter, toolRegistry)
                if (isOffloaded) Log.i(TAG, "Inference routed to offload server")

                // Read live settings from DataStore so every send picks up
                // the latest values from SettingsScreen immediately.
                val inferenceParams = readInferenceParams()
                Log.d(TAG, "InferenceParams: temp=${inferenceParams.temperature} " +
                    "topP=${inferenceParams.topP} maxTurns=${inferenceParams.maxTurns}")

                val result = reactLoop.run(
                    handle          = modelHandle,
                    userMessage     = text,
                    config          = effectiveConfig,
                    memoryContext   = memoryContext,
                    inferenceParams = inferenceParams,

                    onTurnStart = { turn ->
                        reactTurn.value = turn
                        tokenBuffer.clear()
                        Log.d(TAG, "ReAct turn $turn starting")
                        // Clear the streaming content so each turn's output is shown fresh
                        updateAiMessage(placeholderIdx) { copy(content = "", toolActivity = null) }
                    },

                    onToken = { token ->
                        tokenBuffer.append(token)
                        updateAiMessage(placeholderIdx) { copy(content = tokenBuffer.toString()) }
                    },

                    onToolCall = { call: ToolCall ->
                        Log.i(TAG, "Tool call → ${call.name}(${call.arguments})")
                        val activity = formatToolActivity(call)
                        updateAiMessage(placeholderIdx) {
                            copy(content = tokenBuffer.toString(), toolActivity = activity)
                        }
                    },

                    onToolResult = { res: ToolResult ->
                        Log.i(TAG, "Tool result ← ${res.toolName}: ${res.result.take(120)}")
                        // Clear token buffer; next turn will start fresh generation
                        tokenBuffer.clear()
                        val activity = if (res.isError) "⚠ ${res.toolName}: error" else null
                        updateAiMessage(placeholderIdx) { copy(toolActivity = activity) }
                    },
                )

                // Loop finished — set final content
                val finalText = when (result) {
                    is ReActLoopResult.Reply            -> result.message
                    is ReActLoopResult.MaxTurnsReached  -> result.lastResponse
                    is ReActLoopResult.Error            -> "Error: ${result.message}"
                }

                Log.i(TAG, "ReAct loop done after ${
                    when (result) {
                        is ReActLoopResult.Reply           -> result.turnCount
                        is ReActLoopResult.MaxTurnsReached -> result.turnCount
                        else -> -1
                    }
                } turn(s): ${finalText.take(100)}")

                updateAiMessage(placeholderIdx) {
                    copy(content = finalText, isStreaming = false, toolActivity = null)
                }

                // Store AI response in memory AFTER the loop finishes
                if (result !is ReActLoopResult.Error) {
                    try {
                        memoryManager.storeAiResponse(finalText, modelConfig.contextWindow)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to store AI response in memory: ${e.message}")
                    }
                }

            } catch (e: CancellationException) {
                // User cancelled — freeze partial output
                updateAiMessage(placeholderIdx) {
                    copy(
                        content      = tokenBuffer.toString().ifBlank { "[cancelled]" },
                        isStreaming  = false,
                        toolActivity = null,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "ReAct loop error: ${e.message}", e)
                updateAiMessage(placeholderIdx) {
                    copy(
                        content      = "Error: ${e.message}",
                        isStreaming  = false,
                        toolActivity = null,
                    )
                }
            } finally {
                isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        runtime.cancelGenerate(modelHandle)
        generateJob?.cancel()
        generateJob = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelGeneration()

        // Capture handle before clearing, so the coroutine can free it after teardown.
        val handleToFree = modelHandle
        modelHandle = 0L

        // Serialize KV cache and run session teardown BEFORE freeing the model.
        // Pass handleToFree explicitly because modelHandle is already 0 at this point.
        // freeModel is called inside the coroutine after teardown completes to avoid
        // a race where saveKvCache uses a handle that has already been freed.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                triggerSessionTeardown(handle = handleToFree)
            } catch (e: Exception) {
                Log.w(TAG, "Session teardown error: ${e.message}")
            } finally {
                runtime.freeModel(handleToFree)
            }
        }
    }

    /**
     * Restore the KV cache on resume.
     *
     * 1. Reads the file path persisted in Tier 5 from the previous session.
     * 2. If a valid file exists and the model is loaded, restores the native
     *    KV cache via [LlamaRuntime.restoreKvCache].
     * 3. Returns the number of tokens restored (≥ 0), or -1 if skipped/failed.
     *
     * Call this from [MainActivity.onResume] after the model is loaded.
     */
    suspend fun restoreKvCache(): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val filePath = memoryManager.tier5.loadKvCacheTag() ?: run {
            Log.d(TAG, "restoreKvCache: no persisted KV-cache path")
            return@withContext -1
        }
        if (modelHandle == 0L) {
            Log.d(TAG, "restoreKvCache: model not loaded, skipping")
            return@withContext -1
        }
        val tokensRestored = runtime.restoreKvCache(modelHandle, filePath)
        if (tokensRestored >= 0) {
            Log.i(TAG, "restoreKvCache: restored $tokensRestored tokens from $filePath")
        } else {
            Log.w(TAG, "restoreKvCache: restore failed for $filePath")
        }
        tokensRestored
    }

    /**
     * Serialize the KV cache to disk and persist the file path in Tier 5.
     *
     * 1. If a valid [handle] is provided (> 0), calls [LlamaRuntime.saveKvCache]
     *    to write the binary state file to the app's cache directory.
     * 2. Saves the file path as the KV-cache tag in [Tier5Scratchpad] so it can
     *    be found by [restoreKvCache] on the next resume.
     * 3. Then runs the regular session teardown (ONNX session close, Tier 2
     *    flush guard, etc.) via [MemoryManager.runSessionTeardown].
     *
     * @param handle  The model handle to use for saving; defaults to [modelHandle].
     *                [onCleared] passes the captured pre-zeroed handle so the KV
     *                cache can be saved even after modelHandle is set to 0.
     *
     * Called from [MainActivity.onStop] and [onCleared].
     */
    suspend fun triggerSessionTeardown(handle: Long = modelHandle) {
        val kvCacheFilePath = if (handle != 0L) {
            val cacheDir = getApplication<android.app.Application>().cacheDir
            val file     = java.io.File(cacheDir, "kv_cache_${modelConfig.modelName.replace("/", "_")}.bin")
            val saved    = runtime.saveKvCache(handle, file.absolutePath)
            if (saved) {
                Log.i(TAG, "triggerSessionTeardown: KV cache saved → ${file.absolutePath}")
                file.absolutePath
            } else {
                Log.w(TAG, "triggerSessionTeardown: saveKvCache failed, tag cleared")
                null
            }
        } else {
            null
        }
        memoryManager.runSessionTeardown(kvCacheTag = kvCacheFilePath ?: "")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Mutate the AI message at absolute [index] in [messages].
     * Safe to call even when the index has shifted (falls back to last AI message).
     */
    private fun updateAiMessage(index: Int, block: ChatMessage.() -> ChatMessage) {
        val safeIdx = if (index < messages.size) index else messages.indexOfLast { !it.isUser }
        if (safeIdx >= 0) {
            messages[safeIdx] = messages[safeIdx].block()
        }
    }

    /**
     * Human-readable label shown in the streaming bubble while a tool is running.
     */
    private fun formatToolActivity(call: ToolCall): String {
        val argsPreview = call.arguments.toString().take(60)
        return when (call.name) {
            "web_search"    -> "Searching: ${call.arguments.optString("query", "…")}"
            "file_read"     -> "Reading: ${call.arguments.optString("path", "…")}"
            "file_write"    -> "Writing: ${call.arguments.optString("path", "…")}"
            "memory_query"  -> "Querying memory: ${call.arguments.optString("query", "…")}"
            "bash_execute"  -> "Executing on host: ${call.arguments.optString("command", "…").take(50)}"
            "terminal_read" -> "Reading on host: ${call.arguments.optString("path", "…")}"
            "finder_open"   -> "Opening in Finder: ${call.arguments.optString("path", "…")}"
            ToolRegistry.REPLY_TO_REQUEST -> "Preparing final reply…"
            else            -> "Tool: ${call.name}($argsPreview)"
        }
    }
}
