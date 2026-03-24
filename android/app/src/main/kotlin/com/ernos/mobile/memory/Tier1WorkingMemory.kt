package com.ernos.mobile.memory

import android.content.Context
import android.util.Log
import com.ernos.mobile.memory.db.AppDatabase
import com.ernos.mobile.memory.db.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Tier 1 — Working Memory
 *
 * A Room-backed rolling window of chat messages persisted to disk.
 * Survives app restarts, device reboots, and process death.
 *
 * Rolling window: the table is capped at [MAX_WORKING_MESSAGES] rows.
 * When the limit is reached, the oldest rows are pruned AFTER they have been
 * offered to Tier 2 (autosave) for embedding — enforcing retrieval-first ordering.
 *
 * Token-awareness: callers pass an estimated [tokenCount] per message.
 * [totalTokens] lets the [MemoryManager] decide when to trigger autosave.
 *
 * KV-cache state serialisation: on app pause, [saveKvCacheState] stores the
 * current cache tag so the next session can skip re-processing unchanged prefix
 * tokens.  Tier 5 (Scratchpad / DataStore) is used as the backing store for
 * the cache tag because it is the lightest-weight persistence layer.
 */
class Tier1WorkingMemory(context: Context) {

    companion object {
        private const val TAG = "Tier1WorkingMemory"

        /** Maximum rows to keep in the messages table. */
        const val MAX_WORKING_MESSAGES = 200

        /**
         * When the running token count exceeds this fraction of the model's context
         * window, the MemoryManager should trigger a Tier 2 autosave flush.
         */
        const val AUTOSAVE_THRESHOLD_FRACTION = 0.75
    }

    private val db  = AppDatabase.getInstance(context)
    private val dao = db.messageDao()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Persist a new message.  Returns the assigned row ID.
     *
     * Does NOT prune here — pruning is always triggered by [MemoryManager]
     * AFTER Tier 2 embedding so we never lose data.
     */
    suspend fun addMessage(role: String, content: String, tokenCount: Int = estimateTokens(content)): Long =
        withContext(Dispatchers.IO) {
            dao.insert(
                MessageEntity(
                    role       = role,
                    content    = content,
                    tokenCount = tokenCount,
                )
            ).also { id ->
                Log.d(TAG, "Stored message id=$id role=$role tokens=$tokenCount")
            }
        }

    /** Return all persisted messages in chronological order. */
    suspend fun allMessages(): List<MessageEntity> = withContext(Dispatchers.IO) {
        dao.allMessages()
    }

    /**
     * Return the [limit] most recent messages, oldest-first, for injection
     * into the system prompt context window.
     */
    suspend fun recentMessages(limit: Int = 20): List<MessageEntity> = withContext(Dispatchers.IO) {
        dao.recentMessages(limit).reversed()
    }

    /** Observe the full message list as a reactive [Flow]. */
    fun observeMessages(): Flow<List<MessageEntity>> = dao.observeMessages()

    /** Current total approximate token count across all stored messages. */
    suspend fun totalTokens(): Int = withContext(Dispatchers.IO) {
        dao.totalTokenCount()
    }

    /**
     * Messages that have not yet been embedded into Tier 2.
     * Called by [MemoryManager] during the autosave flush.
     */
    suspend fun unembeddedMessages(): List<MessageEntity> = withContext(Dispatchers.IO) {
        dao.unembeddedMessages()
    }

    /** Mark a batch of messages as embedded so they won't be re-processed. */
    suspend fun markEmbedded(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        dao.markEmbedded(ids)
        Log.d(TAG, "Marked ${ids.size} message(s) as embedded")
    }

    /**
     * Prune the oldest messages beyond [MAX_WORKING_MESSAGES].
     *
     * IMPORTANT: only call AFTER marking unembedded messages as embedded,
     * otherwise data will be pruned before Tier 2 can process it.
     */
    suspend fun pruneIfNeeded() = withContext(Dispatchers.IO) {
        dao.pruneOldest(MAX_WORKING_MESSAGES)
        Log.d(TAG, "Pruned working memory to $MAX_WORKING_MESSAGES messages")
    }

    /** Wipe the entire working memory (e.g., user-triggered "clear conversation"). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
        Log.i(TAG, "Working memory cleared")
    }

    // ── KV-cache state serialisation ──────────────────────────────────────────
    //
    // The actual tag string is written/read via Tier5Scratchpad to keep
    // Tier 1 focused on messages. MemoryManager is responsible for the
    // read-on-resume / write-on-pause lifecycle calls.

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Simple word-count heuristic: 1 token ≈ 0.75 words (standard GPT approximation).
     * Replace with an exact count once llama.cpp tokenisation is wired in Milestone 6.
     */
    fun estimateTokens(text: String): Int =
        (text.split(Regex("\\s+")).size / 0.75).toInt().coerceAtLeast(1)

    /**
     * Return true if the working memory token count has crossed the autosave threshold
     * for the given [contextWindowTokens].
     */
    suspend fun shouldTriggerAutosave(contextWindowTokens: Int): Boolean {
        val used = totalTokens()
        val threshold = (contextWindowTokens * AUTOSAVE_THRESHOLD_FRACTION).toInt()
        return used >= threshold
    }
}
