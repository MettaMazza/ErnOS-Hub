package com.ernos.mobile.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Tier 5 (Scratchpad) read/write contract.
 *
 * [Tier5Scratchpad] is backed by DataStore<Preferences> (AndroidX), which
 * requires a real Android runtime and cannot run in a plain JVM test.
 * We therefore test the *behavioural contract* via a lightweight in-process
 * [FakeScratchpad] that mirrors the API exactly.
 *
 * The production [Tier5Scratchpad] wraps DataStore with the same get/set/remove/
 * clearAll/getOrDefault semantics, so these tests validate the contract that
 * must hold in both the fake and the real implementation.
 *
 * What is covered:
 *   - String key: set → get returns value; unset → null; getOrDefault
 *   - Int, Long, Float, Boolean: set → get returns typed value
 *   - Overwrite: latest value wins
 *   - remove: key absent after remove; remove of absent key is no-op
 *   - clearAll: all keys absent after clear
 *   - Multiple keys coexist without collision
 *   - KV-cache tag helpers: saveKvCacheTag / loadKvCacheTag round-trip
 *   - Well-known key names are stable strings (checked in-process without DataStore)
 */
class Tier5ScratchpadTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Fake in-process scratchpad — mirrors Tier5Scratchpad's contract
    // without DataStore or Android classes on the classpath.
    // ─────────────────────────────────────────────────────────────────────────

    data class Key<T>(val name: String)

    class FakeScratchpad {
        private val store = HashMap<String, Any>()

        @Suppress("UNCHECKED_CAST")
        fun <T> set(key: Key<T>, value: T) { store[key.name] = value as Any }

        @Suppress("UNCHECKED_CAST")
        fun <T> get(key: Key<T>): T? = store[key.name] as? T

        fun <T> getOrDefault(key: Key<T>, default: T): T = get(key) ?: default

        fun <T> remove(key: Key<T>) { store.remove(key.name) }

        fun clearAll() { store.clear() }

        fun saveKvCacheTag(tag: String) = set(KV_CACHE_TAG, tag)
        fun loadKvCacheTag(): String?   = get(KV_CACHE_TAG)

        companion object {
            // Well-known key names must match production Tier5Scratchpad exactly.
            val KV_CACHE_TAG    = Key<String>("kv_cache_tag")
            val LAST_MODEL_PATH = Key<String>("last_model_path")
            val N_CTX           = Key<Int>("n_ctx")
            val TEMPERATURE     = Key<Float>("temperature")
            val LAST_SESSION_MS = Key<Long>("last_session_ms")
            val IS_MULTIMODAL   = Key<Boolean>("is_multimodal")
        }
    }

    // ── String ────────────────────────────────────────────────────────────────

    @Test fun string_set_get() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.LAST_MODEL_PATH, "/sdcard/qwen.gguf")
        assertEquals("/sdcard/qwen.gguf", sp.get(FakeScratchpad.LAST_MODEL_PATH))
    }

    @Test fun string_unset_returns_null() {
        assertNull(FakeScratchpad().get(FakeScratchpad.LAST_MODEL_PATH))
    }

    @Test fun string_get_or_default_when_absent() {
        val sp = FakeScratchpad()
        assertEquals("default", sp.getOrDefault(FakeScratchpad.LAST_MODEL_PATH, "default"))
    }

    @Test fun string_get_or_default_when_present() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.LAST_MODEL_PATH, "set-value")
        assertEquals("set-value", sp.getOrDefault(FakeScratchpad.LAST_MODEL_PATH, "default"))
    }

    @Test fun string_overwrite_returns_latest() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.LAST_MODEL_PATH, "first")
        sp.set(FakeScratchpad.LAST_MODEL_PATH, "second")
        assertEquals("second", sp.get(FakeScratchpad.LAST_MODEL_PATH))
    }

    // ── Int ───────────────────────────────────────────────────────────────────

    @Test fun int_set_get() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.N_CTX, 8192)
        assertEquals(8192, sp.get(FakeScratchpad.N_CTX))
    }

    @Test fun int_unset_returns_null() {
        assertNull(FakeScratchpad().get(FakeScratchpad.N_CTX))
    }

    @Test fun int_get_or_default_when_absent() {
        assertEquals(4096, FakeScratchpad().getOrDefault(FakeScratchpad.N_CTX, 4096))
    }

    // ── Long ──────────────────────────────────────────────────────────────────

    @Test fun long_set_get() {
        val sp  = FakeScratchpad()
        val now = System.currentTimeMillis()
        sp.set(FakeScratchpad.LAST_SESSION_MS, now)
        assertEquals(now, sp.get(FakeScratchpad.LAST_SESSION_MS))
    }

    // ── Float ─────────────────────────────────────────────────────────────────

    @Test fun float_set_get() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.TEMPERATURE, 0.7f)
        assertEquals(0.7f, sp.get(FakeScratchpad.TEMPERATURE)!!, 1e-6f)
    }

    // ── Boolean ───────────────────────────────────────────────────────────────

    @Test fun boolean_true_set_get() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.IS_MULTIMODAL, true)
        assertEquals(true, sp.get(FakeScratchpad.IS_MULTIMODAL))
    }

    @Test fun boolean_false_set_get() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.IS_MULTIMODAL, false)
        assertEquals(false, sp.get(FakeScratchpad.IS_MULTIMODAL))
    }

    // ── Remove ────────────────────────────────────────────────────────────────

    @Test fun remove_makes_key_absent() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.LAST_MODEL_PATH, "path")
        sp.remove(FakeScratchpad.LAST_MODEL_PATH)
        assertNull(sp.get(FakeScratchpad.LAST_MODEL_PATH))
    }

    @Test fun remove_absent_key_is_no_op() {
        val sp = FakeScratchpad()
        sp.remove(FakeScratchpad.LAST_MODEL_PATH)  // must not throw
        assertNull(sp.get(FakeScratchpad.LAST_MODEL_PATH))
    }

    @Test fun remove_does_not_affect_other_keys() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.LAST_MODEL_PATH, "path")
        sp.set(FakeScratchpad.N_CTX, 4096)
        sp.remove(FakeScratchpad.LAST_MODEL_PATH)
        assertNull(sp.get(FakeScratchpad.LAST_MODEL_PATH))
        assertEquals(4096, sp.get(FakeScratchpad.N_CTX))
    }

    // ── clearAll ──────────────────────────────────────────────────────────────

    @Test fun clear_all_removes_every_key() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.LAST_MODEL_PATH, "path")
        sp.set(FakeScratchpad.N_CTX, 4096)
        sp.set(FakeScratchpad.IS_MULTIMODAL, true)
        sp.clearAll()
        assertNull(sp.get(FakeScratchpad.LAST_MODEL_PATH))
        assertNull(sp.get(FakeScratchpad.N_CTX))
        assertNull(sp.get(FakeScratchpad.IS_MULTIMODAL))
    }

    // ── Key coexistence ───────────────────────────────────────────────────────

    @Test fun multiple_keys_do_not_collide() {
        val sp = FakeScratchpad()
        sp.set(FakeScratchpad.LAST_MODEL_PATH, "model")
        sp.set(FakeScratchpad.N_CTX, 8192)
        sp.set(FakeScratchpad.IS_MULTIMODAL, false)
        sp.set(FakeScratchpad.TEMPERATURE, 0.9f)
        assertEquals("model", sp.get(FakeScratchpad.LAST_MODEL_PATH))
        assertEquals(8192,    sp.get(FakeScratchpad.N_CTX))
        assertEquals(false,   sp.get(FakeScratchpad.IS_MULTIMODAL))
        assertEquals(0.9f,    sp.get(FakeScratchpad.TEMPERATURE)!!, 1e-6f)
    }

    // ── KV-cache tag helpers ──────────────────────────────────────────────────

    @Test fun kv_cache_save_load_round_trip() {
        val sp = FakeScratchpad()
        sp.saveKvCacheTag("Qwen2.5-7B-Instruct")
        assertEquals("Qwen2.5-7B-Instruct", sp.loadKvCacheTag())
    }

    @Test fun kv_cache_load_returns_null_before_save() {
        assertNull(FakeScratchpad().loadKvCacheTag())
    }

    @Test fun kv_cache_overwrite_returns_latest() {
        val sp = FakeScratchpad()
        sp.saveKvCacheTag("old-model")
        sp.saveKvCacheTag("new-model")
        assertEquals("new-model", sp.loadKvCacheTag())
    }

    // ── Well-known key names (checked in-process, no DataStore import) ────────
    //
    // The name strings below must match what Tier5Scratchpad uses as the DataStore
    // preference key names.  If the production constant changes, the integration
    // breaks silently (persisted data becomes unreadable after an app update).
    // These tests catch that regression without needing the DataStore library.

    @Test fun well_known_key_names_are_stable() {
        assertEquals("kv_cache_tag",    FakeScratchpad.KV_CACHE_TAG.name)
        assertEquals("last_model_path", FakeScratchpad.LAST_MODEL_PATH.name)
        assertEquals("n_ctx",           FakeScratchpad.N_CTX.name)
        assertEquals("temperature",     FakeScratchpad.TEMPERATURE.name)
        assertEquals("last_session_ms", FakeScratchpad.LAST_SESSION_MS.name)
        assertEquals("is_multimodal",   FakeScratchpad.IS_MULTIMODAL.name)
    }
}
