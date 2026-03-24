package com.ernos.mobile.bridge

/**
 * BridgeConfig
 *
 * Sealed hierarchy of runtime configuration blobs for each bridge.
 * Instances are populated from user settings (Tier 5 DataStore or
 * SharedPreferences) and passed to the corresponding bridge on start.
 */
sealed class BridgeConfig {

    /** True when the minimum required credentials are present. */
    abstract val isConfigured: Boolean

    data class Discord(
        /** Bot token from the Discord Developer Portal. */
        val botToken: String,
        /** Comma-separated list of guild/channel IDs to listen on, empty = all. */
        val channelFilter: String = "",
    ) : BridgeConfig() {
        override val isConfigured: Boolean get() = botToken.isNotBlank()
    }

    data class Telegram(
        /** Bot token from @BotFather. */
        val botToken: String,
        /**
         * Webhook URL for polling mode fallback; empty = long-polling.
         * Long-polling is default because it needs no public endpoint.
         */
        val webhookUrl: String = "",
    ) : BridgeConfig() {
        override val isConfigured: Boolean get() = botToken.isNotBlank()
    }

    data class WhatsApp(
        /** WhatsApp Cloud API phone number ID. */
        val phoneNumberId: String,
        /** Permanent access token from Meta Business Suite. */
        val accessToken: String,
        /**
         * Verify token — returned verbatim during Meta's GET webhook verification challenge.
         * This must match the value configured in the Meta Developer Console.
         */
        val verifyToken: String,
        /**
         * App secret from the Meta Developer Console.
         * Used as the HMAC-SHA256 key for X-Hub-Signature-256 verification of inbound payloads.
         * Never send this value outbound — only use it server-side for signature checks.
         */
        val appSecret: String,
        /** Local TCP port the embedded webhook HTTP server binds on (default 8080). */
        val webhookPort: Int = 8080,
    ) : BridgeConfig() {
        override val isConfigured: Boolean
            get() = phoneNumberId.isNotBlank() && accessToken.isNotBlank() &&
                    verifyToken.isNotBlank() && appSecret.isNotBlank()
    }

    data class Custom(
        /**
         * URL path the embedded webhook server listens on (default "/webhook").
         * Inbound POST requests to this path are treated as [UnifiedMessage] JSON.
         */
        val inboundPath: String = "/webhook",
        /** URL to POST the AI reply back to the caller. */
        val responseUrl: String,
        /** Optional shared secret validated from the X-ErnOS-Secret request header. */
        val sharedSecret: String = "",
        /** Local TCP port the embedded webhook HTTP server binds on (default 8080). */
        val webhookPort: Int = 8080,
    ) : BridgeConfig() {
        override val isConfigured: Boolean get() = responseUrl.isNotBlank()
    }

    data class Offload(
        /**
         * IP address or hostname of the Mac/PC running offload_server.py.
         * Example: "192.168.1.100"
         */
        val host: String,
        /** WebSocket port of the offload server (default 8765). */
        val port: Int = 8765,
    ) : BridgeConfig() {
        override val isConfigured: Boolean get() = host.isNotBlank()
    }
}
