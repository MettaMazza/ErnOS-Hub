package com.ernos.mobile.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * SystemPrompter
 *
 * Constructs the full ErnOS system prompt by concatenating four sections:
 *  1. IDENTITY  — kernel protocols (Zero Assumption, Continuity Recovery, Clarification)
 *  2. HUD       — live [SYSTEM AWARENESS] block (battery, network, timestamp)
 *  3. TOOLS     — JSON function definitions filtered by [ModelConfig.isMultimodal]
 *  4. MEMORY    — placeholder filled by Milestone 3; empty string if not yet wired
 *
 * Milestone 3 will call [buildSystemPrompt] with a non-empty [memoryContext] string.
 */
class SystemPrompter(private val context: Context) {

    companion object {
        private const val TAG = "SystemPrompter"
    }

    // ── Model configuration ───────────────────────────────────────────────────

    /**
     * Per-model metadata that influences system prompt construction.
     *
     * @param isMultimodal  If true, vision tools are included in the schema.
     *                      Set to false for text-only GGUF models (default).
     * @param modelName     Human-readable model identifier shown in the HUD.
     * @param contextWindow The model's token context window size.
     */
    data class ModelConfig(
        val isMultimodal: Boolean = false,
        val modelName: String = "Qwen2.5",
        val contextWindow: Int = 4096,
        /** When true, host-only tools (bash_execute, terminal_read, finder_open) are added to the schema. */
        val isOffloaded: Boolean = false,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build the full system prompt string.
     *
     * @param config               Model capabilities — controls tool filtering.
     * @param memoryContext        Tier 1-3 context injected by Milestone 3.
     *                             Pass an empty string (default) until then.
     * @param additionalDirectives Optional extra directives appended at the end.
     */
    fun buildSystemPrompt(
        config: ModelConfig = ModelConfig(),
        memoryContext: String = "",
        additionalDirectives: String = "",
    ): String = buildString {
        append(identitySection())
        append("\n\n")
        append(hudSection(config))
        append("\n\n")
        append(toolSchemaSection(config.isMultimodal, config.isOffloaded))
        if (memoryContext.isNotEmpty()) {
            append("\n\n")
            append(memorySection(memoryContext))
        }
        if (additionalDirectives.isNotEmpty()) {
            append("\n\n")
            append(additionalDirectives)
        }
    }.trim()

    // ── Section builders ──────────────────────────────────────────────────────

    private fun identitySection(): String = """
        [IDENTITY]
        You are ErnOS, an on-device AI assistant. All computation runs locally.
        You have tools for web search, file I/O, and memory queries.

        [RULES]
        1. Use tools when needed. Never simulate tool outputs.
        2. Call reply_to_request with your final answer when done.
        3. Be concise and honest about uncertainty.
        4. If context is missing, ask the user.
    """.trimIndent()

    private fun hudSection(config: ModelConfig): String {
        val battery = getBatteryLevel()
        val network = getConnectivityStatus()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val batteryStr = if (battery >= 0) "$battery%" else "unknown"

        return """
            [SYSTEM AWARENESS]
            Timestamp      : $timestamp
            Battery        : $batteryStr
            Network        : $network
            Model          : ${config.modelName}
            Context window : ${config.contextWindow} tokens
            Multimodal     : ${config.isMultimodal}
            Platform       : Android on-device inference
        """.trimIndent()
    }

    private fun toolSchemaSection(isMultimodal: Boolean, isOffloaded: Boolean = false): String {
        val tools = buildToolList(isMultimodal, isOffloaded)
        val schemaJson = JSONArray(tools).toString(2)

        return """
            [AVAILABLE TOOLS]
            Emit tool calls using this exact format (one call per block):
            <tool_call>
            {"name": "tool_name", "arguments": {"param": "value"}}
            </tool_call>

            The result will be returned in a <tool_response> block. You may then call another
            tool or call reply_to_request to deliver your final answer.

            Tool definitions:
            $schemaJson
        """.trimIndent()
    }

    private fun memorySection(context: String): String = """
        [MEMORY CONTEXT]
        The following information was retrieved from your long-term memory:

        $context
    """.trimIndent()

    // ── Tool schema factory ───────────────────────────────────────────────────

    private fun buildToolList(isMultimodal: Boolean, isOffloaded: Boolean = false): List<JSONObject> =
        mutableListOf<JSONObject>().apply {
            add(
                tool(
                    name = "web_search",
                    description = "Search the web for current, real-time information not in training data.",
                    params = mapOf(
                        "query" to param("string", "The search query to run."),
                    ),
                    required = listOf("query"),
                )
            )
            add(
                tool(
                    name = "file_read",
                    description = "Read the text content of a file from device storage.",
                    params = mapOf(
                        "path" to param("string", "Absolute path or path relative to app storage."),
                    ),
                    required = listOf("path"),
                )
            )
            add(
                tool(
                    name = "file_write",
                    description = "Write or append text to a file on device storage.",
                    params = mapOf(
                        "path"    to param("string", "Absolute or relative file path to write."),
                        "content" to param("string", "Text content to write."),
                        "append"  to param("boolean", "If true, append to existing content; if false, overwrite."),
                    ),
                    required = listOf("path", "content"),
                )
            )
            add(
                tool(
                    name = "memory_query",
                    description = "Query long-term memory for relevant past context.",
                    params = mapOf(
                        "query" to param("string", "What to search for in memory."),
                    ),
                    required = listOf("query"),
                )
            )
            if (isMultimodal) {
                add(
                    tool(
                        name = "describe_image",
                        description = "Analyse and describe the content of an image file.",
                        params = mapOf(
                            "image_path" to param("string", "Path to the image file to analyse."),
                        ),
                        required = listOf("image_path"),
                    )
                )
            }
            if (isOffloaded) {
                add(
                    tool(
                        name = "bash_execute",
                        description = "Execute a shell command on the connected Mac/PC host and return its output. " +
                            "Only available when PC offload is active.",
                        params = mapOf(
                            "command" to param("string", "The shell command to run on the host machine."),
                        ),
                        required = listOf("command"),
                    )
                )
                add(
                    tool(
                        name = "terminal_read",
                        description = "Read the contents of a file or list a directory on the connected Mac/PC host. " +
                            "Only available when PC offload is active.",
                        params = mapOf(
                            "path" to param("string", "Absolute or home-relative path on the host, e.g. ~/Documents/notes.txt"),
                        ),
                        required = listOf("path"),
                    )
                )
                add(
                    tool(
                        name = "finder_open",
                        description = "Reveal a file or folder in macOS Finder or Windows Explorer on the host. " +
                            "Only available when PC offload is active.",
                        params = mapOf(
                            "path" to param("string", "Absolute or home-relative path to reveal, e.g. ~/Desktop/report.pdf"),
                        ),
                        required = listOf("path"),
                    )
                )
            }
            add(
                tool(
                    name = "reply_to_request",
                    description = "Send your final answer to the user. MUST be called when done.",
                    params = mapOf(
                        "message" to param("string", "Your complete response."),
                    ),
                    required = listOf("message"),
                )
            )
        }

    private fun tool(
        name: String,
        description: String,
        params: Map<String, JSONObject>,
        required: List<String>,
    ): JSONObject {
        val properties = JSONObject()
        params.forEach { (k, v) -> properties.put(k, v) }

        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    put("required", JSONArray(required))
                })
            })
        }
    }

    private fun param(type: String, description: String): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("description", description)
        }

    // ── Device queries ────────────────────────────────────────────────────────

    private fun getBatteryLevel(): Int {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter) ?: return -1
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (e: Exception) {
            Log.w(TAG, "getBatteryLevel failed: ${e.message}")
            -1
        }
    }

    private fun getConnectivityStatus(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(network) ?: return "Offline"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)      -> "VPN"
                else                                                       -> "Connected"
            }
        } catch (e: Exception) {
            Log.w(TAG, "getConnectivityStatus failed: ${e.message}")
            "Unknown"
        }
    }
}
