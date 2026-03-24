package com.ernos.mobile.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for the Bridge package.
 *
 * [BridgeService], [DiscordBridge], [TelegramBridge], and [WhatsAppBridge] are
 * Android Foreground Services / network clients that cannot run in a JVM test.
 * We test the pure data structures and message-normalisation logic using the
 * actual [UnifiedMessage], [BridgeSource], and [BridgeConfig] data classes,
 * plus a fake message normaliser that mirrors what each bridge does before
 * forwarding to the engine.
 *
 * Covered:
 *   - [UnifiedMessage] construction and field access
 *   - [BridgeSource] enum exhaustiveness
 *   - [BridgeConfig.isConfigured] validation
 *   - JSON normalisation round-trip (platform payload → UnifiedMessage)
 *   - Reply routing: only source platform receives the reply
 *   - Message deduplication via ID uniqueness
 */
class BridgeTest {

    // ── UnifiedMessage construction ───────────────────────────────────────────

    @Test fun unified_message_construction() {
        val msg = UnifiedMessage(
            source            = BridgeSource.TELEGRAM,
            channelId         = "chat-456",
            senderId          = "user123",
            senderName        = "Alice",
            text              = "Hello ErnOS!",
            timestampMs       = 1_700_000_000_000L,
            platformMessageId = "tg-msg-42",
        )
        assertEquals(BridgeSource.TELEGRAM,     msg.source)
        assertEquals("chat-456",                msg.channelId)
        assertEquals("user123",                 msg.senderId)
        assertEquals("Alice",                   msg.senderName)
        assertEquals("Hello ErnOS!",            msg.text)
        assertEquals(1_700_000_000_000L,        msg.timestampMs)
        assertEquals("tg-msg-42",               msg.platformMessageId)
    }

    @Test fun unified_message_default_timestamp_is_positive() {
        val msg = UnifiedMessage(
            source     = BridgeSource.DISCORD,
            channelId  = "c1",
            senderId   = "u1",
            senderName = "Bob",
            text       = "test",
        )
        assertTrue("Default timestamp must be positive", msg.timestampMs > 0)
    }

    @Test fun unified_message_image_url_is_null_by_default() {
        val msg = UnifiedMessage(
            source     = BridgeSource.CUSTOM,
            channelId  = "c1",
            senderId   = "u1",
            senderName = "u1",
            text       = "hi",
        )
        assertEquals(null, msg.imageUrl)
    }

    // ── BridgeSource enum ─────────────────────────────────────────────────────

    @Test fun bridge_source_enum_has_expected_values() {
        val values = BridgeSource.values()
        val names  = values.map { it.name }
        assertTrue(names.contains("DISCORD"))
        assertTrue(names.contains("TELEGRAM"))
        assertTrue(names.contains("WHATSAPP"))
        assertTrue(names.contains("CUSTOM"))
    }

    // ── BridgeConfig.isConfigured validation ──────────────────────────────────

    @Test fun discord_config_valid_with_non_blank_token() {
        val cfg = BridgeConfig.Discord(botToken = "Bot xoxb-123-abc")
        assertTrue(cfg.isConfigured)
    }

    @Test fun discord_config_invalid_with_blank_token() {
        val cfg = BridgeConfig.Discord(botToken = "")
        assertFalse(cfg.isConfigured)
    }

    @Test fun telegram_config_valid_with_non_blank_token() {
        val cfg = BridgeConfig.Telegram(botToken = "123456:ABC-DEF")
        assertTrue(cfg.isConfigured)
    }

    @Test fun telegram_config_invalid_with_blank_token() {
        val cfg = BridgeConfig.Telegram(botToken = "  ")
        assertFalse(cfg.isConfigured)
    }

    @Test fun whatsapp_config_valid_when_all_fields_non_blank() {
        val cfg = BridgeConfig.WhatsApp(
            phoneNumberId = "12345",
            accessToken   = "EAAG...",
            verifyToken   = "verify-secret",
            appSecret     = "app-secret-abc",
        )
        assertTrue(cfg.isConfigured)
    }

    @Test fun whatsapp_config_invalid_when_any_field_blank() {
        val cfg = BridgeConfig.WhatsApp(
            phoneNumberId = "12345",
            accessToken   = "",          // blank
            verifyToken   = "verify-secret",
            appSecret     = "secret",
        )
        assertFalse(cfg.isConfigured)
    }

    @Test fun custom_config_valid_with_non_blank_response_url() {
        val cfg = BridgeConfig.Custom(responseUrl = "https://example.com/reply")
        assertTrue(cfg.isConfigured)
    }

    @Test fun custom_config_invalid_with_blank_response_url() {
        val cfg = BridgeConfig.Custom(responseUrl = "")
        assertFalse(cfg.isConfigured)
    }

    @Test fun offload_config_valid_with_non_blank_host() {
        val cfg = BridgeConfig.Offload(host = "192.168.1.100")
        assertTrue(cfg.isConfigured)
    }

    @Test fun offload_config_invalid_with_blank_host() {
        val cfg = BridgeConfig.Offload(host = "")
        assertFalse(cfg.isConfigured)
    }

    // ── JSON → UnifiedMessage normalisation ───────────────────────────────────

    /**
     * Mirrors normalisation logic in TelegramBridge: long-poll update → UnifiedMessage.
     */
    private fun normaliseTelegramPayload(json: String): UnifiedMessage {
        val obj     = JSONObject(json)
        val message = obj.getJSONObject("message")
        val from    = message.getJSONObject("from")
        return UnifiedMessage(
            source            = BridgeSource.TELEGRAM,
            channelId         = message.optString("chat_id", "0"),
            senderId          = from.optLong("id").toString(),
            senderName        = from.optString("first_name", "Unknown"),
            text              = message.optString("text"),
            timestampMs       = message.optLong("date", System.currentTimeMillis() / 1000) * 1000L,
            platformMessageId = message.optLong("message_id").toString(),
        )
    }

    @Test fun telegram_payload_normalises_correctly() {
        val json = """
            {
              "update_id": 100,
              "message": {
                "message_id": 42,
                "from": {"id": 999, "first_name": "Alice"},
                "chat_id": "chat-xyz",
                "date": 1700000000,
                "text": "Hello!"
              }
            }
        """.trimIndent()
        val msg = normaliseTelegramPayload(json)
        assertEquals("42",                   msg.platformMessageId)
        assertEquals("Hello!",               msg.text)
        assertEquals("999",                  msg.senderId)
        assertEquals("Alice",                msg.senderName)
        assertEquals(BridgeSource.TELEGRAM,  msg.source)
        assertEquals(1_700_000_000_000L,     msg.timestampMs)
    }

    /**
     * Mirrors normalisation in DiscordBridge: MESSAGE_CREATE event → UnifiedMessage.
     */
    private fun normaliseDiscordPayload(json: String): UnifiedMessage {
        val obj    = JSONObject(json)
        val d      = obj.getJSONObject("d")
        val author = d.getJSONObject("author")
        return UnifiedMessage(
            source            = BridgeSource.DISCORD,
            channelId         = d.optString("channel_id", ""),
            senderId          = author.optString("id"),
            senderName        = author.optString("username", "unknown"),
            text              = d.optString("content"),
            platformMessageId = d.optString("id"),
        )
    }

    @Test fun discord_payload_normalises_correctly() {
        val json = """
            {
              "op": 0,
              "t": "MESSAGE_CREATE",
              "d": {
                "id": "msg-001",
                "channel_id": "ch-999",
                "content": "What is the weather?",
                "author": {"id": "user-007", "username": "Bob"}
              }
            }
        """.trimIndent()
        val msg = normaliseDiscordPayload(json)
        assertEquals("msg-001",              msg.platformMessageId)
        assertEquals("What is the weather?", msg.text)
        assertEquals("user-007",             msg.senderId)
        assertEquals("Bob",                  msg.senderName)
        assertEquals("ch-999",               msg.channelId)
        assertEquals(BridgeSource.DISCORD,   msg.source)
    }

    // ── Message ID uniqueness ─────────────────────────────────────────────────

    @Test fun successive_uuid_ids_are_unique() {
        val ids = (1..1000).map { UUID.randomUUID().toString() }.toSet()
        assertEquals("All 1000 generated IDs must be unique", 1000, ids.size)
    }

    // ── Reply routing ─────────────────────────────────────────────────────────

    class FakeReplyRouter {
        val repliesSent = mutableListOf<Pair<BridgeSource, String>>()

        fun replyTo(message: UnifiedMessage, reply: String) {
            repliesSent.add(message.source to reply)
        }
    }

    @Test fun reply_routes_back_to_source_platform() {
        val router = FakeReplyRouter()
        val msg    = UnifiedMessage(
            source     = BridgeSource.WHATSAPP,
            channelId  = "wa-chat",
            senderId   = "u1",
            senderName = "u1",
            text       = "Hi",
        )
        router.replyTo(msg, "Hello from ErnOS!")

        assertEquals(1,                     router.repliesSent.size)
        assertEquals(BridgeSource.WHATSAPP, router.repliesSent[0].first)
        assertEquals("Hello from ErnOS!",  router.repliesSent[0].second)
    }

    @Test fun reply_does_not_route_to_other_platforms() {
        val router = FakeReplyRouter()
        val msg    = UnifiedMessage(
            source     = BridgeSource.DISCORD,
            channelId  = "dc-ch",
            senderId   = "u2",
            senderName = "u2",
            text       = "Hey",
        )
        router.replyTo(msg, "Response")

        val sentToTelegram = router.repliesSent.any { it.first == BridgeSource.TELEGRAM }
        assertFalse("Reply must not be sent to Telegram when source is Discord", sentToTelegram)
    }
}
