package com.ernos.mobile.bridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TelegramBridge
 *
 * Connects to the Telegram Bot API via long-polling (getUpdates with a 30-second
 * timeout).  Parses text messages and commands, normalises them into
 * [UnifiedMessage], and sends replies via sendMessage.
 *
 * Telegram Bot API documentation:
 *   https://core.telegram.org/bots/api
 */
class TelegramBridge(
    private val config: BridgeConfig.Telegram,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)  // slightly longer than polling timeout
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) : Bridge {

    override val name = "Telegram"

    companion object {
        private const val TAG                = "TelegramBridge"
        private const val API_BASE           = "https://api.telegram.org/bot"
        private const val POLL_TIMEOUT_SEC   = 30
        private const val RETRY_DELAY_MS     = 3_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val offset  = AtomicLong(0L)

    /** Derived once from config. */
    private val apiBase: String get() = "$API_BASE${config.botToken}"

    // ── Bridge interface ─────────────────────────────────────────────────────

    override suspend fun start(onMessage: (UnifiedMessage) -> Unit) {
        if (!config.isConfigured) {
            Log.w(TAG, "Telegram bridge not configured — skipping")
            return
        }
        running.set(true)
        Log.i(TAG, "Telegram bridge started (long-polling)")
        scope.launch { pollLoop(onMessage) }
    }

    override suspend fun sendReply(message: UnifiedMessage, reply: String) {
        if (!config.isConfigured) return
        val payload = JSONObject().apply {
            put("chat_id", message.channelId)
            put("text", reply)
            put("parse_mode", "Markdown")
        }
        val request = Request.Builder()
            .url("$apiBase/sendMessage")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "sendMessage HTTP ${resp.code}: ${resp.body?.string()}")
                } else {
                    Log.i(TAG, "Telegram reply sent to chat ${message.channelId}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendReply error: ${e.message}", e)
        }
    }

    override fun stop() {
        running.set(false)
        Log.i(TAG, "Telegram bridge stopped")
    }

    // ── Long-polling loop ─────────────────────────────────────────────────────

    private suspend fun pollLoop(onMessage: (UnifiedMessage) -> Unit) {
        while (coroutineContext.isActive && running.get()) {
            try {
                val updates = getUpdates()
                for (i in 0 until updates.length()) {
                    val update = updates.optJSONObject(i) ?: continue
                    val updateId = update.optLong("update_id", -1L)
                    if (updateId >= 0) offset.set(updateId + 1)

                    val msg = parseUpdate(update) ?: continue
                    onMessage(msg)
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "pollLoop error: ${e.message} — retrying in ${RETRY_DELAY_MS}ms")
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    /**
     * Call getUpdates with long-polling.
     * Returns the JSONArray of updates (may be empty if timeout expired with no messages).
     */
    private fun getUpdates(): org.json.JSONArray {
        val url = buildString {
            append("$apiBase/getUpdates?timeout=$POLL_TIMEOUT_SEC")
            val off = offset.get()
            if (off > 0) append("&offset=$off")
            append("&allowed_updates=[\"message\"]")
        }
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: return org.json.JSONArray()
            val json = JSONObject(body)
            return json.optJSONArray("result") ?: org.json.JSONArray()
        }
    }

    /**
     * Parse a single Telegram update object into a [UnifiedMessage].
     * Returns null if the update has no processable text message.
     */
    private fun parseUpdate(update: JSONObject): UnifiedMessage? {
        val message = update.optJSONObject("message")
            ?: update.optJSONObject("edited_message")
            ?: return null

        val text = message.optString("text", "").trim()
        if (text.isBlank()) return null

        val chat     = message.optJSONObject("chat") ?: return null
        val chatId   = chat.optString("id", "")
        val from     = message.optJSONObject("from")
        val userId   = from?.optString("id", "") ?: "unknown"
        val userName = from?.optString("username", "")
            ?: from?.optString("first_name", "unknown")
            ?: "unknown"
        val msgId    = message.optString("message_id", "")

        // Strip bot commands prefix (e.g., /start@ErnOS_bot → /start)
        val cleanText = if (text.startsWith("/")) {
            text.substringBefore("@").trim()
        } else {
            text
        }

        return UnifiedMessage(
            source            = BridgeSource.TELEGRAM,
            channelId         = chatId,
            senderId          = userId,
            senderName        = userName,
            text              = cleanText,
            platformMessageId = msgId,
            timestampMs       = message.optLong("date", 0L) * 1_000L,
        )
    }
}
