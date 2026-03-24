package com.ernos.mobile.bridge

/**
 * Bridge
 *
 * Common interface every platform bridge must implement.
 *
 * Each bridge:
 *   1. Opens its connection in [start].
 *   2. Calls [onMessage] for each received [UnifiedMessage].
 *   3. Sends a reply string back to the platform in [sendReply].
 *   4. Closes its connection in [stop].
 */
interface Bridge {

    /** Human-readable name used in logs and notifications. */
    val name: String

    /**
     * Connect to the platform and begin delivering messages.
     *
     * @param onMessage  Callback invoked on every received [UnifiedMessage].
     *                   Must be safe to call from any thread.
     */
    suspend fun start(onMessage: (UnifiedMessage) -> Unit)

    /**
     * Send [reply] back to the platform channel identified by [message].
     *
     * This is a fire-and-forget operation: transient send failures are logged
     * but do not propagate exceptions to the caller.
     */
    suspend fun sendReply(message: UnifiedMessage, reply: String)

    /**
     * Disconnect from the platform and release all resources.
     * Safe to call multiple times.
     */
    fun stop()
}
