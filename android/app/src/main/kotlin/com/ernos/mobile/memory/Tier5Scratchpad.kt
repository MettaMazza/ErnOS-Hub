package com.ernos.mobile.memory

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Top-level extension creates a single DataStore<Preferences> instance per process.
private val Context.scratchpadDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "ernos_scratchpad")

/**
 * Tier 5 — Scratchpad
 *
 * Lightweight, typed key-value store backed by `DataStore<Preferences>`.
 * Ideal for:
 *   - Per-session state (KV-cache tag, last model path, preference flags)
 *   - Model-generated scratchpad notes
 *   - Feature flags and runtime overrides
 *
 * All operations are suspend functions that safely cross the Main/IO boundary.
 * Reads return [Flow] for reactive observation OR an immediate suspend value
 * via [get] / [getOrDefault].
 *
 * Type support: String, Int, Long, Float, Boolean.
 * For arbitrary objects, serialise to JSON and store as String.
 */
class Tier5Scratchpad(private val context: Context) {

    companion object {
        private const val TAG = "Tier5Scratchpad"

        // ── Well-known keys ───────────────────────────────────────────────────
        val KEY_LAST_MODEL_PATH  = stringPreferencesKey("last_model_path")
        val KEY_LAST_SESSION_MS  = longPreferencesKey("last_session_ms")
        val KEY_KV_CACHE_TAG     = stringPreferencesKey("kv_cache_tag")
        val KEY_N_CTX            = intPreferencesKey("n_ctx")
        val KEY_TEMPERATURE      = floatPreferencesKey("temperature")
        val KEY_TOP_P            = floatPreferencesKey("top_p")
        val KEY_IS_MULTIMODAL    = booleanPreferencesKey("is_multimodal")
    }

    private val ds = context.scratchpadDataStore

    // ── Generic typed accessors ───────────────────────────────────────────────

    /** Write any supported preference value. */
    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        ds.edit { prefs -> prefs[key] = value }
        Log.v(TAG, "set[${key.name}] = $value")
    }

    /** Read any supported preference value (returns null if unset). */
    suspend fun <T> get(key: Preferences.Key<T>): T? =
        ds.data.first()[key]

    /** Observe any supported preference value reactively. */
    fun <T> observe(key: Preferences.Key<T>): Flow<T?> =
        ds.data.map { it[key] }

    /** Read any supported preference value, returning [default] if unset. */
    suspend fun <T> getOrDefault(key: Preferences.Key<T>, default: T): T =
        ds.data.first()[key] ?: default

    // ── Generic remove ────────────────────────────────────────────────────────

    suspend fun remove(key: Preferences.Key<*>) {
        ds.edit { prefs -> prefs.remove(key) }
        Log.v(TAG, "removed[${key.name}]")
    }

    suspend fun clearAll() {
        ds.edit { it.clear() }
        Log.i(TAG, "Scratchpad cleared")
    }

    // ── KV-cache state helpers ────────────────────────────────────────────────

    /** Persist the current KV-cache tag so it survives app restart. */
    suspend fun saveKvCacheTag(tag: String) = set(KEY_KV_CACHE_TAG, tag)

    /** Retrieve the last saved KV-cache tag.  Returns null if not yet set. */
    suspend fun loadKvCacheTag(): String? = get(KEY_KV_CACHE_TAG)
}
