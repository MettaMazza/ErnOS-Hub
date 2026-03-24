package com.ernos.mobile.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tier 4 — Timeline Log
 *
 * Append-only JSONL (JSON Lines) file writer.  Each line is a structured event
 * object with at minimum:
 *   { "ts": <epoch_ms>, "type": "...", "content": "..." }
 *
 * Events are appended synchronously on [Dispatchers.IO] and the file is flushed
 * after each write to guarantee durability even if the process is killed.
 *
 * Search API:
 *   [search] loads the JSONL file lazily and filters by date-range and/or keyword.
 *   This is O(n) but acceptable for the timeline sizes we expect (< 10k events).
 *
 * File location:
 *   `<external_files_dir>/ernos_timeline.jsonl`
 *   This path requires no runtime storage permission on API 29+.
 */
class Tier4Timeline(context: Context) {

    companion object {
        private const val TAG       = "Tier4Timeline"
        private const val FILENAME  = "ernos_timeline.jsonl"

        /** Well-known event type constants. */
        const val TYPE_USER_MESSAGE  = "user_message"
        const val TYPE_AI_RESPONSE   = "ai_response"
        const val TYPE_TOOL_CALL     = "tool_call"
        const val TYPE_TOOL_RESULT   = "tool_result"
        const val TYPE_SESSION_START = "session_start"
        const val TYPE_SESSION_END   = "session_end"
        const val TYPE_MEMORY_FLUSH  = "memory_flush"
        const val TYPE_GRAPH_UPDATE  = "graph_update"
        const val TYPE_CUSTOM        = "custom"

        private val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    }

    private val timelineFile: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        FILENAME,
    )

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Append a structured event to the timeline.
     *
     * [extraFields] is a map of additional JSON key-value pairs merged into the event.
     */
    suspend fun logEvent(
        type: String,
        content: String,
        extraFields: Map<String, Any> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val event = JSONObject().apply {
            put("ts",      System.currentTimeMillis())
            put("type",    type)
            put("content", content)
            for ((k, v) in extraFields) put(k, v)
        }
        appendLine(event.toString())
    }

    /** Convenience overload for a user or AI message event. */
    suspend fun logMessage(role: String, text: String) {
        val type = if (role == "user") TYPE_USER_MESSAGE else TYPE_AI_RESPONSE
        logEvent(type, text, mapOf("role" to role))
    }

    /** Log a tool call for auditability. */
    suspend fun logToolCall(toolName: String, arguments: String) {
        logEvent(TYPE_TOOL_CALL, toolName, mapOf("arguments" to arguments))
    }

    /** Log a tool result. */
    suspend fun logToolResult(toolName: String, result: String, isError: Boolean) {
        logEvent(TYPE_TOOL_RESULT, result, mapOf("tool" to toolName, "is_error" to isError))
    }

    /** Mark the start of a new session with a timestamp. */
    suspend fun logSessionStart() {
        logEvent(TYPE_SESSION_START, "Session started")
    }

    /** Mark the end of a session. */
    suspend fun logSessionEnd() {
        logEvent(TYPE_SESSION_END, "Session ended")
    }

    // ── Search API ────────────────────────────────────────────────────────────

    /**
     * Search the timeline for events matching the given filters.
     *
     * @param keyword       Case-insensitive substring match on the `content` field.
     *                      Null to skip keyword filtering.
     * @param fromDate      Inclusive start date (ISO 8601: "yyyy-MM-dd").  Null for no lower bound.
     * @param toDate        Inclusive end date.  Null for no upper bound.
     * @param type          Filter to a specific event type.  Null to include all types.
     * @param limit         Maximum number of results to return (newest first).
     */
    suspend fun search(
        keyword: String? = null,
        fromDate: String? = null,
        toDate: String? = null,
        type: String? = null,
        limit: Int = 20,
    ): List<TimelineEvent> = withContext(Dispatchers.IO) {
        if (!timelineFile.exists()) return@withContext emptyList()

        val fromMs = fromDate?.let { localDateToEpochMs(it) }
        val toMs   = toDate?.let { localDateToEpochMs(it, endOfDay = true) }
        val kw     = keyword?.lowercase()

        timelineFile.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                parseLine(line)
            }.filter { event ->
                (fromMs == null || event.timestampMs >= fromMs) &&
                (toMs   == null || event.timestampMs <= toMs) &&
                (type   == null || event.type == type) &&
                (kw     == null || event.content.lowercase().contains(kw))
            }.toList()
        }
            .sortedByDescending { it.timestampMs }
            .take(limit)
    }

    /**
     * Return a human-readable summary of the last [maxEvents] events for
     * injection into the system prompt.
     */
    suspend fun recentSummary(maxEvents: Int = 10, keyword: String? = null): String =
        withContext(Dispatchers.IO) {
            val events = search(keyword = keyword, limit = maxEvents)
            if (events.isEmpty()) return@withContext ""

            events.joinToString("\n") { e ->
                val date = Instant.ofEpochMilli(e.timestampMs)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                "[$date] ${e.type}: ${e.content.take(120)}"
            }
        }

    // ── Data class ────────────────────────────────────────────────────────────

    data class TimelineEvent(
        val timestampMs: Long,
        val type: String,
        val content: String,
        val rawJson: String,
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun appendLine(json: String) {
        try {
            PrintWriter(FileWriter(timelineFile, /* append = */ true)).use { pw ->
                pw.println(json)
                pw.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write timeline event: ${e.message}", e)
        }
    }

    private fun parseLine(line: String): TimelineEvent? {
        return try {
            if (line.isBlank()) return null
            val obj = JSONObject(line)
            TimelineEvent(
                timestampMs = obj.optLong("ts", 0L),
                type        = obj.optString("type", TYPE_CUSTOM),
                content     = obj.optString("content", ""),
                rawJson     = line,
            )
        } catch (e: Exception) {
            null  // Malformed line — skip silently
        }
    }

    private fun localDateToEpochMs(isoDate: String, endOfDay: Boolean = false): Long {
        return try {
            val date = LocalDate.parse(isoDate, DateTimeFormatter.ISO_LOCAL_DATE)
            val zdt  = if (endOfDay) {
                date.atTime(23, 59, 59).atZone(ZoneId.systemDefault())
            } else {
                date.atStartOfDay(ZoneId.systemDefault())
            }
            zdt.toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse date '$isoDate': ${e.message}")
            0L
        }
    }
}
