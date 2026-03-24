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
 * WhatsAppBridge
 *
 * Implements the WhatsApp Cloud API.
 *
 * Inbound flow:
 *   [BridgeService] starts an embedded [WebhookServer] that listens for POST requests
 *   from Meta.  Each valid payload is passed to [handleWebhookPayload], which
 *   verifies the X-Hub-Signature-256 header (HMAC-SHA256 keyed on [BridgeConfig.WhatsApp.appSecret])
 *   and normalises the content into [UnifiedMessage] instances.
 *
 * Outbound flow:
 *   [sendReply] calls the WhatsApp Cloud API /messages endpoint.
 *
 * WhatsApp Cloud API documentation:
 *   https://developers.facebook.com/docs/whatsapp/cloud-api
 *
 * Webhook signature verification:
 *   X-Hub-Signature-256 = "sha256=" + hex( HMAC-SHA256( payload, appSecret ) )
 *   The app secret comes from the Meta Developer Console (NOT the verifyToken).
 *   verifyToken is only used to respond to Meta's GET verification challenge.
 */
class WhatsAppBridge(
    private val config: BridgeConfig.WhatsApp,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) : Bridge {

    override val name = "WhatsApp"

    companion object {
        private const val TAG      = "WhatsAppBridge"
        private const val API_BASE = "https://graph.facebook.com/v19.0"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var onMessage: ((UnifiedMessage) -> Unit)? = null

    // ── Bridge interface ─────────────────────────────────────────────────────

    override suspend fun start(onMessage: (UnifiedMessage) -> Unit) {
        if (!config.isConfigured) {
            Log.w(TAG, "WhatsApp bridge not configured — skipping")
            return
        }
        this.onMessage = onMessage
        running.set(true)
        Log.i(TAG, "WhatsApp bridge ready (awaiting webhook deliveries on port ${config.webhookPort})")
    }

    override suspend fun sendReply(message: UnifiedMessage, reply: String) {
        if (!config.isConfigured) return

        val payload = JSONObject().apply {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", message.channelId)
            put("type", "text")
            put("text", JSONObject().apply { put("body", reply) })
        }

        val request = Request.Builder()
            .url("$API_BASE/${config.phoneNumberId}/messages")
            .addHeader("Authorization", "Bearer ${config.accessToken}")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "sendReply HTTP ${resp.code}: ${resp.body?.string()}")
                } else {
                    Log.i(TAG, "WhatsApp reply sent to ${message.channelId}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendReply error: ${e.message}", e)
        }
    }

    override fun stop() {
        running.set(false)
        Log.i(TAG, "WhatsApp bridge stopped")
    }

    // ── Webhook processing ────────────────────────────────────────────────────

    /**
     * Handle a raw webhook POST payload received from Meta.
     *
     * Called by [BridgeService] (via [WebhookServer]) when the embedded HTTP server
     * receives a POST to the WhatsApp webhook path.
     *
     * @param rawBody         Raw UTF-8 request body string.
     * @param hubSignature    Value of the X-Hub-Signature-256 header (may be null).
     * @return true if the signature was valid and the payload was accepted.
     */
    fun handleWebhookPayload(rawBody: String, hubSignature: String?): Boolean {
        if (!running.get()) return false

        if (!verifySignature(rawBody, hubSignature)) {
            Log.w(TAG, "Webhook signature verification failed — payload rejected")
            return false
        }

        try {
            val root   = JSONObject(rawBody)
            val entry  = root.optJSONArray("entry") ?: return true
            for (i in 0 until entry.length()) {
                val entryObj = entry.optJSONObject(i) ?: continue
                val changes  = entryObj.optJSONArray("changes") ?: continue
                for (j in 0 until changes.length()) {
                    val change = changes.optJSONObject(j) ?: continue
                    if (change.optString("field") != "messages") continue
                    val value = change.optJSONObject("value") ?: continue
                    parseMessages(value)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleWebhookPayload parse error: ${e.message}", e)
        }
        return true
    }

    /**
     * Handle Meta's GET webhook verification challenge.
     *
     * Meta sends a GET request with hub.mode, hub.verify_token, and hub.challenge.
     * Return hub.challenge if the verify_token matches [BridgeConfig.WhatsApp.verifyToken],
     * null otherwise.
     *
     * NOTE: verifyToken is only used here for the registration handshake.
     *       HMAC signature verification uses [BridgeConfig.WhatsApp.appSecret].
     */
    fun handleVerificationChallenge(
        mode: String?,
        token: String?,
        challenge: String?,
    ): String? {
        return if (mode == "subscribe" && token == config.verifyToken) {
            Log.i(TAG, "WhatsApp webhook verification challenge accepted")
            challenge
        } else {
            Log.w(TAG, "WhatsApp webhook verification failed: mode=$mode token=$token")
            null
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun parseMessages(value: JSONObject) {
        val messages = value.optJSONArray("messages") ?: return
        val contacts = value.optJSONArray("contacts")

        for (i in 0 until messages.length()) {
            val msg  = messages.optJSONObject(i) ?: continue
            val type = msg.optString("type", "")
            if (type != "text") continue

            val from    = msg.optString("from", "")
            val msgId   = msg.optString("id", "")
            val text    = msg.optJSONObject("text")?.optString("body", "") ?: ""
            val tsEpoch = msg.optLong("timestamp", 0L)

            if (text.isBlank()) continue

            val displayName = (0 until (contacts?.length() ?: 0))
                .mapNotNull { contacts?.optJSONObject(it) }
                .firstOrNull { it.optString("wa_id") == from }
                ?.optJSONObject("profile")?.optString("name")
                ?: from

            val unified = UnifiedMessage(
                source            = BridgeSource.WHATSAPP,
                channelId         = from,
                senderId          = from,
                senderName        = displayName,
                text              = text,
                platformMessageId = msgId,
                timestampMs       = tsEpoch * 1_000L,
            )
            onMessage?.invoke(unified)
            Log.i(TAG, "WhatsApp message from $from: ${text.take(80)}")
        }
    }

    /**
     * Verify X-Hub-Signature-256 using [BridgeConfig.WhatsApp.appSecret] as the HMAC key.
     *
     * Per Meta documentation:
     *   signature = "sha256=" + hex( HMAC-SHA256( rawPayload, appSecret ) )
     *
     * The appSecret is the application secret from the Meta Developer Console —
     * NOT the verifyToken, which is only used for the initial GET challenge.
     */
    private fun verifySignature(body: String, signature: String?): Boolean {
        if (signature == null) {
            Log.w(TAG, "Missing X-Hub-Signature-256 header")
            return false
        }
        val expected = signature.removePrefix("sha256=")
        return try {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(
                config.appSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val computed = mac.doFinal(body.toByteArray(Charsets.UTF_8))
            val hex = computed.joinToString("") { "%02x".format(it) }
            val valid = hex == expected
            if (!valid) Log.w(TAG, "HMAC mismatch: computed=$hex expected=$expected")
            valid
        } catch (e: Exception) {
            Log.e(TAG, "HMAC verification error: ${e.message}")
            false
        }
    }
}
