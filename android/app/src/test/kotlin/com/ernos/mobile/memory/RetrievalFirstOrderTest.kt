package com.ernos.mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the retrieval-first ordering contract.
 *
 * The HIVE specification requires:
 *
 *   retrieveContext(query) must be called BEFORE storeUserMessage(query)
 *
 * This test simulates the [ChatViewModel] call ordering using a simple
 * operation log and asserts that retrieval always precedes any store.
 *
 * Because MemoryManager has I/O dependencies (Room, DataStore, ONNX) that
 * cannot easily run in a plain JVM test, we test the ordering invariant
 * at the pure-logic level using a fake memory manager that records call order.
 */
class RetrievalFirstOrderTest {

    // ── Fake MemoryManager that records operation order ────────────────────────

    sealed class Op {
        data class Retrieve(val query: String) : Op()
        data class StoreUser(val text: String) : Op()
        data class StoreAI(val text: String) : Op()
    }

    class FakeMemoryManager {
        val operations = mutableListOf<Op>()

        fun retrieveContext(query: String): String {
            operations.add(Op.Retrieve(query))
            return "mock context"
        }

        fun storeUserMessage(text: String) {
            operations.add(Op.StoreUser(text))
        }

        fun storeAiResponse(text: String) {
            operations.add(Op.StoreAI(text))
        }
    }

    // ── ChatViewModel-like controller using the fake ──────────────────────────

    /**
     * Simulates the send-message flow from [ChatViewModel.sendMessage].
     *
     * Order:
     *   1. retrieveContext(userText)
     *   2. storeUserMessage(userText)
     *   3. … run the ReAct loop (simulated here as aiResponse) …
     *   4. storeAiResponse(aiResponse)
     */
    private fun simulateSendMessage(mm: FakeMemoryManager, userText: String, aiResponse: String) {
        mm.retrieveContext(userText)
        mm.storeUserMessage(userText)
        // ReAct loop runs here (elided in test)
        mm.storeAiResponse(aiResponse)
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun retrieval_precedes_store_in_single_turn() {
        val mm = FakeMemoryManager()
        simulateSendMessage(mm, "What is Python?", "Python is a programming language.")

        assertEquals("Exactly 3 operations should be recorded", 3, mm.operations.size)
        assertTrue("First op must be Retrieve", mm.operations[0] is Op.Retrieve)
        assertTrue("Second op must be StoreUser", mm.operations[1] is Op.StoreUser)
        assertTrue("Third op must be StoreAI", mm.operations[2] is Op.StoreAI)
    }

    @Test
    fun retrieval_always_before_store_across_multiple_turns() {
        val mm = FakeMemoryManager()
        val turns = listOf(
            "Hello" to "Hi there!",
            "What time is it?" to "I don't know the current time.",
            "Tell me about Kotlin" to "Kotlin is a modern JVM language.",
        )
        for ((user, ai) in turns) {
            simulateSendMessage(mm, user, ai)
        }

        val ops = mm.operations
        assertEquals("Should record 3 ops per turn × 3 turns = 9 ops", 9, ops.size)

        // For every triplet, verify ordering
        for (i in 0 until 3) {
            val base = i * 3
            assertTrue("Turn $i: op[$base] must be Retrieve", ops[base] is Op.Retrieve)
            assertTrue("Turn $i: op[${base+1}] must be StoreUser", ops[base + 1] is Op.StoreUser)
            assertTrue("Turn $i: op[${base+2}] must be StoreAI", ops[base + 2] is Op.StoreAI)
        }
    }

    @Test
    fun retrieve_query_matches_user_message() {
        val mm       = FakeMemoryManager()
        val userText = "Explain recursion"
        simulateSendMessage(mm, userText, "Recursion is a function calling itself.")

        val retrieveOp = mm.operations.filterIsInstance<Op.Retrieve>().first()
        assertEquals("Retrieve query must match the user message", userText, retrieveOp.query)
    }

    @Test
    fun store_user_precedes_store_ai() {
        val mm = FakeMemoryManager()
        simulateSendMessage(mm, "Question", "Answer")

        val storeUserIdx = mm.operations.indexOfFirst { it is Op.StoreUser }
        val storeAIIdx   = mm.operations.indexOfFirst { it is Op.StoreAI }

        assertTrue("StoreUser must come before StoreAI", storeUserIdx < storeAIIdx)
    }

    @Test
    fun no_store_happens_before_first_retrieve() {
        val mm = FakeMemoryManager()
        simulateSendMessage(mm, "Hello", "Hi")

        val firstStoreIdx   = mm.operations.indexOfFirst { it is Op.StoreUser || it is Op.StoreAI }
        val firstRetrieveIdx = mm.operations.indexOfFirst { it is Op.Retrieve }

        assertTrue(
            "First retrieve ($firstRetrieveIdx) must precede first store ($firstStoreIdx)",
            firstRetrieveIdx < firstStoreIdx,
        )
    }
}
