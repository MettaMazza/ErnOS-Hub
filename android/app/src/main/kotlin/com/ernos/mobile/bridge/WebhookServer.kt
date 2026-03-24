package com.ernos.mobile.bridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebhookServer
 *
 * A minimal embedded HTTP/1.1 server that listens on a local TCP port and
 * dispatches inbound webhook POST requests to registered path handlers.
 *
 * This enables WhatsApp and Custom bridges to receive inbound messages without
 * any external dependency (no NanoHTTPD, no Ktor).
 *
 * Supports:
 *   GET  /path        — returns the result of [GetHandler] (used for Meta verification challenge)
 *   POST /path        — passes raw body + headers to [PostHandler] and writes the response
 *
 * Thread model:
 *   - [start] returns immediately; the server loop runs on [Dispatchers.IO].
 *   - Each accepted connection is handled in its own IO coroutine.
 *   - [stop] closes the ServerSocket and signals the accept loop to exit.
 *
 * Usage:
 * ```
 * val server = WebhookServer(port = 8080)
 * server.registerGet("/whatsapp") { params -> "challenge response or 403" }
 * server.registerPost("/whatsapp") { body, headers -> PostResult(200, "OK") }
 * server.start()
 * // … later …
 * server.stop()
 * ```
 */
class WebhookServer(private val port: Int) {

    companion object {
        private const val TAG = "WebhookServer"
    }

    data class PostResult(
        val statusCode: Int = 200,
        val body: String = "OK",
        val contentType: String = "text/plain",
    )

    fun interface GetHandler {
        fun handle(queryParams: Map<String, String>): String?
    }

    fun interface PostHandler {
        fun handle(body: String, headers: Map<String, String>): PostResult
    }

    private val getHandlers  = mutableMapOf<String, GetHandler>()
    private val postHandlers = mutableMapOf<String, PostHandler>()
    private val running      = AtomicBoolean(false)
    private val scope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    /** Register a GET handler for [path]. */
    fun registerGet(path: String, handler: GetHandler) {
        getHandlers[normalizePath(path)] = handler
    }

    /** Register a POST handler for [path]. */
    fun registerPost(path: String, handler: PostHandler) {
        postHandlers[normalizePath(path)] = handler
    }

    /**
     * Start the embedded HTTP server.
     * Non-blocking — the accept loop runs on [Dispatchers.IO].
     */
    fun start() {
        if (running.getAndSet(true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                Log.i(TAG, "WebhookServer listening on port $port")
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        scope.launch(Dispatchers.IO) { handleClient(client) }
                    } catch (e: SocketException) {
                        if (running.get()) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}", e)
            }
        }
    }

    /** Stop the server and release the TCP port. */
    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "WebhookServer stopped")
    }

    // ── Request handling ──────────────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)

                // Parse request line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.trim().split(" ")
                if (parts.size < 3) return
                val method  = parts[0].uppercase()
                val fullUri = parts[1]

                // Split path and query string
                val (path, rawQuery) = if ("?" in fullUri) {
                    fullUri.substringBefore("?") to fullUri.substringAfter("?")
                } else {
                    fullUri to ""
                }
                val queryParams = parseQuery(rawQuery)

                // Parse headers
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key   = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim()
                        headers[key] = value
                        if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                    }
                }

                // Read body for POST requests
                val body = if (method == "POST" && contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val normalPath = normalizePath(path)

                when (method) {
                    "GET"  -> handleGet(writer, normalPath, queryParams)
                    "POST" -> handlePost(writer, normalPath, body, headers)
                    else   -> writeResponse(writer, 405, "Method Not Allowed", "text/plain")
                }

                Log.d(TAG, "$method $path → handled")
            } catch (e: Exception) {
                Log.e(TAG, "handleClient error: ${e.message}")
            }
        }
    }

    private fun handleGet(
        writer: PrintWriter,
        path: String,
        queryParams: Map<String, String>,
    ) {
        val handler = getHandlers[path]
        if (handler == null) {
            writeResponse(writer, 404, "Not Found", "text/plain")
            return
        }
        val result = handler.handle(queryParams)
        if (result == null) {
            writeResponse(writer, 403, "Forbidden", "text/plain")
        } else {
            writeResponse(writer, 200, result, "text/plain")
        }
    }

    private fun handlePost(
        writer: PrintWriter,
        path: String,
        body: String,
        headers: Map<String, String>,
    ) {
        val handler = postHandlers[path]
        if (handler == null) {
            writeResponse(writer, 404, "Not Found", "text/plain")
            return
        }
        val result = handler.handle(body, headers)
        writeResponse(writer, result.statusCode, result.body, result.contentType)
    }

    private fun writeResponse(
        writer: PrintWriter,
        statusCode: Int,
        body: String,
        contentType: String,
    ) {
        val statusText = when (statusCode) {
            200  -> "OK"
            204  -> "No Content"
            400  -> "Bad Request"
            403  -> "Forbidden"
            404  -> "Not Found"
            405  -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 $statusCode $statusText\r\n")
        writer.print("Content-Type: $contentType; charset=utf-8\r\n")
        writer.print("Content-Length: ${bodyBytes.size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun normalizePath(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return if (p.endsWith("/") && p.length > 1) p.dropLast(1) else p
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("&").mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) null
            else pair.substring(0, eq).urlDecode() to pair.substring(eq + 1).urlDecode()
        }.toMap()
    }

    private fun String.urlDecode(): String = try {
        java.net.URLDecoder.decode(this, "UTF-8")
    } catch (_: Exception) { this }
}
