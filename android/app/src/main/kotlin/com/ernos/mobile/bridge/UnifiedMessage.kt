package com.ernos.mobile.bridge

/**
 * UnifiedMessage
 *
 * Normalised representation of an incoming message from any bridge — Discord,
 * Telegram, WhatsApp, or a custom webhook.  All bridges produce this struct
 * before handing off to [BridgeService] for AI processing.
 *
 * Mirrors the ErnOS Synapse Bridge `chat_preprocessing.py` schema.
 */
data class UnifiedMessage(
    /** Which bridge produced this message. */
    val source: BridgeSource,

    /** Platform-specific channel / chat ID.  Used to route replies back. */
    val channelId: String,

    /** User or sender identifier (username, user ID, or webhook origin). */
    val senderId: String,

    /** Display name of the sender (may equal [senderId] if unavailable). */
    val senderName: String,

    /** The text content of the message. */
    val text: String,

    /** Optional URL of an image attachment, if the platform provides one. */
    val imageUrl: String? = null,

    /** Epoch-millisecond timestamp of the message.  Defaults to now. */
    val timestampMs: Long = System.currentTimeMillis(),

    /**
     * Platform-specific message ID (e.g. Discord message snowflake, Telegram
     * update_id).  Used for deduplication.
     */
    val platformMessageId: String? = null,
)

/** Identifies which bridge produced a [UnifiedMessage]. */
enum class BridgeSource {
    DISCORD,
    TELEGRAM,
    WHATSAPP,
    CUSTOM,
}
