package com.ernos.mobile.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.ernos.mobile.ErnOSApplication
import com.ernos.mobile.MainActivity
import com.ernos.mobile.engine.InferenceBackend
import com.ernos.mobile.engine.ReActLoopManager
import com.ernos.mobile.engine.SystemPrompter
import com.ernos.mobile.engine.ToolRegistry
import com.ernos.mobile.offload.OffloadClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BridgeService
 *
 * Android foreground service that manages all platform bridge connections
 * (Discord, Telegram, WhatsApp, Custom) and an optional PC offload client.
 *
 * Inbound webhook handling (WhatsApp & Custom):
 *   An embedded [WebhookServer] binds on the configured port and dispatches
 *   POST/GET requests to the WhatsApp and Custom bridges.  This is the only
 *   way inbound webhooks can reach the app without an external proxy.
 *
 * PC Offload:
 *   When [EXTRA_OFFLOAD_HOST] is provided, [OffloadClient] connects to the
 *   companion offload_server.py.  When connected, inference runs on the Mac/PC
 *   GPU and ToolRegistry unlocks bash_execute, terminal_read, finder_open.
 *   SystemPrompter.ModelConfig.isOffloaded is set to true so the model schema
 *   includes those tools.
 *
 * Intent extras:
 *   EXTRA_DISCORD_TOKEN       — Discord bot token
 *   EXTRA_TELEGRAM_TOKEN      — Telegram bot token
 *   EXTRA_WA_PHONE_ID         — WhatsApp phone number ID
 *   EXTRA_WA_ACCESS_TOKEN     — WhatsApp access token
 *   EXTRA_WA_VERIFY_TOKEN     — WhatsApp webhook verify token (for registration challenge)
 *   EXTRA_WA_APP_SECRET       — WhatsApp app secret (HMAC key for signature verification)
 *   EXTRA_WA_WEBHOOK_PORT     — Local port for the webhook HTTP server (default 8080)
 *   EXTRA_CUSTOM_RESPONSE_URL — Custom bridge POST reply URL
 *   EXTRA_CUSTOM_SECRET       — Custom bridge X-ErnOS-Secret header value
 *   EXTRA_CUSTOM_INBOUND_PATH — Custom bridge URL path (default "/webhook")
 *   EXTRA_CUSTOM_WEBHOOK_PORT — Port for custom webhook server (default 8080, shared if WA also set)
 *   EXTRA_OFFLOAD_HOST        — PC offload server hostname/IP
 *   EXTRA_OFFLOAD_PORT        — PC offload server port (default 8765)
 */
class BridgeService : Service() {

    companion object {
        private const val TAG           = "BridgeService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID   = "ernos_bridge_channel"

        const val ACTION_STOP = "com.ernos.mobile.BRIDGE_STOP"

        const val EXTRA_DISCORD_TOKEN       = "discord_token"
        const val EXTRA_TELEGRAM_TOKEN      = "telegram_token"
        const val EXTRA_WA_PHONE_ID         = "wa_phone_id"
        const val EXTRA_WA_ACCESS_TOKEN     = "wa_access_token"
        const val EXTRA_WA_VERIFY_TOKEN     = "wa_verify_token"
        const val EXTRA_WA_APP_SECRET       = "wa_app_secret"
        const val EXTRA_WA_WEBHOOK_PORT     = "wa_webhook_port"
        const val EXTRA_CUSTOM_RESPONSE_URL = "custom_response_url"
        const val EXTRA_CUSTOM_SECRET       = "custom_secret"
        const val EXTRA_CUSTOM_INBOUND_PATH = "custom_inbound_path"
        const val EXTRA_CUSTOM_WEBHOOK_PORT = "custom_webhook_port"
        const val EXTRA_OFFLOAD_HOST        = "offload_host"
        const val EXTRA_OFFLOAD_PORT        = "offload_port"
        const val EXTRA_OFFLOAD_SECRET      = "offload_secret"

        fun start(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeBridges  = mutableListOf<Bridge>()
    private var webhookServer: WebhookServer? = null
    private var offloadClient:  OffloadClient? = null

    // Lazily cached WhatsApp and Custom bridge references for webhook dispatch
    private var whatsAppBridge: WhatsAppBridge?     = null
    private var customBridge:   CustomBridgeBuilder? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "BridgeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("ErnOS bridges active"))

        val bridges = buildBridges(intent)

        // Determine whether offload is configured before the client is created,
        // so that offload-only (no platform bridges) keeps the service running.
        val offloadHost = intent?.getStringExtra(EXTRA_OFFLOAD_HOST).orEmpty()
        val hasOffload  = offloadHost.isNotBlank()

        if (bridges.isEmpty() && !hasOffload) {
            Log.w(TAG, "No bridges or offload configured — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        activeBridges.addAll(bridges)
        startWebhookServer(intent)
        startOffloadClient(intent)   // connects in background coroutine

        bridges.forEach { bridge ->
            scope.launch {
                try {
                    bridge.start { message -> handleIncoming(message) }
                } catch (e: Exception) {
                    Log.e(TAG, "${bridge.name} bridge crashed: ${e.message}", e)
                }
            }
        }

        Log.i(TAG, "Started ${bridges.size} bridge(s): ${bridges.map { it.name }}")
        return START_STICKY
    }

    override fun onDestroy() {
        activeBridges.forEach { it.stop() }
        activeBridges.clear()
        webhookServer?.stop()
        webhookServer = null
        offloadClient?.disconnect()
        offloadClient = null
        BridgeState.offloadClient = null
        Log.i(TAG, "BridgeService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Incoming message handling ─────────────────────────────────────────────

    private fun handleIncoming(message: UnifiedMessage) {
        Log.i(TAG, "Incoming [${message.source}] from ${message.senderName}: ${message.text.take(80)}")
        scope.launch {
            try {
                val reply = processMessage(message)
                activeBridges
                    .firstOrNull { it.bridgeSource() == message.source }
                    ?.sendReply(message, reply)
            } catch (e: Exception) {
                Log.e(TAG, "handleIncoming error: ${e.message}", e)
            }
        }
    }

    /**
     * Run the ErnOS ReAct loop for [message].
     *
     * When [offloadClient] is connected, inference runs on the Mac/PC and
     * host-only tools (bash_execute, terminal_read, finder_open) are available.
     */
    private suspend fun processMessage(message: UnifiedMessage): String {
        val memoryManager = ErnOSApplication.memoryManager
        val client = offloadClient
        val isOffloaded = client?.isConnected == true

        val memoryContext = try {
            memoryManager.retrieveContext(message.text)
        } catch (e: Exception) {
            Log.w(TAG, "Memory retrieval error: ${e.message}")
            ""
        }

        try {
            memoryManager.storeUserMessage(message.text, 4096)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store bridge message in memory: ${e.message}")
        }

        val systemPrompter = SystemPrompter(applicationContext)
        val toolRegistry   = ToolRegistry(applicationContext, memoryManager, client)

        val config = SystemPrompter.ModelConfig(
            modelName     = "ErnOS Bridge",
            contextWindow = 4096,
            isMultimodal  = false,
            isOffloaded   = isOffloaded,
        )

        val bridgeRuntime = BridgeState.llamaRuntime
        val bridgeHandle  = BridgeState.modelHandle

        if (!isOffloaded && (bridgeRuntime == null || bridgeHandle == 0L)) {
            return "ErnOS is not ready — please load a model in the main app first."
        }

        val backend: InferenceBackend = if (isOffloaded) {
            InferenceBackend.OffloadBackend(client!!)
        } else {
            InferenceBackend.LocalBackend(bridgeRuntime!!)
        }
        if (isOffloaded) Log.i(TAG, "Bridge inference routed to offload server")

        val react = ReActLoopManager(backend, systemPrompter, toolRegistry)

        var finalReply = "I couldn't generate a response — please try again."
        try {
            val result = react.run(
                handle        = bridgeHandle,
                userMessage   = "[${message.source}] ${message.senderName}: ${message.text}",
                config        = config,
                memoryContext = memoryContext,
            )
            finalReply = when (result) {
                is com.ernos.mobile.engine.ReActLoopResult.Reply           -> result.message
                is com.ernos.mobile.engine.ReActLoopResult.MaxTurnsReached -> result.lastResponse
                is com.ernos.mobile.engine.ReActLoopResult.Error           -> "Error: ${result.message}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "ReAct loop error for bridge message: ${e.message}", e)
        }

        try {
            memoryManager.storeAiResponse(finalReply, 4096)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store bridge reply in memory: ${e.message}")
        }

        return finalReply
    }

    // ── Webhook server ────────────────────────────────────────────────────────

    /**
     * Start the embedded [WebhookServer] if any webhook-based bridge is configured.
     *
     * WhatsApp and Custom bridges both use the same server instance.
     * If both are configured and use the same port, they share one server.
     */
    private fun startWebhookServer(intent: Intent?) {
        val waPhoneId     = intent?.getStringExtra(EXTRA_WA_PHONE_ID) ?: ""
        val customUrl     = intent?.getStringExtra(EXTRA_CUSTOM_RESPONSE_URL) ?: ""
        val waPort        = intent?.getIntExtra(EXTRA_WA_WEBHOOK_PORT, 8080) ?: 8080
        val customPort    = intent?.getIntExtra(EXTRA_CUSTOM_WEBHOOK_PORT, 8080) ?: 8080
        val customPath    = intent?.getStringExtra(EXTRA_CUSTOM_INBOUND_PATH) ?: "/webhook"

        val needsServer = waPhoneId.isNotBlank() || customUrl.isNotBlank()
        if (!needsServer) return

        // Use the WhatsApp port as the primary port; assume Custom is on the same port
        // unless explicitly different (start a single shared server for simplicity).
        val port = if (waPhoneId.isNotBlank()) waPort else customPort
        val server = WebhookServer(port)
        webhookServer = server

        // Register WhatsApp handlers
        val wa = whatsAppBridge
        if (wa != null) {
            // GET /whatsapp — Meta's verification challenge
            server.registerGet("/whatsapp") { params ->
                wa.handleVerificationChallenge(
                    mode      = params["hub.mode"],
                    token     = params["hub.verify_token"],
                    challenge = params["hub.challenge"],
                )
            }
            // POST /whatsapp — inbound messages
            server.registerPost("/whatsapp") { body, headers ->
                val sig = headers["x-hub-signature-256"]
                val accepted = wa.handleWebhookPayload(body, sig)
                if (accepted) {
                    WebhookServer.PostResult(200, "EVENT_RECEIVED")
                } else {
                    WebhookServer.PostResult(403, "Forbidden")
                }
            }
            Log.i(TAG, "Webhook server: WhatsApp handlers registered at /whatsapp on port $port")
        }

        // Register Custom bridge handlers
        val cb = customBridge
        if (cb != null) {
            val normPath = if (customPath.startsWith("/")) customPath else "/$customPath"
            server.registerPost(normPath) { body, headers ->
                val secret = headers["x-ernos-secret"]
                val accepted = cb.handleWebhookPayload(body, secret)
                if (accepted) {
                    WebhookServer.PostResult(200, "OK")
                } else {
                    WebhookServer.PostResult(403, "Forbidden")
                }
            }
            Log.i(TAG, "Webhook server: Custom handler registered at $normPath on port $port")
        }

        server.start()
        Log.i(TAG, "Embedded WebhookServer started on port $port")
    }

    // ── Offload client ────────────────────────────────────────────────────────

    private fun startOffloadClient(intent: Intent?) {
        val host   = intent?.getStringExtra(EXTRA_OFFLOAD_HOST) ?: ""
        if (host.isBlank()) return
        val port   = intent?.getIntExtra(EXTRA_OFFLOAD_PORT, 8765) ?: 8765
        val secret = intent?.getStringExtra(EXTRA_OFFLOAD_SECRET) ?: ""
        if (secret.isBlank()) {
            Log.w(TAG, "Offload client connecting WITHOUT auth — set EXTRA_OFFLOAD_SECRET for security")
        }
        val client = OffloadClient(host = host, port = port, secret = secret)
        offloadClient = client
        BridgeState.offloadClient = client
        scope.launch {
            val connected = client.connect()
            if (connected) {
                Log.i(TAG, "PC offload client connected to $host:$port — host-only tools enabled")
            } else {
                Log.w(TAG, "PC offload client failed to connect to $host:$port")
            }
        }
    }

    // ── Bridge builder ────────────────────────────────────────────────────────

    private fun buildBridges(intent: Intent?): List<Bridge> {
        val result = mutableListOf<Bridge>()
        if (intent == null) return result

        val discordToken = intent.getStringExtra(EXTRA_DISCORD_TOKEN) ?: ""
        if (discordToken.isNotBlank()) {
            result.add(DiscordBridge(BridgeConfig.Discord(botToken = discordToken)))
            Log.d(TAG, "Discord bridge configured")
        }

        val telegramToken = intent.getStringExtra(EXTRA_TELEGRAM_TOKEN) ?: ""
        if (telegramToken.isNotBlank()) {
            result.add(TelegramBridge(BridgeConfig.Telegram(botToken = telegramToken)))
            Log.d(TAG, "Telegram bridge configured")
        }

        val waPhoneId     = intent.getStringExtra(EXTRA_WA_PHONE_ID) ?: ""
        val waAccessToken = intent.getStringExtra(EXTRA_WA_ACCESS_TOKEN) ?: ""
        val waVerifyToken = intent.getStringExtra(EXTRA_WA_VERIFY_TOKEN) ?: ""
        val waAppSecret   = intent.getStringExtra(EXTRA_WA_APP_SECRET) ?: ""
        val waPort        = intent.getIntExtra(EXTRA_WA_WEBHOOK_PORT, 8080)
        if (waPhoneId.isNotBlank() && waAccessToken.isNotBlank() &&
            waVerifyToken.isNotBlank() && waAppSecret.isNotBlank()) {
            val wa = WhatsAppBridge(BridgeConfig.WhatsApp(
                phoneNumberId = waPhoneId,
                accessToken   = waAccessToken,
                verifyToken   = waVerifyToken,
                appSecret     = waAppSecret,
                webhookPort   = waPort,
            ))
            whatsAppBridge = wa
            result.add(wa)
            Log.d(TAG, "WhatsApp bridge configured")
        }

        val customResponseUrl  = intent.getStringExtra(EXTRA_CUSTOM_RESPONSE_URL) ?: ""
        val customSecret       = intent.getStringExtra(EXTRA_CUSTOM_SECRET) ?: ""
        val customInboundPath  = intent.getStringExtra(EXTRA_CUSTOM_INBOUND_PATH) ?: "/webhook"
        val customPort         = intent.getIntExtra(EXTRA_CUSTOM_WEBHOOK_PORT, 8080)
        if (customResponseUrl.isNotBlank()) {
            val cb = CustomBridgeBuilder(BridgeConfig.Custom(
                inboundPath  = customInboundPath,
                responseUrl  = customResponseUrl,
                sharedSecret = customSecret,
                webhookPort  = customPort,
            ))
            customBridge = cb
            result.add(cb)
            Log.d(TAG, "Custom bridge configured")
        }

        return result
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ErnOS Bridge Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Maintains platform bridge connections for Discord, Telegram, WhatsApp"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ErnOS")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .addAction(Notification.Action.Builder(
                null, "Stop", stopIntent,
            ).build())
            .build()
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun Bridge.bridgeSource(): BridgeSource = when (this) {
        is DiscordBridge       -> BridgeSource.DISCORD
        is TelegramBridge      -> BridgeSource.TELEGRAM
        is WhatsAppBridge      -> BridgeSource.WHATSAPP
        is CustomBridgeBuilder -> BridgeSource.CUSTOM
        else                   -> BridgeSource.CUSTOM
    }
}

/**
 * BridgeState
 *
 * Process-wide volatile state shared between [BridgeService] and [ChatViewModel].
 * [ChatViewModel] writes [llamaRuntime] and [modelHandle] when a model is loaded.
 * [BridgeService] writes [offloadClient] when a PC offload connection is established.
 */
object BridgeState {
    @Volatile var llamaRuntime: com.ernos.mobile.LlamaRuntime? = null
    @Volatile var modelHandle:  Long = 0L
    /** Non-null when [BridgeService] has a live offload connection. */
    @Volatile var offloadClient: OffloadClient? = null
}
