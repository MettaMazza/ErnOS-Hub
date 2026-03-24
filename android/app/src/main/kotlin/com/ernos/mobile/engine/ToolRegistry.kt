package com.ernos.mobile.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.ernos.mobile.glasses.GlassesServiceState
import com.ernos.mobile.glasses.VisionEncoder
import com.ernos.mobile.offload.OffloadClient
import com.ernos.mobile.offload.OffloadException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ToolRegistry
 *
 * Concrete implementations for every tool in the ErnOS tool schema.
 * All tool executions are dispatched to [Dispatchers.IO].
 *
 * Tools:
 *   web_search        — DuckDuckGo Instant Answer API (OkHttp GET)
 *   file_read         — Read text file from app storage or absolute path (SAF-aware)
 *   file_write        — Write/append text to file on device (SAF-aware)
 *   memory_query      — Real Tier 1-4 retrieval via [MemoryManager] (Milestone 3)
 *   reply_to_request  — Terminal tool; sets [replyMessage] and ends the loop
 *
 * Milestone 4 adds: bash_execute, terminal_read, finder_open (host-only tools when offloaded)
 * Milestone 5 adds: describe_image (vision tool, gated by isMultimodal)
 *
 * @param memoryManager  Optional [MemoryManager] instance; if null, memory_query
 *                       returns a polite fallback rather than crashing.
 * @param offloadClient  Optional [OffloadClient] instance; when non-null and connected,
 *                       host-only tools (bash_execute, terminal_read, finder_open) are enabled.
 */
class ToolRegistry(
    private val context: Context,
    private val memoryManager: com.ernos.mobile.memory.MemoryManager? = null,
    private val offloadClient: OffloadClient? = null,
    /**
     * Whether the loaded model supports vision/image tokens.
     * Must be [true] for [describeImage] to produce image content; when [false],
     * calling describe_image returns an error so the model is never sent
     * image tokens it cannot interpret.
     */
    private val isMultimodal: Boolean = false,
) {

    companion object {
        private const val TAG = "ToolRegistry"

        /** Name of the terminal tool that exits the ReAct loop. */
        const val REPLY_TO_REQUEST = "reply_to_request"

        /** Maximum file size (bytes) the file_read tool will load. */
        private const val MAX_FILE_BYTES = 1_000_000L  // 1 MB

        /** DuckDuckGo instant-answer endpoint. */
        private const val DDG_URL = "https://api.duckduckgo.com/"
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /**
     * The message passed to [REPLY_TO_REQUEST].
     * Null until the terminal tool is invoked. Reset by [reset].
     */
    var replyMessage: String? = null
        private set

    /** Reset per-loop state before starting a new [ReActLoopManager.run]. */
    fun reset() {
        replyMessage = null
    }

    // ── HTTP client ───────────────────────────────────────────────────────────

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Dispatch ──────────────────────────────────────────────────────────────

    /**
     * Execute a [ToolCall] and return a [ToolResult].
     * All work is done on [Dispatchers.IO].
     */
    suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "▶ ${call.name}(${call.arguments})")
        try {
            when (call.name) {
                "web_search"    -> webSearch(call.arguments.optString("query", ""))
                "file_read"     -> fileRead(call.arguments.optString("path", ""))
                "file_write"    -> fileWrite(
                    path    = call.arguments.optString("path", ""),
                    content = call.arguments.optString("content", ""),
                    append  = call.arguments.optBoolean("append", false),
                )
                "memory_query"   -> memoryQuery(call.arguments)
                REPLY_TO_REQUEST -> replyToRequest(call.arguments.optString("message", ""))
                "bash_execute"   -> bashExecute(call.arguments.optString("command", ""))
                "terminal_read"  -> terminalRead(call.arguments.optString("path", ""))
                "finder_open"    -> finderOpen(call.arguments.optString("path", ""))
                "describe_image" -> describeImage(call.arguments.optString("image_path", ""))
                else -> ToolResult(
                    toolName = call.name,
                    result   = "Unknown tool: '${call.name}'. Available tools: " +
                        "web_search, file_read, file_write, memory_query, reply_to_request" +
                        if (offloadClient?.isConnected == true) ", bash_execute, terminal_read, finder_open" else "" +
                        if (GlassesServiceState.currentFrame.value != null) ", describe_image" else "",
                    isError  = true,
                )
            }.also { r ->
                if (r.isError) {
                    Log.w(TAG, "✗ ${call.name}: ${r.result}")
                } else {
                    Log.i(TAG, "✓ ${call.name}: ${r.result.take(120)}${if (r.result.length > 120) "…" else ""}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool '${call.name}' threw exception: ${e.message}", e)
            ToolResult(
                toolName = call.name,
                result   = "Tool execution error: ${e.message}",
                isError  = true,
            )
        }
    }

    // ── Tool implementations ──────────────────────────────────────────────────

    /**
     * web_search — queries DuckDuckGo Instant Answer API.
     *
     * Returns the abstract text, answer snippet, and up to 3 related topics.
     * Falls back to "No results found" when the API returns an empty response.
     */
    private fun webSearch(query: String): ToolResult {
        if (query.isBlank()) {
            return ToolResult("web_search", "Error: 'query' parameter cannot be empty.", isError = true)
        }

        val encodedQuery = query.trim().replace(" ", "+")
        val url = "$DDG_URL?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"

        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "ErnOS/1.0 (Android)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ToolResult(
                        "web_search",
                        "HTTP ${response.code} from search API.",
                        isError = true,
                    )
                }

                val body = response.body?.string()
                    ?: return ToolResult("web_search", "Empty response from search API.", isError = true)

                parseSearchResponse(query, body)
            }
        } catch (e: IOException) {
            ToolResult("web_search", "Network error: ${e.message}", isError = true)
        }
    }

    private fun parseSearchResponse(query: String, json: String): ToolResult {
        return try {
            val obj = JSONObject(json)
            val sb = StringBuilder()

            // Direct answer (e.g. calculator, conversions)
            val answer = obj.optString("Answer", "")
            if (answer.isNotEmpty()) {
                sb.appendLine("Answer: $answer")
            }

            // Abstract paragraph (Wikipedia, etc.)
            val abstract = obj.optString("AbstractText", "")
            val abstractSource = obj.optString("AbstractSource", "")
            if (abstract.isNotEmpty()) {
                sb.appendLine(abstract)
                if (abstractSource.isNotEmpty()) sb.appendLine("Source: $abstractSource")
            }

            // Related topics (up to 5)
            val topics = obj.optJSONArray("RelatedTopics")
            if (topics != null && topics.length() > 0) {
                sb.appendLine("\nRelated:")
                var count = 0
                for (i in 0 until topics.length()) {
                    if (count >= 5) break
                    val topic = topics.optJSONObject(i) ?: continue
                    val text = topic.optString("Text", "")
                    if (text.isNotEmpty()) {
                        sb.appendLine("• $text")
                        count++
                    }
                }
            }

            val result = sb.toString().trim()
            if (result.isEmpty()) {
                ToolResult("web_search", "No results found for: \"$query\"")
            } else {
                ToolResult("web_search", result)
            }
        } catch (e: Exception) {
            ToolResult("web_search", "Failed to parse search response: ${e.message}", isError = true)
        }
    }

    /**
     * file_read — read text from device storage using SAF-aware dispatch.
     *
     * Path routing:
     *   content://…  → Storage Access Framework (DocumentFile / ContentResolver).
     *                   These URIs come from user-granted tree permissions (ACTION_OPEN_DOCUMENT_TREE).
     *   /…           → Absolute File path (app-private dirs, /sdcard/ if permission held).
     *   otherwise    → Relative path anchored to app-specific external files directory.
     *
     * Refuses to load files larger than 1 MB.
     */
    private fun fileRead(path: String): ToolResult {
        if (path.isBlank()) {
            return ToolResult("file_read", "Error: 'path' parameter cannot be empty.", isError = true)
        }

        // SAF content:// URI
        if (path.startsWith("content://")) {
            return fileReadSaf(path)
        }

        // Direct File path
        val file = resolveFile(path)

        if (!file.exists()) {
            return ToolResult("file_read", "File not found: ${file.absolutePath}", isError = true)
        }
        if (!file.canRead()) {
            return ToolResult("file_read", "Permission denied: ${file.absolutePath}", isError = true)
        }
        if (file.isDirectory) {
            val entries = file.listFiles()?.joinToString("\n") { e ->
                "${if (e.isDirectory) "[DIR] " else "[FILE]"}${e.name} (${e.length()} bytes)"
            } ?: "(empty directory)"
            return ToolResult("file_read", "Directory listing for ${file.absolutePath}:\n$entries")
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolResult(
                "file_read",
                "File too large (${file.length()} bytes; limit ${MAX_FILE_BYTES} bytes).",
                isError = true,
            )
        }

        return try {
            ToolResult("file_read", file.readText())
        } catch (e: IOException) {
            ToolResult("file_read", "Read error: ${e.message}", isError = true)
        }
    }

    /**
     * Read a file identified by a SAF `content://` URI.
     * The calling app must hold a persisted URI permission for this URI
     * (granted via [Intent.ACTION_OPEN_DOCUMENT] or [Intent.ACTION_OPEN_DOCUMENT_TREE]).
     */
    private fun fileReadSaf(uriString: String): ToolResult {
        return try {
            val uri  = Uri.parse(uriString)
            val doc  = DocumentFile.fromSingleUri(context, uri)
                ?: return ToolResult("file_read", "Cannot resolve SAF URI: $uriString", isError = true)

            if (!doc.canRead()) {
                return ToolResult("file_read", "SAF permission denied for URI: $uriString", isError = true)
            }

            val size = doc.length()
            if (size > MAX_FILE_BYTES) {
                return ToolResult(
                    "file_read",
                    "File too large ($size bytes; limit ${MAX_FILE_BYTES} bytes).",
                    isError = true,
                )
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                ToolResult("file_read", stream.bufferedReader().readText())
            } ?: ToolResult("file_read", "Could not open input stream for URI: $uriString", isError = true)
        } catch (e: Exception) {
            ToolResult("file_read", "SAF read error: ${e.message}", isError = true)
        }
    }

    /**
     * file_write — write or append text to device storage using SAF-aware dispatch.
     *
     * Path routing mirrors [fileRead]:
     *   content://…  → SAF ContentResolver openOutputStream.
     *   absolute/rel → Direct File write with auto-created parent directories.
     */
    private fun fileWrite(path: String, content: String, append: Boolean): ToolResult {
        if (path.isBlank()) {
            return ToolResult("file_write", "Error: 'path' parameter cannot be empty.", isError = true)
        }

        // SAF content:// URI
        if (path.startsWith("content://")) {
            return fileWriteSaf(path, content, append)
        }

        // Direct File path
        val file = resolveFile(path)

        return try {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            val action = if (append) "Appended" else "Wrote"
            ToolResult("file_write", "$action ${content.length} chars to ${file.absolutePath}")
        } catch (e: IOException) {
            ToolResult("file_write", "Write error: ${e.message}", isError = true)
        }
    }

    /**
     * Write to a SAF `content://` URI via [android.content.ContentResolver].
     * The "append" flag is honoured by opening the stream in append mode when possible.
     */
    private fun fileWriteSaf(uriString: String, content: String, append: Boolean): ToolResult {
        return try {
            val uri  = Uri.parse(uriString)
            val doc  = DocumentFile.fromSingleUri(context, uri)

            if (doc != null && !doc.canWrite()) {
                return ToolResult("file_write", "SAF permission denied (write) for URI: $uriString", isError = true)
            }

            // "wa" = write-append; "w" = write-truncate
            val mode = if (append) "wa" else "w"
            context.contentResolver.openOutputStream(uri, mode)?.use { stream ->
                stream.bufferedWriter().use { it.write(content) }
                val action = if (append) "Appended" else "Wrote"
                ToolResult("file_write", "$action ${content.length} chars to SAF URI: $uriString")
            } ?: ToolResult("file_write", "Could not open output stream for URI: $uriString", isError = true)
        } catch (e: Exception) {
            ToolResult("file_write", "SAF write error: ${e.message}", isError = true)
        }
    }

    /**
     * memory_query — searches Tier 2-4 via [MemoryManager].
     *
     * Accepts arguments:
     *   query     (required) Natural-language query; may include date expressions
     *             like "yesterday", "last week", "last Monday".
     *   from_date (optional) ISO-8601 start date override, e.g. "2024-03-01".
     *   to_date   (optional) ISO-8601 end date override.
     *
     * Falls back gracefully if [memoryManager] was not injected.
     */
    private suspend fun memoryQuery(call: org.json.JSONObject): ToolResult {
        val query    = call.optString("query", "").trim()
        val fromDate = call.optString("from_date", "").takeIf { it.isNotBlank() }
        val toDate   = call.optString("to_date",   "").takeIf { it.isNotBlank() }

        if (query.isBlank()) {
            return ToolResult("memory_query", "Error: 'query' cannot be empty.", isError = true)
        }
        val mm = memoryManager ?: return ToolResult(
            "memory_query",
            "Memory system not available in this session.",
        )
        return try {
            val result = mm.queryMemory(query = query, fromDate = fromDate, toDate = toDate)
            ToolResult("memory_query", result)
        } catch (e: Exception) {
            Log.e(TAG, "memory_query error: ${e.message}", e)
            ToolResult("memory_query", "Memory query error: ${e.message}", isError = true)
        }
    }

    /**
     * reply_to_request — terminal tool.
     *
     * Sets [replyMessage] and returns a result. The [ReActLoopManager] checks
     * [replyMessage] after each tool execution and exits the loop when it is set.
     */
    private fun replyToRequest(message: String): ToolResult {
        replyMessage = message.ifBlank { "(empty reply)" }
        Log.i(TAG, "reply_to_request → loop will exit")
        return ToolResult(REPLY_TO_REQUEST, replyMessage!!)
    }

    // ── Host-only tools (Milestone 4) ────────────────────────────────────────

    /**
     * bash_execute — run a shell command on the connected Mac/PC offload host.
     *
     * Requires [offloadClient] to be non-null and connected.
     * The offload server executes the command server-side and streams its
     * stdout/stderr back as tokens.
     *
     * This tool is intentionally NOT available when running on-device only —
     * a phone should never execute arbitrary shell commands on itself via AI.
     */
    private suspend fun bashExecute(command: String): ToolResult {
        val client = offloadClient
        if (client == null || !client.isConnected) {
            return ToolResult(
                "bash_execute",
                "bash_execute is only available when connected to a PC/Mac offload server.",
                isError = true,
            )
        }
        if (command.isBlank()) {
            return ToolResult("bash_execute", "Error: 'command' parameter cannot be empty.", isError = true)
        }

        return try {
            // Wrap the command in a bash_exec frame — the offload server protocol
            // is extended to support type="bash" for this purpose.
            // We reuse streamGenerate with a sentinel prompt that the server
            // recognises as a shell-execution request.
            val wrappedPrompt = "__BASH__:${command}"
            val tokens = client.streamGenerate(
                prompt     = wrappedPrompt,
                maxTokens  = 2048,
                temperature = 0.0f,
            ).toList()
            ToolResult("bash_execute", tokens.joinToString("").trim().ifBlank { "(no output)" })
        } catch (e: OffloadException) {
            ToolResult("bash_execute", "Offload error: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult("bash_execute", "bash_execute error: ${e.message}", isError = true)
        }
    }

    /**
     * terminal_read — read the contents of a file path on the host machine.
     *
     * Analogous to [fileRead] but executed remotely on the Mac/PC.
     * Requires [offloadClient] to be connected.
     */
    private suspend fun terminalRead(path: String): ToolResult {
        val client = offloadClient
        if (client == null || !client.isConnected) {
            return ToolResult(
                "terminal_read",
                "terminal_read is only available when connected to a PC/Mac offload server.",
                isError = true,
            )
        }
        if (path.isBlank()) {
            return ToolResult("terminal_read", "Error: 'path' parameter cannot be empty.", isError = true)
        }

        return try {
            val wrappedPrompt = "__READ__:${path}"
            val tokens = client.streamGenerate(
                prompt     = wrappedPrompt,
                maxTokens  = 4096,
                temperature = 0.0f,
            ).toList()
            ToolResult("terminal_read", tokens.joinToString("").trim().ifBlank { "(empty file)" })
        } catch (e: OffloadException) {
            ToolResult("terminal_read", "Offload error: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult("terminal_read", "terminal_read error: ${e.message}", isError = true)
        }
    }

    /**
     * finder_open — reveal a path in macOS Finder / Windows Explorer on the host.
     *
     * Requires [offloadClient] to be connected.
     * Returns confirmation that the Finder window was opened.
     */
    private suspend fun finderOpen(path: String): ToolResult {
        val client = offloadClient
        if (client == null || !client.isConnected) {
            return ToolResult(
                "finder_open",
                "finder_open is only available when connected to a PC/Mac offload server.",
                isError = true,
            )
        }
        if (path.isBlank()) {
            return ToolResult("finder_open", "Error: 'path' parameter cannot be empty.", isError = true)
        }

        return try {
            val wrappedPrompt = "__FINDER__:${path}"
            val tokens = client.streamGenerate(
                prompt     = wrappedPrompt,
                maxTokens  = 128,
                temperature = 0.0f,
            ).toList()
            ToolResult("finder_open", tokens.joinToString("").trim().ifBlank { "Finder window opened for: $path" })
        } catch (e: OffloadException) {
            ToolResult("finder_open", "Offload error: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult("finder_open", "finder_open error: ${e.message}", isError = true)
        }
    }

    // ── describe_image (vision tool — requires isMultimodal + live glasses frame) ──

    /**
     * describe_image
     *
     * Returns a vision-encoded representation of the current glasses camera frame
     * (or, if [imagePath] is non-empty, a file on disk) ready for Qwen 3.5's
     * native early-fusion multimodal encoder.
     *
     * The returned [ToolResult.result] contains the encoded frame string:
     *   `<img>data:image/jpeg;base64,...</img>`
     * which the ReAct loop injects directly into the next prompt turn.  Qwen 3.5
     * then processes the image tokens natively without any external projector.
     *
     * When [imagePath] is blank, the latest frame from [GlassesServiceState.currentFrame]
     * is used (live POV from the glasses).  When a path is provided, that JPEG file
     * is read from disk instead.
     */
    private fun describeImage(imagePath: String): ToolResult {
        // Guard: refuse to produce image tokens when the model is text-only.
        // Sending vision tokens to a non-multimodal model produces garbage output.
        if (!isMultimodal) {
            return ToolResult(
                toolName = "describe_image",
                result   = "Vision not available: the loaded model does not support image tokens. " +
                    "Load a vision-language model (e.g. Qwen2-VL, LLaVA) and enable Glasses Live.",
                isError  = true,
            )
        }

        if (imagePath.isNotBlank()) {
            // File path mode — read from disk
            val file = resolveFile(imagePath)
            if (!file.exists()) {
                return ToolResult("describe_image", "File not found: ${file.absolutePath}", isError = true)
            }
            val jpeg = try { file.readBytes() } catch (e: Exception) {
                return ToolResult("describe_image", "Cannot read image: ${e.message}", isError = true)
            }
            val frame = com.ernos.mobile.glasses.GlassesFrame(jpeg = jpeg)
            val encoded = VisionEncoder.encodeFrame(frame)
            return ToolResult("describe_image",
                "Image loaded from ${file.absolutePath}. Visual content:\n$encoded")
        }

        // Live glasses frame mode
        val frame = GlassesServiceState.currentFrame.value
            ?: return ToolResult(
                toolName = "describe_image",
                result   = "No live camera frame available. " +
                    "Enable 'Glasses Live' in the app or provide an image_path.",
                isError  = true,
            )

        val encoded = VisionEncoder.encodeFrame(frame)
        Log.i(TAG, "describe_image: encoding live frame ${frame.width}×${frame.height}")
        return ToolResult("describe_image",
            "Live glasses camera frame (${frame.width}×${frame.height}). Visual content:\n$encoded")
    }

    // ── Path resolution ───────────────────────────────────────────────────────

    /**
     * Resolve a file path: absolute paths are used as-is; relative paths are
     * anchored to the app's external files directory (no permission needed on API 29+).
     */
    private fun resolveFile(path: String): File =
        if (path.startsWith("/")) {
            File(path)
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, path)
        }
}
