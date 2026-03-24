package com.ernos.mobile.engine

import android.util.Log
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/**
 * ReActLoopManager
 *
 * Implements the HIVE/ErnOS ReAct (Reason + Act) pattern as a Kotlin coroutine:
 *
 *   Generate → parse tool calls → execute tools → inject results → repeat
 *
 * Loop terminates when:
 *   (a) The model calls reply_to_request     → [ReActLoopResult.Reply]
 *   (b) The model returns no tool calls      → treated as a direct reply
 *   (c) Turn count reaches [MAX_TURNS]       → [ReActLoopResult.MaxTurnsReached]
 *
 * Death-loop prevention: on the final turn, a strong directive is injected into
 * the context forcing the model to call reply_to_request immediately.
 *
 * Conversation is maintained as a growing string in Qwen chat-template format:
 *   <|im_start|>system … <|im_end|>
 *   <|im_start|>user   … <|im_end|>
 *   <|im_start|>assistant [generated]
 *   <|im_start|>tool   <tool_response>…</tool_response> <|im_end|>
 *   … (repeated per turn)
 *
 * Generation is delegated to [InferenceBackend], which may route tokens
 * through the local llama.cpp JNI ([InferenceBackend.LocalBackend]) or a
 * remote PC/Mac offload server ([InferenceBackend.OffloadBackend]).
 */
class ReActLoopManager(
    private val backend: InferenceBackend,
    private val systemPrompter: SystemPrompter,
    private val toolRegistry: ToolRegistry,
) {

    /**
     * User-tunable inference parameters surfaced by SettingsViewModel.
     * Passed to [run] so that each send picks up the latest DataStore values.
     *
     * Defaults mirror [SettingsViewModel] defaults so callers that don't yet
     * wire up the settings screen still get sensible behaviour.
     */
    data class InferenceParams(
        val temperature:     Float = 0.7f,
        val topP:            Float = 0.9f,
        val presencePenalty: Float = 0.0f,
        val maxTurns:        Int   = 15,
    )

    companion object {
        private const val TAG = "ReActLoopManager"

        /** Maximum reasoning turns before forcing a reply (compile-time default). */
        const val MAX_TURNS = 15

        /** Max tokens to generate per turn. */
        private const val MAX_TOKENS_PER_TURN = 512

        /**
         * Regex that matches a single <tool_call>…</tool_call> block.
         * The inner JSON group may span multiple lines.
         */
        private val TOOL_CALL_REGEX = Regex(
            pattern = """<tool_call>\s*(\{.*?})\s*</tool_call>""",
            options  = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Run the full ReAct loop for one user turn.
     *
     * All callbacks are invoked on whichever coroutine context the caller uses —
     * typically the ViewModel's [viewModelScope] which dispatches UI updates to Main.
     *
     * @param handle           Active model handle. Required for [InferenceBackend.LocalBackend];
     *                         ignored (but still passed) for [InferenceBackend.OffloadBackend].
     * @param userMessage      The raw user message for this turn.
     * @param config           Model config (multimodal flag, name, context window).
     * @param memoryContext    Tier 1-3 memory text injected into the system prompt.
     *                         Pass an empty string until Milestone 3.
     * @param inferenceParams  User-tunable sampling parameters from SettingsViewModel.
     *                         Defaults to [InferenceParams] defaults (temp=0.7, topP=0.9, etc.).
     * @param onTurnStart      Called at the start of each generation turn with its index.
     * @param onToken          Called for each streamed token; useful for live UI updates.
     * @param onToolCall       Called when a tool call is parsed from the model output.
     * @param onToolResult     Called after a tool returns its result.
     */
    suspend fun run(
        handle: Long,
        userMessage: String,
        config: SystemPrompter.ModelConfig = SystemPrompter.ModelConfig(),
        memoryContext: String = "",
        inferenceParams: InferenceParams = InferenceParams(),
        onTurnStart: (turnIndex: Int) -> Unit = {},
        onToken: (token: String) -> Unit = {},
        onToolCall: (call: ToolCall) -> Unit = {},
        onToolResult: (result: ToolResult) -> Unit = {},
    ): ReActLoopResult {
        if (handle == 0L) return ReActLoopResult.Error("Model handle is invalid (0).")

        toolRegistry.reset()

        val systemPrompt = systemPrompter.buildSystemPrompt(config, memoryContext)
        val effectiveMaxTurns = inferenceParams.maxTurns.coerceIn(1, 30)

        // Build conversation buffer in Qwen chat-template format
        val ctx = StringBuilder().apply {
            appendLine("<|im_start|>system")
            appendLine(systemPrompt)
            appendLine("<|im_end|>")
            appendLine("<|im_start|>user")
            appendLine(userMessage.trim())
            appendLine("<|im_end|>")
        }

        var turnIndex = 0

        while (turnIndex < effectiveMaxTurns) {
            val isFinalTurn = turnIndex == effectiveMaxTurns - 1
            onTurnStart(turnIndex)
            Log.i(TAG, "=== ReAct turn ${turnIndex + 1}/$effectiveMaxTurns" +
                "${if (isFinalTurn) " [FINAL]" else ""} (temp=${inferenceParams.temperature} " +
                "topP=${inferenceParams.topP}) ===")

            // Prime the assistant slot; on the final turn, inject a death-loop directive
            val prompt = if (isFinalTurn) buildFinalTurnPrompt(ctx) else buildNormalTurnPrompt(ctx)

            // ── Generate ────────────────────────────────────────────────────
            val responseBuf = StringBuilder()

            backend.streamGenerate(
                handle          = handle,
                prompt          = prompt,
                maxTokens       = MAX_TOKENS_PER_TURN,
                temperature     = inferenceParams.temperature,
                topP            = inferenceParams.topP,
                presencePenalty = inferenceParams.presencePenalty,
            ).collect { token ->
                responseBuf.append(token)
                onToken(token)
            }

            val response = responseBuf.toString()
            Log.i(TAG, "Turn ${turnIndex + 1} raw (first 300 chars): ${response.take(300)}")

            // Commit assistant turn to context
            appendAssistant(ctx, response)

            // ── Check if reply_to_request was triggered during generation ────
            toolRegistry.replyMessage?.let { reply ->
                Log.i(TAG, "reply_to_request detected via replyMessage — exiting loop")
                return ReActLoopResult.Reply(reply, turnIndex + 1)
            }

            // ── FINAL TURN: ignore any tool calls, return model output directly ──
            // Death-loop prevention: whatever the model says on the last turn is
            // treated as conversational output. Tool parsing is skipped entirely.
            if (isFinalTurn) {
                val stripped = stripToolCallBlocks(response).trim()
                val conversational = stripped.ifBlank {
                    // Model output was entirely tool-call syntax — return a safe fallback
                    "I've used all my reasoning steps. Please ask me to continue or " +
                        "rephrase your request and I'll try again."
                }
                Log.w(TAG, "Final turn reached — returning conversational output (${conversational.length} chars)")
                return ReActLoopResult.MaxTurnsReached(
                    lastResponse = conversational,
                    turnCount    = effectiveMaxTurns,
                )
            }

            // ── Parse tool calls ─────────────────────────────────────────────
            val toolCalls = parseToolCalls(response)

            if (toolCalls.isEmpty()) {
                // No tool call — model returned a direct answer
                Log.i(TAG, "No tool call found — treating response as final answer")
                return ReActLoopResult.Reply(response.trim(), turnIndex + 1)
            }

            // ── Execute tool calls ───────────────────────────────────────────
            for (call in toolCalls) {
                onToolCall(call)
                Log.i(TAG, "Executing tool: ${call.name}(${call.arguments})")

                val result = toolRegistry.execute(call)
                onToolResult(result)

                // Append tool result to conversation context
                appendToolResult(ctx, call.name, result)

                // Terminal tool — exit immediately
                if (call.name == ToolRegistry.REPLY_TO_REQUEST) {
                    Log.i(TAG, "reply_to_request tool call → loop exits cleanly")
                    return ReActLoopResult.Reply(result.result, turnIndex + 1)
                }

                // After executing any tool, re-check replyMessage
                toolRegistry.replyMessage?.let { reply ->
                    return ReActLoopResult.Reply(reply, turnIndex + 1)
                }
            }

            turnIndex++
        }

        // Should not be reachable (isFinalTurn guard above exits the loop),
        // but satisfies the Kotlin exhaustive-return requirement.
        Log.e(TAG, "Unexpected fall-through after effectiveMaxTurns loop")
        return ReActLoopResult.MaxTurnsReached("Unexpected loop termination.", effectiveMaxTurns)
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    private fun buildNormalTurnPrompt(ctx: StringBuilder): String =
        ctx.toString() + "<|im_start|>assistant\n"

    /**
     * On the final turn, inject a death-loop-prevention directive into a new
     * system message immediately before the assistant slot.
     */
    private fun buildFinalTurnPrompt(ctx: StringBuilder): String =
        ctx.toString() +
            "<|im_start|>system\n" +
            "FINAL TURN DIRECTIVE: You have reached the maximum number of reasoning steps. " +
            "You MUST call reply_to_request NOW with your best available answer. " +
            "Do NOT call any other tool.\n" +
            "<|im_end|>\n" +
            "<|im_start|>assistant\n"

    // ── Context mutation helpers ──────────────────────────────────────────────

    private fun appendAssistant(ctx: StringBuilder, response: String) {
        ctx.appendLine("<|im_start|>assistant")
        ctx.appendLine(response.trim())
        ctx.appendLine("<|im_end|>")
    }

    /**
     * Append a tool result in Qwen's standard tool-response format.
     *
     * Format:
     *   <|im_start|>tool
     *   <tool_response>
     *   {"name": "tool_name", "content": "result text", "is_error": false}
     *   </tool_response>
     *   <|im_end|>
     */
    private fun appendToolResult(ctx: StringBuilder, toolName: String, result: ToolResult) {
        val payload = JSONObject().apply {
            put("name",     toolName)
            put("content",  result.result)
            put("is_error", result.isError)
        }
        ctx.appendLine("<|im_start|>tool")
        ctx.appendLine("<tool_response>")
        ctx.appendLine(payload.toString())
        ctx.appendLine("</tool_response>")
        ctx.appendLine("<|im_end|>")
    }

    // ── Tool call parsing ─────────────────────────────────────────────────────

    /**
     * Extract all tool calls from [response].
     *
     * Expects one or more `<tool_call>{…}</tool_call>` blocks. Malformed JSON
     * is logged and skipped rather than crashing the loop.
     */
    private fun parseToolCalls(response: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()

        for (match in TOOL_CALL_REGEX.findAll(response)) {
            val raw = match.groupValues[1].trim()
            try {
                val json = JSONObject(raw)
                val name = json.optString("name", "").trim()
                if (name.isEmpty()) {
                    Log.w(TAG, "Tool call missing 'name' field: $raw")
                    continue
                }
                val args = json.optJSONObject("arguments") ?: JSONObject()
                calls.add(ToolCall(name = name, arguments = args))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tool call JSON: $raw — ${e.message}")
            }
        }

        if (calls.isEmpty() && response.contains("<tool_call>")) {
            Log.w(TAG, "Response contained <tool_call> markers but none parsed successfully")
        }

        return calls
    }

    /**
     * Remove all `<tool_call>…</tool_call>` blocks from [response] so that
     * the remaining text is purely conversational.
     * Used on the final turn to surface the model's prose rather than its
     * tool-calling syntax.
     */
    private fun stripToolCallBlocks(response: String): String =
        TOOL_CALL_REGEX.replace(response, "").replace(Regex("\\s{3,}"), "\n\n").trim()
}
