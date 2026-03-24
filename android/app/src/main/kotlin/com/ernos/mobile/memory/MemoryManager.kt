package com.ernos.mobile.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MemoryManager
 *
 * Single entry point for all five memory tiers.  Enforces the HIVE retrieval-first
 * pattern: memory is ALWAYS retrieved before the current event is stored.
 *
 *   1. [retrieveContext]  — called BEFORE storing the user message; returns a
 *                           formatted context string for the system prompt.
 *   2. [storeUserMessage] — called AFTER retrieval, stores user text in Tier 1
 *                           and Tier 4, then checks the autosave threshold.
 *   3. [storeAiResponse]  — called AFTER the AI reply is finalised.
 *   4. [flushToTier2]     — embeds un-embedded Tier 1 messages into Tier 2
 *                           (semantic search) and prunes the working-memory window.
 *   5. [runSessionSetup]  — called at app start: runs homeostasis, logs session
 *                           start, loads ONNX model.
 *   6. [runSessionTeardown] — called on app pause/destroy: logs session end,
 *                             saves KV-cache tag, closes ONNX session.
 *
 * Context window size [contextWindowTokens] comes from the model config and is
 * used to compute the autosave threshold.
 */
class MemoryManager(private val context: Context) {

    companion object {
        private const val TAG = "MemoryManager"
    }

    // ── Tier instances ────────────────────────────────────────────────────────

    val tier1 = Tier1WorkingMemory(context)
    val tier2 = Tier2AutoSave(context)
    val tier3 = Tier3SynapticGraph(context)
    val tier4 = Tier4Timeline(context)
    val tier5 = Tier5Scratchpad(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Session lifecycle ─────────────────────────────────────────────────────

    /**
     * Called at app start and on every foreground resume after [runSessionTeardown].
     *
     * Idempotent: if the ONNX session is already open (e.g., the app was never
     * backgrounded), this is a no-op for both [Tier2AutoSave.loadModel] and the
     * timeline log — [Tier4Timeline.logSessionStart] is only emitted when the
     * session was actually closed during a prior teardown, preventing duplicate
     * "session start" timeline entries from normal foreground/background cycles.
     */
    suspend fun runSessionSetup(contextWindowTokens: Int = 4096) {
        val wasUnloaded = !tier2.isModelLoaded
        Log.i(TAG, "Session setup starting (onnxWasClosed=$wasUnloaded)")

        tier2.loadModel()          // idempotent: skip if already open
        tier3.runHomeostasis()     // runs fast if graph hasn't changed

        // Only emit a session-start timeline event when the app was actually
        // backgrounded (ONNX was closed).  Prevents duplicate entries on the
        // very first launch-then-resume within the same process lifetime.
        if (wasUnloaded) {
            tier4.logSessionStart()
        }

        Log.i(TAG, "Session setup complete (ONNX loaded=${tier2.isModelLoaded})")
    }

    /**
     * Called when the app is paused or destroyed.
     *
     * Saves the KV-cache tag to Tier 5 and closes the ONNX session.
     */
    suspend fun runSessionTeardown(kvCacheTag: String = "") {
        tier5.saveKvCacheTag(kvCacheTag)
        tier5.set(Tier5Scratchpad.KEY_LAST_SESSION_MS, System.currentTimeMillis())
        tier4.logSessionEnd()
        tier2.closeModel()
        Log.i(TAG, "Session teardown complete")
    }

    // ── Retrieval-first API ───────────────────────────────────────────────────

    /**
     * Retrieve relevant memory context for [userMessage].
     *
     * MUST be called BEFORE [storeUserMessage] to honour retrieval-first ordering.
     *
     * Context sections (included if non-empty):
     *   - Tier 1: last N messages formatted as conversation history
     *   - Tier 2: top-k semantically similar past chunks
     *   - Tier 3: graph knowledge related to the query
     *   - Tier 4: recent timeline summary (last 10 events)
     *
     * Returns a formatted multi-section string ready for injection into
     * [SystemPrompter.buildSystemPrompt]'s memoryContext parameter.
     */
    suspend fun retrieveContext(userMessage: String): String = withContext(Dispatchers.IO) {
        val sections = mutableListOf<String>()

        // Tier 1: conversation history
        val history = tier1.recentMessages(limit = 10)
        if (history.isNotEmpty()) {
            val historyText = history.joinToString("\n") { m ->
                "${m.role.uppercase()}: ${m.content.take(200)}"
            }
            sections.add("[CONVERSATION HISTORY]\n$historyText")
        }

        // Tier 2: semantic search
        if (tier2.isModelLoaded) {
            val chunks = tier2.search(userMessage, topK = 5)
            if (chunks.isNotEmpty()) {
                val chunkText = chunks.joinToString("\n") { c ->
                    "(${"%.2f".format(c.score)}) ${c.text.take(200)}"
                }
                sections.add("[SEMANTIC MEMORY]\n$chunkText")
            }
        }

        // Tier 3: graph context
        val graphCtx = tier3.buildContextForQuery(userMessage, maxNodes = 4)
        if (graphCtx.isNotEmpty()) {
            sections.add("[KNOWLEDGE GRAPH]\n$graphCtx")
        }

        // Tier 4: recent timeline
        val timeline = tier4.recentSummary(maxEvents = 5, keyword = null)
        if (timeline.isNotEmpty()) {
            sections.add("[RECENT ACTIVITY]\n$timeline")
        }

        if (sections.isEmpty()) "" else sections.joinToString("\n\n")
    }

    // ── Storage API ───────────────────────────────────────────────────────────

    /**
     * Store a user message across Tier 1 and Tier 4.
     * Triggers autosave if the token budget is nearly exhausted.
     *
     * Call AFTER [retrieveContext].
     */
    suspend fun storeUserMessage(text: String, contextWindowTokens: Int = 4096) {
        val tokenCount = tier1.estimateTokens(text)
        tier1.addMessage("user", text, tokenCount)
        tier4.logMessage("user", text)

        checkAndFlushTier2(contextWindowTokens)
    }

    /**
     * Store an AI response across Tier 1 and Tier 4.
     */
    suspend fun storeAiResponse(text: String, contextWindowTokens: Int = 4096) {
        val tokenCount = tier1.estimateTokens(text)
        tier1.addMessage("assistant", text, tokenCount)
        tier4.logMessage("assistant", text)

        checkAndFlushTier2(contextWindowTokens)
    }

    /**
     * Query memory for a free-text [query] with optional date-range filtering.
     * Used by [ToolRegistry.memory_query].
     *
     * Searches Tier 2 (semantic), Tier 3 (graph), and Tier 4 (timeline).
     *
     * @param query     Natural-language query, may contain date expressions such as
     *                  "yesterday", "last week", "last Monday".
     * @param fromDate  ISO-8601 date string ("yyyy-MM-dd").  Overrides date expression
     *                  parsing if provided.  Null = derive from [query] or no lower bound.
     * @param toDate    ISO-8601 date string for the upper bound.  Null = today.
     */
    suspend fun queryMemory(
        query: String,
        fromDate: String? = null,
        toDate: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val parts = mutableListOf<String>()

        // Tier 2 semantic search (keyword is still the full query string)
        if (tier2.isModelLoaded) {
            val chunks = tier2.search(query, topK = 5)
            if (chunks.isNotEmpty()) {
                parts.add("Semantic matches:\n" + chunks.joinToString("\n") {
                    "• (${"%.2f".format(it.score)}) ${it.text.take(200)}"
                })
            }
        }

        // Tier 3 graph
        val graphCtx = tier3.buildContextForQuery(query)
        if (graphCtx.isNotEmpty()) {
            parts.add("Knowledge graph:\n$graphCtx")
        }

        // Tier 4 timeline search — resolve date range
        val (resolvedFrom, resolvedTo) = resolveDateRange(query, fromDate, toDate)
        val dateRangeResolved = resolvedFrom != null || resolvedTo != null

        // Strip date expressions from the keyword.
        // If a date range was resolved, also check whether the remaining text is
        // a generic question preamble ("what did we talk about", "show me", etc.)
        // with no meaningful domain keyword.  In that case drop the keyword
        // entirely so the search returns ALL events within the date range instead
        // of matching the filler phrase against event content.
        val stripped = stripDateExpressions(query)
        val timelineKeyword: String? = when {
            stripped.isBlank()                                   -> null
            dateRangeResolved && isGenericPreamble(stripped)     -> null
            else                                                 -> stripped
        }

        val events = tier4.search(
            keyword  = timelineKeyword,
            fromDate = resolvedFrom,
            toDate   = resolvedTo,
            limit    = 10,
        )
        if (events.isNotEmpty()) {
            parts.add("Timeline entries:\n" + events.joinToString("\n") {
                "[${it.type}] ${it.content.take(150)}"
            })
        }

        if (parts.isEmpty()) "No relevant memory found for: \"$query\"" else parts.joinToString("\n\n")
    }

    // ── Natural-language date parsing ─────────────────────────────────────────

    /**
     * Resolve a (fromDate, toDate) pair for the Tier 4 timeline search.
     *
     * Resolution order:
     *   1. If [explicitFrom] / [explicitTo] are non-null, use them directly.
     *   2. Otherwise, attempt to parse a date expression from [query].
     *   3. If no expression is found, return (null, null) — no date filter.
     *
     * Recognised date expressions (case-insensitive):
     *   "yesterday"           → from=yesterday, to=yesterday
     *   "today"               → from=today, to=today
     *   "last week"           → from=7 days ago (Monday), to=yesterday
     *   "last month"          → from=first of last calendar month, to=last of last calendar month
     *   "last N days"         → from=N days ago, to=today
     *   "last Monday/Tuesday/…" → the most recent occurrence of that weekday
     */
    private fun resolveDateRange(
        query: String,
        explicitFrom: String?,
        explicitTo: String?,
    ): Pair<String?, String?> {
        if (explicitFrom != null || explicitTo != null) return explicitFrom to explicitTo

        val today    = java.time.LocalDate.now()
        val q        = query.lowercase()
        val fmt      = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

        return when {
            q.contains("yesterday") -> {
                val d = today.minusDays(1).format(fmt)
                d to d
            }
            q.contains("today") -> {
                val d = today.format(fmt)
                d to d
            }
            q.contains("last week") -> {
                val from = today.minusWeeks(1).with(java.time.DayOfWeek.MONDAY).format(fmt)
                val to   = today.minusDays(1).format(fmt)
                from to to
            }
            q.contains("last month") -> {
                val firstOfLastMonth = today.minusMonths(1).withDayOfMonth(1)
                val lastOfLastMonth  = firstOfLastMonth.plusMonths(1).minusDays(1)
                firstOfLastMonth.format(fmt) to lastOfLastMonth.format(fmt)
            }
            else -> {
                // "last N days" pattern
                val lastNDays = Regex("last\\s+(\\d+)\\s+days?")
                    .find(q)?.groupValues?.get(1)?.toIntOrNull()
                if (lastNDays != null) {
                    today.minusDays(lastNDays.toLong()).format(fmt) to today.format(fmt)
                } else {
                    // "last <weekday>" pattern
                    val weekdayMatch = Regex("last\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)")
                        .find(q)?.groupValues?.get(1)
                    if (weekdayMatch != null) {
                        val targetDay = java.time.DayOfWeek.valueOf(weekdayMatch.uppercase())
                        var candidate = today.minusDays(1)
                        while (candidate.dayOfWeek != targetDay) candidate = candidate.minusDays(1)
                        val d = candidate.format(fmt)
                        d to d
                    } else {
                        null to null
                    }
                }
            }
        }
    }

    /** Remove common date expressions from a query string to keep the keyword clean. */
    private fun stripDateExpressions(query: String): String =
        query
            .replace(Regex("(?i)\\byesterday\\b"), "")
            .replace(Regex("(?i)\\btoday\\b"), "")
            .replace(Regex("(?i)\\blast week\\b"), "")
            .replace(Regex("(?i)\\blast month\\b"), "")
            .replace(Regex("(?i)\\blast \\d+ days?\\b"), "")
            .replace(Regex("(?i)\\blast (monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    /**
     * Return true when [text] consists only of a generic question preamble with
     * no meaningful domain keyword.
     *
     * Examples that should NOT produce a keyword filter for timeline search:
     *   "what did we talk about"   (after stripping "yesterday")
     *   "show me"
     *   "what happened"
     *   "tell me about"
     *   "can you recall"
     *   "recall"
     *   "recap"
     *   "remind me"
     *   "what was discussed"
     *
     * Any text longer than ~30 characters or containing lowercase letters beyond
     * common stop words is assumed to carry a real keyword and returns false.
     */
    private fun isGenericPreamble(text: String): Boolean {
        val normalised = text.lowercase().trim()
            .replace(Regex("[?!.,]"), "")
            .replace(Regex("\\s+"), " ")

        val genericPhrases = listOf(
            "what did we talk about",
            "what did we discuss",
            "what was discussed",
            "what happened",
            "what was said",
            "what were we talking about",
            "show me",
            "tell me",
            "tell me about",
            "remind me",
            "remind me about",
            "recall",
            "recap",
            "can you recall",
            "can you remind me",
            "summarize",
            "summarise",
            "what",
            "how",
            "did we",
            "we talked",
            "we discussed",
        )

        // If the entire normalised string is a known generic phrase (exact or prefix), it's filler.
        return genericPhrases.any { phrase ->
            normalised == phrase || normalised.startsWith("$phrase ")
        } || normalised.length <= 4  // extremely short residuals like "me", "a", "the"
    }

    // ── Autosave flush ────────────────────────────────────────────────────────

    /**
     * If the Tier 1 token budget is nearly exhausted, embed all un-embedded
     * messages into Tier 2 and then prune the working-memory window.
     *
     * Safe to call from any coroutine — runs on [Dispatchers.IO] internally.
     */
    suspend fun checkAndFlushTier2(contextWindowTokens: Int) {
        if (!tier1.shouldTriggerAutosave(contextWindowTokens)) return
        Log.i(TAG, "Token threshold reached — flushing to Tier 2")
        flushToTier2()
    }

    /**
     * Force an immediate Tier 2 flush regardless of the token budget.
     *
     * Safety guarantee: messages are only marked as embedded (and therefore
     * eligible for pruning) when the ONNX model is loaded AND the embedding
     * actually succeeds.  If the model is absent or inference fails, messages
     * remain un-embedded in Tier 1 so no semantic-memory data is lost.
     *
     * Pruning is also guarded: it only runs when at least one message was
     * successfully embedded so that messages are never silently discarded.
     */
    suspend fun flushToTier2() = withContext(Dispatchers.IO) {
        val unembedded = tier1.unembeddedMessages()
        if (unembedded.isEmpty()) {
            Log.d(TAG, "Tier 2 flush: nothing to embed")
            return@withContext
        }

        if (!tier2.isModelLoaded) {
            Log.w(TAG, "Tier 2 flush skipped: ONNX model not loaded (messages retained in Tier 1)")
            return@withContext
        }

        // Embed each message individually and track which ones actually succeeded.
        val successIds = mutableListOf<Long>()
        val chunks     = mutableListOf<com.ernos.mobile.memory.db.ChunkEntity>()

        for (msg in unembedded) {
            val vec = tier2.embed(msg.content)
            if (vec != null) {
                successIds.add(msg.id)
                chunks.add(
                    com.ernos.mobile.memory.db.ChunkEntity(
                        text      = msg.content,
                        embedding = vec.joinToString(","),
                        sourceRef = "msg:${msg.id}",
                    )
                )
            } else {
                Log.w(TAG, "Embedding failed for message id=${msg.id} — message retained in Tier 1")
            }
        }

        if (successIds.isEmpty()) {
            Log.w(TAG, "Tier 2 flush: all embeddings failed, no messages marked or pruned")
            return@withContext
        }

        // Bulk-insert the pre-computed chunks (avoids re-running ONNX inference).
        tier2.insertChunks(chunks)
        tier1.markEmbedded(successIds)
        tier1.pruneIfNeeded()

        tier4.logEvent(
            Tier4Timeline.TYPE_MEMORY_FLUSH,
            "Flushed ${successIds.size}/${unembedded.size} messages to Tier 2",
        )
        Log.i(TAG, "Tier 2 flush: embedded ${successIds.size}/${unembedded.size} message(s)")
    }
}
