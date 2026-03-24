package com.ernos.mobile.bridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * DiscordBridge
 *
 * Connects to the Discord Gateway API via WebSocket, handles heartbeating,
 * sequence tracking, and reconnection.  Dispatches [UnifiedMessage] for every
 * MESSAGE_CREATE event that mentions the bot or is a DM.
 *
 * Discord Gateway documentation:
 *   https://discord.com/developers/docs/topics/gateway
 *
 * Opcode reference:
 *   0  = Dispatch            (server → client, normal events)
 *   1  = Heartbeat           (client request)
 *   2  = Identify            (client auth)
 *   7  = Reconnect           (server requests reconnect)
 *   9  = Invalid session     (reauthenticate)
 *   10 = Hello               (server sends heartbeat interval)
 *   11 = Heartbeat ACK       (server acknowledges heartbeat)
 */
class DiscordBridge(
    private val config: BridgeConfig.Discord,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : Bridge {

    override val name = "Discord"

    companion object {
        private const val TAG               = "DiscordBridge"
        private const val GATEWAY_URL       = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val API_BASE          = "https://discord.com/api/v10"
        private const val DISCORD_BOT_INTENTS = 33280  // GUILD_MESSAGES + MESSAGE_CONTENT + DIRECT_MESSAGES
        private const val RECONNECT_DELAY_MS = 5_000L
    }

    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running     = AtomicBoolean(false)
    private val sequence    = AtomicLong(-1L)
    private var heartbeatJob: Job? = null
    private var webSocket: WebSocket? = null
    private var onMessage: ((UnifiedMessage) -> Unit)? = null

    /** Our bot's user ID — populated from the READY event. */
    private var botUserId: String = ""

    // ── Bridge interface ─────────────────────────────────────────────────────

    override suspend fun start(onMessage: (UnifiedMessage) -> Unit) {
        if (!config.isConfigured) {
            Log.w(TAG, "Discord bridge not configured — skipping")
            return
        }
        this.onMessage = onMessage
        running.set(true)
        connect()
    }

    override suspend fun sendReply(message: UnifiedMessage, reply: String) {
        if (!config.isConfigured) return
        val channelId = message.channelId
        val mediaType = "application/json".toMediaType()
        val body = JSONObject().apply { put("content", reply) }.toString()
        val request = Request.Builder()
            .url("$API_BASE/channels/$channelId/messages")
            .addHeader("Authorization", "Bot ${config.botToken}")
            .post(body.toRequestBody(mediaType))
            .build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "sendReply HTTP ${resp.code}: ${resp.body?.string()}")
                } else {
                    Log.i(TAG, "Reply sent to Discord channel $channelId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendReply error: ${e.message}", e)
        }
    }

    override fun stop() {
        running.set(false)
        heartbeatJob?.cancel()
        webSocket?.close(1000, "ErnOS shutdown")
        webSocket = null
        Log.i(TAG, "Discord bridge stopped")
    }

    // ── Gateway connection ────────────────────────────────────────────────────

    private fun connect() {
        val request = Request.Builder().url(GATEWAY_URL).build()
        webSocket = httpClient.newWebSocket(request, GatewayListener())
        Log.i(TAG, "Discord: connecting to Gateway")
    }

    private inner class GatewayListener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "Discord Gateway connection opened")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                handlePayload(ws, JSONObject(text))
            } catch (e: Exception) {
                Log.e(TAG, "onMessage parse error: ${e.message}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Gateway failure: ${t.message}")
            if (running.get()) scheduleReconnect()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Gateway closed: $code $reason")
            if (running.get() && code != 1000) scheduleReconnect()
        }
    }

    private fun handlePayload(ws: WebSocket, payload: JSONObject) {
        val op  = payload.optInt("op", -1)
        val d   = payload.opt("d")
        val s   = payload.optLong("s", -1L)
        val t   = payload.optString("t", "")

        if (s >= 0) sequence.set(s)

        when (op) {
            10 -> {
                // Hello — start heartbeating and identify
                val intervalMs = (d as? JSONObject)?.optLong("heartbeat_interval", 41250L) ?: 41250L
                startHeartbeat(ws, intervalMs)
                identify(ws)
            }
            11 -> {
                // Heartbeat ACK — no action needed
                Log.v(TAG, "Heartbeat ACK received")
            }
            0  -> {
                // Dispatch
                when (t) {
                    "READY"          -> handleReady(d as? JSONObject)
                    "MESSAGE_CREATE" -> handleMessageCreate(d as? JSONObject)
                    else             -> { /* other events ignored */ }
                }
            }
            7  -> {
                // Reconnect requested by server
                Log.i(TAG, "Server requested reconnect")
                scheduleReconnect()
            }
            9  -> {
                // Invalid session — re-identify after a short delay
                Log.w(TAG, "Invalid session — re-identifying")
                scope.launch {
                    delay(RECONNECT_DELAY_MS)
                    identify(ws)
                }
            }
        }
    }

    private fun startHeartbeat(ws: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running.get()) {
                val seq = sequence.get()
                val payload = JSONObject().apply {
                    put("op", 1)
                    put("d", if (seq >= 0) seq else JSONObject.NULL)
                }
                ws.send(payload.toString())
                Log.v(TAG, "Heartbeat sent (seq=$seq)")
                delay(intervalMs)
            }
        }
    }

    private fun identify(ws: WebSocket) {
        val payload = JSONObject().apply {
            put("op", 2)
            put("d", JSONObject().apply {
                put("token", config.botToken)
                put("intents", DISCORD_BOT_INTENTS)
                put("properties", JSONObject().apply {
                    put("\$os", "android")
                    put("\$browser", "ErnOS")
                    put("\$device", "ErnOS")
                })
            })
        }
        ws.send(payload.toString())
        Log.i(TAG, "Discord: Identify sent")
    }

    private fun handleReady(d: JSONObject?) {
        botUserId = d?.optJSONObject("user")?.optString("id", "") ?: ""
        Log.i(TAG, "Discord: READY — botUserId=$botUserId")
    }

    private fun handleMessageCreate(d: JSONObject?) {
        if (d == null) return

        val authorId   = d.optJSONObject("author")?.optString("id", "") ?: ""
        val authorName = d.optJSONObject("author")?.optString("username", "unknown") ?: "unknown"

        // Ignore messages from ourselves
        if (authorId == botUserId) return

        val content   = d.optString("content", "")
        val channelId = d.optString("channel_id", "")
        val msgId     = d.optString("id", "")

        // Only respond when mentioned or in a DM channel
        val mentions = d.optJSONArray("mentions")
        val mentionedBot = (0 until (mentions?.length() ?: 0)).any {
            mentions?.optJSONObject(it)?.optString("id", "") == botUserId
        }
        val isDm = d.optInt("guild_id", -1) == -1  // guild_id absent → DM

        if (!mentionedBot && !isDm) return

        // Strip the bot mention from content
        val cleanContent = content.replace(Regex("<@!?$botUserId>"), "").trim()
        if (cleanContent.isBlank()) return

        val msg = UnifiedMessage(
            source            = BridgeSource.DISCORD,
            channelId         = channelId,
            senderId          = authorId,
            senderName        = authorName,
            text              = cleanContent,
            platformMessageId = msgId,
        )
        onMessage?.invoke(msg)
        Log.i(TAG, "Discord message from $authorName in $channelId: ${cleanContent.take(80)}")
    }

    private fun scheduleReconnect() {
        scope.launch {
            if (!running.get()) return@launch
            Log.i(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms…")
            delay(RECONNECT_DELAY_MS)
            if (running.get()) connect()
        }
    }
}
