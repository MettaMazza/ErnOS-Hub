package com.ernos.mobile.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ReActLoopManager] logic that can run on the JVM.
 *
 * Because [ReActLoopManager] depends on [InferenceBackend] (JNI) and
 * [SystemPrompter] (Android Context), we test the pure data-manipulation
 * and state-machine aspects in isolation using fake collaborators and
 * equivalent Kotlin logic extracted from the real implementation.
 *
 * Covered:
 *   - MAX_TURNS constant value (15)
 *   - Tool-call regex parsing (single call, multiple calls, no call)
 *   - strip_tool_call_blocks removes all <tool_call> blocks
 *   - ToolCall data class construction
 *   - ToolResult data class construction
 *   - ReActLoopResult sealed hierarchy (Reply, MaxTurnsReached, Error)
 *   - reply_to_request detection via FakeToolRegistry
 *   - Death-loop turn counting
 */
class ReActLoopTest {

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test fun max_turns_is_fifteen() {
        assertEquals(15, ReActLoopManager.MAX_TURNS)
    }

    // ── Tool call regex parsing ───────────────────────────────────────────────

    private val TOOL_CALL_REGEX = Regex(
        pattern = """<tool_call>\s*(\{.*?})\s*</tool_call>""",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private fun parseToolCallJson(response: String): List<String> =
        TOOL_CALL_REGEX.findAll(response).map { it.groupValues[1] }.toList()

    @Test fun parse_single_tool_call() {
        val response = """
            |Let me search for that.
            |<tool_call>{"name":"web_search","arguments":{"query":"Kotlin coroutines"}}</tool_call>
        """.trimMargin()
        val calls = parseToolCallJson(response)
        assertEquals(1, calls.size)
        assertTrue(calls[0].contains("web_search"))
    }

    @Test fun parse_multiple_tool_calls() {
        val response = """
            |<tool_call>{"name":"web_search","arguments":{"query":"A"}}</tool_call>
            |some middle text
            |<tool_call>{"name":"file_read","arguments":{"path":"/tmp/x"}}</tool_call>
        """.trimMargin()
        val calls = parseToolCallJson(response)
        assertEquals(2, calls.size)
        assertTrue(calls[0].contains("web_search"))
        assertTrue(calls[1].contains("file_read"))
    }

    @Test fun parse_no_tool_calls_returns_empty_list() {
        val response = "This is a plain conversational response with no tool calls."
        assertTrue(parseToolCallJson(response).isEmpty())
    }

    @Test fun parse_multiline_json_tool_call() {
        val response = """
            |<tool_call>
            |{
            |  "name": "memory_query",
            |  "arguments": { "query": "last conversation" }
            |}
            |</tool_call>
        """.trimMargin()
        val calls = parseToolCallJson(response)
        assertEquals(1, calls.size)
        assertTrue(calls[0].contains("memory_query"))
    }

    @Test fun parse_is_case_insensitive() {
        val response = "<TOOL_CALL>{\"name\":\"web_search\",\"arguments\":{}}</TOOL_CALL>"
        val calls = parseToolCallJson(response)
        assertEquals(1, calls.size)
    }

    // ── stripToolCallBlocks ───────────────────────────────────────────────────

    private fun stripToolCallBlocks(text: String): String =
        TOOL_CALL_REGEX.replace(text, "").trim()

    @Test fun strip_removes_single_tool_call_block() {
        val response = "Thinking...\n<tool_call>{\"name\":\"web_search\"}</tool_call>\nDone."
        val stripped = stripToolCallBlocks(response)
        assertFalse(stripped.contains("<tool_call>"))
        assertTrue(stripped.contains("Thinking"))
        assertTrue(stripped.contains("Done."))
    }

    @Test fun strip_removes_all_tool_call_blocks() {
        val response =
            "<tool_call>{\"name\":\"A\"}</tool_call>\nMiddle.\n<tool_call>{\"name\":\"B\"}</tool_call>"
        val stripped = stripToolCallBlocks(response)
        assertFalse(stripped.contains("<tool_call>"))
        assertTrue(stripped.contains("Middle."))
    }

    @Test fun strip_on_no_blocks_returns_original_trimmed() {
        val text = "Plain response."
        assertEquals(text, stripToolCallBlocks(text))
    }

    // ── ToolCall data class ───────────────────────────────────────────────────

    @Test fun tool_call_holds_name_and_json_arguments() {
        val args = JSONObject().apply { put("query", "foo") }
        val call = ToolCall("web_search", args)
        assertEquals("web_search", call.name)
        assertEquals("foo", call.arguments.optString("query"))
    }

    @Test fun tool_call_empty_arguments_do_not_crash() {
        val call = ToolCall("reply_to_request", JSONObject())
        assertEquals("reply_to_request", call.name)
        assertEquals(0, call.arguments.length())
    }

    // ── ToolResult data class ─────────────────────────────────────────────────

    @Test fun tool_result_holds_tool_name_and_result() {
        val result = ToolResult(toolName = "file_read", result = "file contents")
        assertEquals("file_read",     result.toolName)
        assertEquals("file contents", result.result)
        assertFalse(result.isError)
    }

    @Test fun tool_result_error_flag_propagates() {
        val result = ToolResult(toolName = "web_search", result = "timeout", isError = true)
        assertTrue(result.isError)
        assertEquals("web_search", result.toolName)
    }

    // ── ReActLoopResult sealed hierarchy ─────────────────────────────────────

    @Test fun result_reply_carries_message_and_turn_count() {
        val r = ReActLoopResult.Reply("Hello!", turnCount = 3)
        assertEquals("Hello!", r.message)
        assertEquals(3, r.turnCount)
    }

    @Test fun result_max_turns_carries_last_response_and_count() {
        val r = ReActLoopResult.MaxTurnsReached("Fallback answer", turnCount = 15)
        assertEquals("Fallback answer", r.lastResponse)
        assertEquals(15, r.turnCount)
    }

    @Test fun result_error_carries_message() {
        val r = ReActLoopResult.Error("Model handle is invalid (0).")
        assertEquals("Model handle is invalid (0).", r.message)
    }

    // ── Turn counter logic ────────────────────────────────────────────────────

    @Test fun turn_counter_reaches_final_turn_at_max_turns_minus_one() {
        val maxTurns = ReActLoopManager.MAX_TURNS
        var turnIndex = 0
        var finalTurnFired = false

        while (turnIndex < maxTurns) {
            val isFinalTurn = turnIndex == maxTurns - 1
            if (isFinalTurn) finalTurnFired = true
            turnIndex++
        }

        assertTrue("Final turn flag must fire at MAX_TURNS - 1", finalTurnFired)
        assertEquals("Loop exits after MAX_TURNS iterations", maxTurns, turnIndex)
    }

    @Test fun reply_to_request_name_constant_is_correct() {
        assertEquals("reply_to_request", ToolRegistry.REPLY_TO_REQUEST)
    }

    // ── Fake ToolRegistry state machine ───────────────────────────────────────

    class FakeToolRegistry {
        var replyMessage: String? = null
            private set

        fun invokeReplyToRequest(message: String) {
            replyMessage = message
        }

        fun reset() {
            replyMessage = null
        }
    }

    @Test fun reply_message_is_null_before_invoke() {
        assertNull(FakeToolRegistry().replyMessage)
    }

    @Test fun reply_message_set_after_invoke() {
        val r = FakeToolRegistry()
        r.invokeReplyToRequest("Hi!")
        assertEquals("Hi!", r.replyMessage)
    }

    @Test fun reset_clears_reply_message() {
        val r = FakeToolRegistry()
        r.invokeReplyToRequest("Hi!")
        r.reset()
        assertNull(r.replyMessage)
    }

    // ── ModelConfig multimodal-filename heuristic logic ───────────────────────

    /**
     * Mirrors the filename heuristic in [ChatViewModel.loadModel].
     * Models with "vl", "vision", "multimodal", "llava", or "qwen2-vl" in the
     * filename are treated as multimodal.
     */
    private fun filenameIsMultimodal(path: String): Boolean {
        val lower = path.lowercase()
        return listOf("vl", "vision", "multimodal", "llava", "qwen2-vl").any { lower.contains(it) }
    }

    @Test fun qwen2_vl_filename_detected_as_multimodal() {
        assertTrue(filenameIsMultimodal("/sdcard/qwen2-vl-7b-Q4_K_M.gguf"))
    }

    @Test fun llava_filename_detected_as_multimodal() {
        assertTrue(filenameIsMultimodal("/sdcard/llava-1.5-7b.gguf"))
    }

    @Test fun vision_keyword_detected_as_multimodal() {
        assertTrue(filenameIsMultimodal("/models/qwen-vision-7b.gguf"))
    }

    @Test fun plain_qwen_filename_not_multimodal() {
        assertFalse(filenameIsMultimodal("/sdcard/Qwen2.5-7B-Instruct-Q4_K_M.gguf"))
    }

    @Test fun plain_llama_filename_not_multimodal() {
        assertFalse(filenameIsMultimodal("/sdcard/llama-3-8b-instruct.gguf"))
    }
}
