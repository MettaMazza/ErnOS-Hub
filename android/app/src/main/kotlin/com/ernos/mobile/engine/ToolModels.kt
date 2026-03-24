package com.ernos.mobile.engine

import org.json.JSONObject

/**
 * Represents a single parsed tool call emitted by the model.
 */
data class ToolCall(
    val name: String,
    val arguments: JSONObject,
)

/**
 * Result returned from executing a [ToolCall].
 */
data class ToolResult(
    val toolName: String,
    val result: String,
    val isError: Boolean = false,
)

/**
 * Final outcome of one complete [ReActLoopManager.run] invocation.
 */
sealed class ReActLoopResult {
    /** The model called reply_to_request or returned a direct answer. */
    data class Reply(
        val message: String,
        val turnCount: Int,
    ) : ReActLoopResult()

    /** The loop hit MAX_TURNS without a clean reply_to_request. */
    data class MaxTurnsReached(
        val lastResponse: String,
        val turnCount: Int,
    ) : ReActLoopResult()

    /** An unrecoverable error occurred (bad handle, tokenisation failure, etc.). */
    data class Error(val message: String) : ReActLoopResult()
}
