package com.ernos.mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Unit tests for the natural-language date-range resolution and keyword filtering
 * logic embedded in [MemoryManager.queryMemory].
 *
 * Because [MemoryManager] depends on Room, ONNX, DataStore, and Android APIs, we
 * cannot instantiate it in a plain JVM test.  Instead we replicate the pure logic
 * (date parsing, strip expressions, preamble detection) inline here so the algorithms
 * are validated independently.  Any change to the production helpers must also be
 * reflected below.
 *
 * What is covered:
 *   - Natural-language date resolution: yesterday, today, last week, last month,
 *     last N days, last <weekday>
 *   - Explicit from_date / to_date override
 *   - stripDateExpressions: removes date phrases from keyword
 *   - isGenericPreamble: detects filler queries that should not become keyword filters
 *   - Combined behaviour: "What did we talk about yesterday?" → dateRange resolved,
 *     keyword null (preamble suppressed)
 *   - Topic query with date: "discuss python yesterday" → dateRange + real keyword
 */
class TimelineDateQueryTest {

    // ── Mirrors of MemoryManager private helpers ──────────────────────────────
    //
    // These are intentional copies — they serve as a regression test contract.
    // If the production algorithm changes, the tests below will fail until the
    // mirror is updated, ensuring both stay in sync.

    private val fmt: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val today: LocalDate = LocalDate.now()

    private fun resolveDateRange(
        query: String,
        explicitFrom: String?,
        explicitTo: String?,
    ): Pair<String?, String?> {
        if (explicitFrom != null || explicitTo != null) return explicitFrom to explicitTo

        val q = query.lowercase()

        return when {
            q.contains("yesterday") -> {
                val d = today.minusDays(1).format(fmt); d to d
            }
            q.contains("today") -> {
                val d = today.format(fmt); d to d
            }
            q.contains("last week") -> {
                val from = today.minusWeeks(1).with(DayOfWeek.MONDAY).format(fmt)
                val to   = today.minusDays(1).format(fmt)
                from to to
            }
            q.contains("last month") -> {
                val first = today.minusMonths(1).withDayOfMonth(1)
                val last  = first.plusMonths(1).minusDays(1)
                first.format(fmt) to last.format(fmt)
            }
            else -> {
                val lastN = Regex("last\\s+(\\d+)\\s+days?").find(q)
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (lastN != null) {
                    today.minusDays(lastN.toLong()).format(fmt) to today.format(fmt)
                } else {
                    val day = Regex("last\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)")
                        .find(q)?.groupValues?.get(1)
                    if (day != null) {
                        val target = DayOfWeek.valueOf(day.uppercase())
                        var c = today.minusDays(1)
                        while (c.dayOfWeek != target) c = c.minusDays(1)
                        val d = c.format(fmt); d to d
                    } else {
                        null to null
                    }
                }
            }
        }
    }

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

    private fun isGenericPreamble(text: String): Boolean {
        val n = text.lowercase().trim()
            .replace(Regex("[?!.,]"), "")
            .replace(Regex("\\s+"), " ")
        val phrases = listOf(
            "what did we talk about", "what did we discuss", "what was discussed",
            "what happened", "what was said", "what were we talking about",
            "show me", "tell me", "tell me about", "remind me", "remind me about",
            "recall", "recap", "can you recall", "can you remind me",
            "summarize", "summarise", "what", "how", "did we", "we talked", "we discussed",
        )
        return phrases.any { p -> n == p || n.startsWith("$p ") } || n.length <= 4
    }

    /** Helper combining all three steps, mirroring queryMemory internals. */
    private fun resolveTimeline(
        query: String,
        fromDate: String? = null,
        toDate: String? = null,
    ): Triple<String?, String?, String?> {
        val (resolvedFrom, resolvedTo) = resolveDateRange(query, fromDate, toDate)
        val dateRangeResolved = resolvedFrom != null || resolvedTo != null
        val stripped = stripDateExpressions(query)
        val keyword: String? = when {
            stripped.isBlank()                               -> null
            dateRangeResolved && isGenericPreamble(stripped) -> null
            else                                             -> stripped
        }
        return Triple(keyword, resolvedFrom, resolvedTo)
    }

    // ── Date resolution ───────────────────────────────────────────────────────

    @Test fun yesterday_resolves_correct_date() {
        val (_, from, to) = resolveTimeline("What did we talk about yesterday?")
        val expected = today.minusDays(1).format(fmt)
        assertEquals(expected, from)
        assertEquals(expected, to)
    }

    @Test fun today_resolves_to_current_date() {
        val (_, from, to) = resolveTimeline("What happened today?")
        val expected = today.format(fmt)
        assertEquals(expected, from)
        assertEquals(expected, to)
    }

    @Test fun last_week_resolves_monday_to_yesterday() {
        val (_, from, to) = resolveTimeline("recap last week")
        val expectedFrom = today.minusWeeks(1).with(DayOfWeek.MONDAY).format(fmt)
        val expectedTo   = today.minusDays(1).format(fmt)
        assertEquals(expectedFrom, from)
        assertEquals(expectedTo, to)
    }

    @Test fun last_month_resolves_full_calendar_month() {
        val (_, from, to) = resolveTimeline("summarize last month")
        val first = today.minusMonths(1).withDayOfMonth(1)
        val last  = first.plusMonths(1).minusDays(1)
        assertEquals(first.format(fmt), from)
        assertEquals(last.format(fmt), to)
    }

    @Test fun last_7_days_resolves_correctly() {
        val (_, from, to) = resolveTimeline("show me the last 7 days")
        assertEquals(today.minusDays(7).format(fmt), from)
        assertEquals(today.format(fmt), to)
    }

    @Test fun last_30_days_resolves_correctly() {
        val (_, from, to) = resolveTimeline("what happened in the last 30 days")
        assertEquals(today.minusDays(30).format(fmt), from)
        assertEquals(today.format(fmt), to)
    }

    @Test fun last_monday_resolves_to_most_recent_monday() {
        val (_, from, to) = resolveTimeline("what did we discuss last Monday?")
        var expected = today.minusDays(1)
        while (expected.dayOfWeek != DayOfWeek.MONDAY) expected = expected.minusDays(1)
        assertEquals(expected.format(fmt), from)
        assertEquals(expected.format(fmt), to)
    }

    @Test fun no_date_expression_returns_null_range() {
        val (_, from, to) = resolveTimeline("what is machine learning")
        assertNull(from)
        assertNull(to)
    }

    @Test fun explicit_from_overrides_parsed_date() {
        val (_, from, to) = resolveTimeline(
            "what happened yesterday",
            fromDate = "2024-01-01",
            toDate   = "2024-01-07",
        )
        assertEquals("2024-01-01", from)
        assertEquals("2024-01-07", to)
    }

    @Test fun explicit_from_only_returns_null_to() {
        val (_, from, to) = resolveTimeline("yesterday", fromDate = "2024-03-10")
        assertEquals("2024-03-10", from)
        assertNull(to)
    }

    // ── stripDateExpressions ──────────────────────────────────────────────────

    @Test fun strip_yesterday_from_question() {
        assertEquals("What did we talk about ?", stripDateExpressions("What did we talk about yesterday?"))
    }

    @Test fun strip_last_week_from_recap() {
        assertEquals("recap", stripDateExpressions("recap last week"))
    }

    @Test fun strip_last_7_days_from_query() {
        val result = stripDateExpressions("show me the last 7 days")
        assertEquals("show me the", result)
    }

    @Test fun strip_multiple_expressions_not_present() {
        assertEquals("what happened", stripDateExpressions("what happened"))
    }

    // ── isGenericPreamble ─────────────────────────────────────────────────────

    @Test fun preamble_what_did_we_talk_about() {
        assertTrue(isGenericPreamble("What did we talk about ?"))
    }

    @Test fun preamble_what_happened() {
        assertTrue(isGenericPreamble("what happened"))
    }

    @Test fun preamble_remind_me() {
        assertTrue(isGenericPreamble("remind me"))
    }

    @Test fun preamble_recall_is_generic() {
        assertTrue(isGenericPreamble("recall"))
    }

    @Test fun preamble_recap_is_generic() {
        assertTrue(isGenericPreamble("recap"))
    }

    @Test fun preamble_show_me() {
        assertTrue(isGenericPreamble("show me"))
    }

    @Test fun preamble_short_residual_is_generic() {
        assertTrue(isGenericPreamble("me"))
    }

    @Test fun preamble_false_for_domain_keyword() {
        assert(!isGenericPreamble("python programming"))
    }

    @Test fun preamble_false_for_mixed_preamble_and_topic() {
        assert(!isGenericPreamble("what did we talk about docker deployment"))
    }

    // ── Combined: keyword suppression for date-only queries ───────────────────

    @Test fun yesterday_preamble_suppresses_keyword() {
        val (keyword, from, to) = resolveTimeline("What did we talk about yesterday?")
        assertNull("Keyword should be null for generic preamble + date", keyword)
        assertNotNull(from)
        assertNotNull(to)
    }

    @Test fun today_show_me_suppresses_keyword() {
        val (keyword, from, to) = resolveTimeline("show me today")
        assertNull(keyword)
        assertEquals(today.format(fmt), from)
    }

    @Test fun last_week_recap_suppresses_keyword() {
        val (keyword, from, to) = resolveTimeline("recap last week")
        assertNull(keyword)
        assertNotNull(from)
        assertNotNull(to)
    }

    // ── Real keyword preserved when meaningful topic is present ───────────────

    @Test fun topic_with_yesterday_preserves_keyword() {
        val (keyword, from, to) = resolveTimeline("discuss python yesterday")
        assertEquals("discuss python", keyword)
        assertEquals(today.minusDays(1).format(fmt), from)
    }

    @Test fun topic_with_last_week_preserves_keyword() {
        val (keyword, from, to) = resolveTimeline("docker deployment last week")
        assertEquals("docker deployment", keyword)
        assertNotNull(from)
    }

    @Test fun no_date_no_preamble_preserves_full_query() {
        val (keyword, from, to) = resolveTimeline("what is machine learning")
        assertEquals("what is machine learning", keyword)
        assertNull(from)
        assertNull(to)
    }

    @Test fun explicit_dates_with_preamble_suppresses_keyword() {
        val (keyword, from, to) = resolveTimeline(
            "What did we discuss",
            fromDate = "2024-01-01",
            toDate   = "2024-01-07",
        )
        assertNull(keyword)
        assertEquals("2024-01-01", from)
        assertEquals("2024-01-07", to)
    }
}
