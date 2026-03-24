package com.ernos.mobile.engine

import com.ernos.mobile.LlamaRuntime
import com.ernos.mobile.offload.OffloadClient
import kotlinx.coroutines.flow.Flow

/**
 * InferenceBackend
 *
 * Single interface used by [ReActLoopManager] to generate tokens.
 * Two concrete implementations are provided:
 *
 *  - [LocalBackend]   — delegates to [LlamaRuntime] (on-device llama.cpp)
 *  - [OffloadBackend] — delegates to [OffloadClient] (remote PC/Mac server)
 *
 * Callers choose the backend based on [OffloadClient.isConnected]:
 *
 *   val backend: InferenceBackend =
 *       if (offloadClient?.isConnected == true)
 *           InferenceBackend.OffloadBackend(offloadClient!!)
 *       else
 *           InferenceBackend.LocalBackend(llamaRuntime)
 */
interface InferenceBackend {

    /**
     * Stream generated tokens for [prompt].
     *
     * @param handle          Active model handle — used only by [LocalBackend].
     *                        [OffloadBackend] ignores it (model is loaded on the server).
     * @param prompt          Full prompt string (Qwen chat-template encoded).
     * @param maxTokens       Maximum number of tokens to generate.
     * @param temperature     Sampling temperature.
     * @param topP            Nucleus sampling p.
     * @param presencePenalty Additive penalty for tokens already in context (0.0 = off).
     */
    fun streamGenerate(
        handle:           Long,
        prompt:           String,
        maxTokens:        Int   = 512,
        temperature:      Float = 0.7f,
        topP:             Float = 0.95f,
        presencePenalty:  Float = 0.0f,
    ): Flow<String>

    // ── Concrete implementations ───────────────────────────────────────────────

    /**
     * Routes generation through the local [LlamaRuntime] (llama.cpp JNI).
     */
    class LocalBackend(private val runtime: LlamaRuntime) : InferenceBackend {
        override fun streamGenerate(
            handle:           Long,
            prompt:           String,
            maxTokens:        Int,
            temperature:      Float,
            topP:             Float,
            presencePenalty:  Float,
        ): Flow<String> = runtime.streamGenerate(
            handle          = handle,
            prompt          = prompt,
            maxTokens       = maxTokens,
            temperature     = temperature,
            topP            = topP,
            presencePenalty = presencePenalty,
        )
    }

    /**
     * Routes generation through [OffloadClient] to a remote PC/Mac server.
     * The [handle] parameter is accepted for interface parity but is not sent
     * to the server; the server manages its own model lifecycle.
     */
    class OffloadBackend(private val client: OffloadClient) : InferenceBackend {
        override fun streamGenerate(
            handle:           Long,
            prompt:           String,
            maxTokens:        Int,
            temperature:      Float,
            topP:             Float,
            presencePenalty:  Float,
        ): Flow<String> = client.streamGenerate(
            prompt          = prompt,
            maxTokens       = maxTokens,
            temperature     = temperature,
            topP            = topP,
            presencePenalty = presencePenalty,
        )
    }
}
