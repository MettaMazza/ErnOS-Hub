package com.ernos.mobile.offload

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * OffloadClient
 *
 * WebSocket client that connects to the companion offload server running on a
 * Mac/PC.  When connected, inference workloads can be sent to the host machine
 * (which has more CPU/GPU RAM) and streamed token-by-token back to the phone.
 *
 * Authentication (optional but strongly recommended):
 *   When [secret] is non-blank, the client sends an auth frame immediately
 *   after the WebSocket handshake completes:
 *     `{"type": "auth", "token": "<secret>"}`
 *   The server must respond with `{"type": "auth_ok"}` or the connection
 *   is treated as failed.  Set the same token via `--secret` on the server.
 *
 * Protocol (JSON frames):
 *
 *   Client → Server (auth — first frame when secret is set):
 *   ```json
 *   { "type": "auth", "token": "..." }
 *   ```
 *   Server → Client:
 *   ```json
 *   { "type": "auth_ok" }
 *   ```
 *
 *   Client → Server (generate request):
 *   ```json
 *   { "type": "generate", "prompt": "...", "max_tokens": 512, "temperature": 0.7 }
 *   ```
 *
 *   Server → Client (token stream):
 *   ```json
 *   { "type": "token", "text": "..." }
 *   ```
 *   ...
 *   ```json
 *   { "type": "done" }
 *   ```
 *   or on error:
 *   ```json
 *   { "type": "error", "message": "..." }
 *   ```
 *
 *   Client → Server (ping):
 *   ```json
 *   { "type": "ping" }
 *   ```
 *
 *   Server → Client (pong):
 *   ```json
 *   { "type": "pong" }
 *   ```
 *
 * Connection URL: ws://<host>:<port>/offload  (default port 8765)
 */
class OffloadClient(
    /** WebSocket server address.  E.g. "192.168.1.100" */
    private val host: String,
    private val port: Int = 8765,
    /**
     * Shared secret for authentication.  Must match the `--secret` flag on the
     * offload server.  Leave blank to connect without authentication (insecure
     * on shared networks).
     */
    private val secret: String = "",
) {

    companion object {
        private const val TAG              = "OffloadClient"
        private const val CONNECT_TIMEOUT  = 5_000L
        private const val PING_TIMEOUT_MS  = 3_000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connected   = AtomicBoolean(false)
    private val ws          = AtomicReference<WebSocket?>(null)

    /** A channel used to deliver incoming frames from the WebSocket listener. */
    private val frameChannel = Channel<JSONObject>(capacity = Channel.UNLIMITED)

    // ── Public API ────────────────────────────────────────────────────────────

    /** True while the WebSocket handshake has completed and no failure has occurred. */
    val isConnected: Boolean get() = connected.get()

    /**
     * Establish the WebSocket connection to the offload server.
     *
     * If [secret] is non-blank, sends an auth frame immediately after the
     * handshake and waits for an `auth_ok` response.  Returns false (and
     * disconnects) if the server rejects the token.
     *
     * @return true if the connection (and optional auth) succeeded within
     *         [CONNECT_TIMEOUT] ms.
     */
    suspend fun connect(): Boolean {
        if (connected.get()) return true
        val url = "ws://$host:$port/offload"
        val request = Request.Builder().url(url).build()
        val listener = OffloadListener()
        val socket = httpClient.newWebSocket(request, listener)

        // Wait for either OPEN or FAILURE
        val handshakeOk = withTimeoutOrNull(CONNECT_TIMEOUT) {
            while (!listener.resolved) {
                kotlinx.coroutines.delay(50)
            }
            listener.didOpen
        } ?: false

        if (!handshakeOk) {
            socket.cancel()
            Log.w(TAG, "Failed to connect to offload server at $url")
            return false
        }

        // ── Optional auth handshake ────────────────────────────────────────────
        if (secret.isNotBlank()) {
            val authFrame = JSONObject().apply {
                put("type",  "auth")
                put("token", secret)
            }
            socket.send(authFrame.toString())
            Log.d(TAG, "Auth frame sent — waiting for auth_ok")

            var authOk = false
            val authResult = withTimeoutOrNull(CONNECT_TIMEOUT) {
                var result = false
                loop@ while (true) {
                    val frame = frameChannel.receive()
                    when (frame.optString("type", "")) {
                        "auth_ok" -> { result = true;  break@loop }
                        "error"   -> {
                            Log.e(TAG, "Auth rejected: ${frame.optString("message")}")
                            break@loop
                        }
                    }
                }
                result
            }
            authOk = authResult == true

            if (!authOk) {
                socket.cancel()
                Log.w(TAG, "Offload server rejected auth token")
                return false
            }
            Log.i(TAG, "Auth accepted by offload server")
        }

        ws.set(socket)
        connected.set(true)
        Log.i(TAG, "Connected to offload server at $url (auth=${secret.isNotBlank()})")
        return true
    }

    /**
     * Disconnect from the offload server and release resources.
     */
    fun disconnect() {
        ws.get()?.close(1000, "ErnOS disconnect")
        ws.set(null)
        connected.set(false)
        Log.i(TAG, "Disconnected from offload server")
    }

    /**
     * Stream token generation from the offload server.
     *
     * Sends the [prompt] and [params] to the server, then emits each token
     * string as it arrives.  The flow completes when the server sends a "done"
     * frame or an "error" frame is received (in which case the flow throws
     * an [OffloadException]).
     *
     * @throws OffloadException if the client is not connected or the server
     *         returns an error frame.
     */
    fun streamGenerate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        presencePenalty: Float = 0.0f,
    ): Flow<String> = flow {
        if (!connected.get()) {
            throw OffloadException("OffloadClient is not connected")
        }

        val payload = JSONObject().apply {
            put("type",             "generate")
            put("prompt",           prompt)
            put("max_tokens",       maxTokens)
            put("temperature",      temperature)
            put("top_p",            topP)
            put("presence_penalty", presencePenalty)
        }

        // Clear any stale frames before sending
        while (!frameChannel.isEmpty) {
            frameChannel.tryReceive()
        }

        val socket = ws.get() ?: throw OffloadException("WebSocket is null")
        socket.send(payload.toString())
        Log.d(TAG, "Sent generate request (${prompt.length} chars)")

        // Collect frames until done or error
        while (true) {
            val frame = frameChannel.receive()
            when (val type = frame.optString("type", "")) {
                "token" -> {
                    val text = frame.optString("text", "")
                    if (text.isNotEmpty()) emit(text)
                }
                "done" -> {
                    Log.d(TAG, "Offload generation complete")
                    break
                }
                "error" -> {
                    val msg = frame.optString("message", "Unknown offload error")
                    Log.e(TAG, "Offload error: $msg")
                    throw OffloadException(msg)
                }
                else -> {
                    Log.v(TAG, "Unknown frame type ignored: $type")
                }
            }
        }
    }

    /**
     * Send a ping and wait up to [PING_TIMEOUT_MS] for a pong.
     *
     * @return true if the pong was received in time.
     */
    suspend fun ping(): Boolean {
        val socket = ws.get() ?: return false
        socket.send(JSONObject().apply { put("type", "ping") }.toString())
        val pong = withTimeoutOrNull(PING_TIMEOUT_MS) {
            var result = false
            while (true) {
                val frame = frameChannel.receive()
                if (frame.optString("type") == "pong") {
                    result = true
                    break
                }
            }
            result
        }
        return pong == true
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private inner class OffloadListener : WebSocketListener() {
        @Volatile var resolved = false
        @Volatile var didOpen  = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            didOpen  = true
            resolved = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val frame = JSONObject(text)
                frameChannel.trySend(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse server frame: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}")
            connected.set(false)
            resolved = true
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected.set(false)
            Log.i(TAG, "WebSocket closed: $code $reason")
        }
    }
}

/** Thrown when the offload server returns an error or the client is disconnected. */
class OffloadException(message: String) : Exception(message)
