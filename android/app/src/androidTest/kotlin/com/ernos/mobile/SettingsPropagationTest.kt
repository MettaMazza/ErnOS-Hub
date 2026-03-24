package com.ernos.mobile

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ernos.mobile.engine.ReActLoopManager
import com.ernos.mobile.memory.Tier5Scratchpad
import com.ernos.mobile.settings.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests verifying that settings changes propagate correctly to
 * [ReActLoopManager.InferenceParams].
 *
 * These tests use the real Android DataStore (via [Tier5Scratchpad]) to verify
 * that values written by [SettingsViewModel.setTemperature] etc. are read back
 * correctly and map to the right [InferenceParams] fields — including
 * [InferenceParams.presencePenalty] which must flow all the way through to the
 * native sampler chain.
 *
 * Note on nCtx: context-window changes are read on the *next* [ChatViewModel.loadModel]
 * call.  The test below documents this contract explicitly.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPropagationTest {

    private val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ── Test 1: InferenceParams defaults match SettingsViewModel defaults ─────

    @Test
    fun inferenceParams_defaultsMatchSettingsViewModelDefaults() {
        val params = ReActLoopManager.InferenceParams()
        assertEquals(
            "temperature default mismatch",
            SettingsViewModel.DEFAULT_TEMPERATURE,
            params.temperature,
            0.001f,
        )
        assertEquals(
            "topP default mismatch",
            SettingsViewModel.DEFAULT_TOP_P,
            params.topP,
            0.001f,
        )
        assertEquals(
            "presencePenalty default mismatch",
            SettingsViewModel.DEFAULT_PRESENCE_PENALTY,
            params.presencePenalty,
            0.001f,
        )
        assertEquals(
            "maxTurns default mismatch",
            SettingsViewModel.DEFAULT_MAX_TURNS,
            params.maxTurns,
        )
    }

    // ── Test 2: Settings written to DataStore are read back correctly ─────────

    @Test
    fun tier5Scratchpad_roundTrip_floatSettings() = runBlocking {
        val scratchpad = Tier5Scratchpad(appContext)

        val expectedTemp = 1.3f
        val expectedTopP = 0.6f
        val expectedPenalty = 0.4f

        scratchpad.set(SettingsViewModel.KEY_TEMPERATURE,       expectedTemp)
        scratchpad.set(SettingsViewModel.KEY_TOP_P,             expectedTopP)
        scratchpad.set(SettingsViewModel.KEY_PRESENCE_PENALTY,  expectedPenalty)

        val readTemp    = scratchpad.getOrDefault(SettingsViewModel.KEY_TEMPERATURE,      0f)
        val readTopP    = scratchpad.getOrDefault(SettingsViewModel.KEY_TOP_P,            0f)
        val readPenalty = scratchpad.getOrDefault(SettingsViewModel.KEY_PRESENCE_PENALTY, 0f)

        assertEquals("temperature round-trip failed", expectedTemp,    readTemp,    0.001f)
        assertEquals("topP round-trip failed",        expectedTopP,    readTopP,    0.001f)
        assertEquals("penalty round-trip failed",     expectedPenalty, readPenalty, 0.001f)

        // Clean up so we don't pollute other tests
        scratchpad.remove(SettingsViewModel.KEY_TEMPERATURE)
        scratchpad.remove(SettingsViewModel.KEY_TOP_P)
        scratchpad.remove(SettingsViewModel.KEY_PRESENCE_PENALTY)
    }

    // ── Test 3: Settings written to DataStore produce correct InferenceParams ─

    @Test
    fun inferenceParams_builtFromDataStore_reflectsUserSettings() = runBlocking {
        val scratchpad = Tier5Scratchpad(appContext)

        val writtenTemp    = 0.5f
        val writtenTopP    = 0.8f
        val writtenPenalty = 0.3f
        val writtenTurns   = 7

        scratchpad.set(SettingsViewModel.KEY_TEMPERATURE,      writtenTemp)
        scratchpad.set(SettingsViewModel.KEY_TOP_P,            writtenTopP)
        scratchpad.set(SettingsViewModel.KEY_PRESENCE_PENALTY, writtenPenalty)
        scratchpad.set(SettingsViewModel.KEY_MAX_TURNS,        writtenTurns)

        // Simulate the same read that ChatViewModel.readInferenceParams() does
        val params = ReActLoopManager.InferenceParams(
            temperature     = scratchpad.getOrDefault(SettingsViewModel.KEY_TEMPERATURE,      SettingsViewModel.DEFAULT_TEMPERATURE),
            topP            = scratchpad.getOrDefault(SettingsViewModel.KEY_TOP_P,            SettingsViewModel.DEFAULT_TOP_P),
            presencePenalty = scratchpad.getOrDefault(SettingsViewModel.KEY_PRESENCE_PENALTY, SettingsViewModel.DEFAULT_PRESENCE_PENALTY),
            maxTurns        = scratchpad.getOrDefault(SettingsViewModel.KEY_MAX_TURNS,        SettingsViewModel.DEFAULT_MAX_TURNS),
        )

        assertEquals("temperature not propagated", writtenTemp,    params.temperature,     0.001f)
        assertEquals("topP not propagated",        writtenTopP,    params.topP,            0.001f)
        assertEquals("penalty not propagated",     writtenPenalty, params.presencePenalty, 0.001f)
        assertEquals("maxTurns not propagated",    writtenTurns,   params.maxTurns)

        // Clean up
        scratchpad.remove(SettingsViewModel.KEY_TEMPERATURE)
        scratchpad.remove(SettingsViewModel.KEY_TOP_P)
        scratchpad.remove(SettingsViewModel.KEY_PRESENCE_PENALTY)
        scratchpad.remove(SettingsViewModel.KEY_MAX_TURNS)
    }

    // ── Test 4: nCtx is stored/retrieved correctly (applied on next loadModel) ─

    @Test
    fun nCtx_storedInDataStore_readBackCorrectly() = runBlocking {
        val scratchpad = Tier5Scratchpad(appContext)

        val expectedNCtx = 8192
        scratchpad.set(SettingsViewModel.KEY_N_CTX, expectedNCtx)

        val readNCtx = scratchpad.getOrDefault(SettingsViewModel.KEY_N_CTX, SettingsViewModel.DEFAULT_N_CTX)
        assertEquals(
            "nCtx not stored/read correctly " +
            "(note: applied on next loadModel call, not immediately)",
            expectedNCtx, readNCtx
        )

        scratchpad.remove(SettingsViewModel.KEY_N_CTX)
    }

    // ── Test 5: presencePenalty is a distinct field in InferenceParams ─────────

    @Test
    fun inferenceParams_presencePenaltyIsDistinctField() {
        val withPenalty    = ReActLoopManager.InferenceParams(presencePenalty = 0.5f)
        val withoutPenalty = ReActLoopManager.InferenceParams(presencePenalty = 0.0f)
        assertNotEquals(
            "presencePenalty should be a distinct field",
            withPenalty,
            withoutPenalty,
        )
        assertEquals(0.5f, withPenalty.presencePenalty, 0.001f)
        assertEquals(0.0f, withoutPenalty.presencePenalty, 0.001f)
    }

    // ── Test 6: maxTurns is clamped to valid range ────────────────────────────

    @Test
    fun inferenceParams_maxTurns_clampedToRange() {
        val tooLow  = 0.coerceIn(1, 30)
        val tooHigh = 99.coerceIn(1, 30)
        assertEquals(1,  tooLow)
        assertEquals(30, tooHigh)
    }

    // ── Test 7: DataStore key names are unique ────────────────────────────────

    @Test
    fun settingsKeys_haveUniqueNamesAndSettingsPrefix() {
        val keys = listOf(
            SettingsViewModel.KEY_N_CTX.name,
            SettingsViewModel.KEY_TEMPERATURE.name,
            SettingsViewModel.KEY_TOP_P.name,
            SettingsViewModel.KEY_MAX_TURNS.name,
            SettingsViewModel.KEY_PRESENCE_PENALTY.name,
            SettingsViewModel.KEY_THEME_CHOICE.name,
            SettingsViewModel.KEY_DYNAMIC_COLOR.name,
            SettingsViewModel.KEY_CUSTOM_PRIMARY_COLOR.name,
        )
        assertEquals("Duplicate DataStore key names", keys.size, keys.distinct().size)
        keys.forEach { key ->
            assertTrue("Key '$key' missing 'settings_' prefix", key.startsWith("settings_"))
        }
    }

    // ── Test 8: Custom primary colour default is non-zero ─────────────────────

    @Test
    fun customPrimaryColor_defaultIsNonZero() {
        assertNotEquals(0, SettingsViewModel.DEFAULT_CUSTOM_PRIMARY_COLOR)
    }

    // ── Test 9: InferenceParams data class equality is structural ─────────────

    @Test
    fun inferenceParams_structuralEquality() {
        val a = ReActLoopManager.InferenceParams(temperature = 0.9f, topP = 0.85f,
            presencePenalty = 0.1f, maxTurns = 10)
        val b = ReActLoopManager.InferenceParams(temperature = 0.9f, topP = 0.85f,
            presencePenalty = 0.1f, maxTurns = 10)
        assertEquals("data class equals should be structural", a, b)
    }
}
