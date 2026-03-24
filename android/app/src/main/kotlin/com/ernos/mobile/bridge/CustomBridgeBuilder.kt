package com.ernos.mobile.bridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CustomBridgeBuilder
 *
 * Allows the user to configure an arbitrary HTTP webhook as a message source
 * and an arbitrary URL as the reply target.
 *
 * Inbound:  External system POSTs JSON to the embedded webhook server.
 *           The payload must contain at minimum:
 *             { "text": "...", "sender_id": "...", "channel_id": "..." }
 *           Optional fields: sender_name, image_url, message_id, timestamp_ms.
 *
 * Outbound: [sendReply] POSTs the AI reply to [BridgeConfig.Custom.responseUrl]
 *           as JSON: { "channel_id": "...", "reply": "..." }.
 *
 *           An optional shared secret is sent in the X-ErnOS-Secret header so
 *           the receiving server can validate the origin.
 */
class CustomBridgeBuilder(
    private val config: BridgeConfig.Custom,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) : Bridge {

    override val name = "Custom"

    companion object {
        private const val TAG = "CustomBridgeBuilder"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var onMessage: ((UnifiedMessage) -> Unit)? = null

    // ── Bridge interface ─────────────────────────────────────────────────────

    override suspend fun start(onMessage: (UnifiedMessage) -> Unit) {
        if (!config.isConfigured) {
            Log.w(TAG, "Custom bridge not configured — skipping")
            return
        }
        this.onMessage = onMessage
        running.set(true)
        Log.i(TAG, "Custom bridge ready — inboundPath=${config.inboundPath} responseUrl=${config.responseUrl}")
    }

    override suspend fun sendReply(message: UnifiedMessage, reply: String) {
        if (!config.isConfigured || config.responseUrl.isBlank()) return

        val payload = JSONObject().apply {
            put("channel_id", message.channelId)
            put("sender_id",  message.senderId)
            put("reply",      reply)
            put("timestamp_ms", System.currentTimeMillis())
        }

        val reqBuilder = Request.Builder()
            .url(config.responseUrl)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (config.sharedSecret.isNotBlank()) {
            reqBuilder.addHeader("X-ErnOS-Secret", config.sharedSecret)
        }

        try {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "sendReply HTTP ${resp.code}: ${resp.body?.string()}")
                } else {
                    Log.i(TAG, "Custom reply sent to ${config.responseUrl}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendReply error: ${e.message}", e)
        }
    }

    override fun stop() {
        running.set(false)
        Log.i(TAG, "Custom bridge stopped")
    }

    // ── Webhook processing ────────────────────────────────────────────────────

    /**
     * Handle a raw webhook payload delivered to [BridgeConfig.Custom.inboundPath].
     *
     * Expected JSON schema:
     * ```
     * {
     *   "text":         "Hello ErnOS",            // required
     *   "sender_id":    "user_123",               // required
     *   "channel_id":   "channel_abc",            // required
     *   "sender_name":  "Alice",                  // optional
     *   "image_url":    "https://...",            // optional
     *   "message_id":   "msg_xyz",               // optional
     *   "timestamp_ms": 1711000000000            // optional
     * }
     * ```
     *
     * @param rawBody     Raw UTF-8 request body.
     * @param secretHeader Value of the X-ErnOS-Secret header (may be null).
     * @return true if the payload was accepted and dispatched.
     */
    fun handleWebhookPayload(rawBody: String, secretHeader: String?): Boolean {
        if (!running.get()) return false

        // Validate shared secret when configured
        if (config.sharedSecret.isNotBlank() && secretHeader != config.sharedSecret) {
            Log.w(TAG, "Custom webhook: invalid or missing X-ErnOS-Secret — rejected")
            return false
        }

        return try {
            val obj = JSONObject(rawBody)

            val text      = obj.optString("text", "").trim()
            val senderId  = obj.optString("sender_id", "unknown").trim()
            val channelId = obj.optString("channel_id", "unknown").trim()

            if (text.isBlank()) return false

            val msg = UnifiedMessage(
                source            = BridgeSource.CUSTOM,
                channelId         = channelId,
                senderId          = senderId,
                senderName        = obj.optString("sender_name", senderId),
                text              = text,
                imageUrl          = obj.optString("image_url", "").ifBlank { null },
                platformMessageId = obj.optString("message_id", "").ifBlank { null },
                timestampMs       = obj.optLong("timestamp_ms", System.currentTimeMillis()),
            )

            onMessage?.invoke(msg)
            Log.i(TAG, "Custom webhook message from $senderId: ${text.take(80)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "handleWebhookPayload parse error: ${e.message}", e)
            false
        }
    }
}
