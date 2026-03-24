package com.ernos.mobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SettingsViewModel logic.
 *
 * [SettingsViewModel] depends on Android Application context and DataStore
 * which cannot run in a plain JVM test.  We therefore test the pure
 * validation/coercion logic and default-value contract using a
 * [FakeSettings] that mirrors the same rules.
 *
 * Covered:
 *   - Default values match the spec
 *   - nCtx coercion: below min → 512, above max → 16384
 *   - Temperature coercion: below 0 → 0.0, above 2.0 → 2.0
 *   - TopP coercion: below 0 → 0.0, above 1.0 → 1.0
 *   - MaxTurns coercion: below 1 → 1, above 30 → 30
 *   - PresencePenalty coercion: below 0 → 0.0, above 2.0 → 2.0
 *   - ThemeChoice: set/get round-trip for valid values
 *   - DynamicColor: toggle
 *   - resetToDefaults restores all values
 */
class SettingsViewModelTest {

    // ── Fake that mirrors SettingsViewModel without Android ───────────────────

    class FakeSettings {
        var nCtx            = SettingsViewModel.DEFAULT_N_CTX
        var temperature     = SettingsViewModel.DEFAULT_TEMPERATURE
        var topP            = SettingsViewModel.DEFAULT_TOP_P
        var maxTurns        = SettingsViewModel.DEFAULT_MAX_TURNS
        var presencePenalty = SettingsViewModel.DEFAULT_PRESENCE_PENALTY
        var themeChoice     = SettingsViewModel.DEFAULT_THEME_CHOICE
        var dynamicColor    = SettingsViewModel.DEFAULT_DYNAMIC_COLOR

        fun setNCtx(value: Int)            { nCtx            = value.coerceIn(512, 16384) }
        fun setTemperature(value: Float)   { temperature     = value.coerceIn(0f, 2f) }
        fun setTopP(value: Float)          { topP            = value.coerceIn(0f, 1f) }
        fun setMaxTurns(value: Int)        { maxTurns        = value.coerceIn(1, 30) }
        fun setPresencePenalty(value: Float) { presencePenalty = value.coerceIn(0f, 2f) }
        fun setThemeChoice(value: String)  { themeChoice     = value }
        fun setDynamicColor(value: Boolean){ dynamicColor    = value }

        fun resetToDefaults() {
            setNCtx(SettingsViewModel.DEFAULT_N_CTX)
            setTemperature(SettingsViewModel.DEFAULT_TEMPERATURE)
            setTopP(SettingsViewModel.DEFAULT_TOP_P)
            setMaxTurns(SettingsViewModel.DEFAULT_MAX_TURNS)
            setPresencePenalty(SettingsViewModel.DEFAULT_PRESENCE_PENALTY)
            setThemeChoice(SettingsViewModel.DEFAULT_THEME_CHOICE)
            setDynamicColor(SettingsViewModel.DEFAULT_DYNAMIC_COLOR)
        }
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test fun default_n_ctx_is_4096() {
        assertEquals(4096, SettingsViewModel.DEFAULT_N_CTX)
    }

    @Test fun default_temperature_is_0_7() {
        assertEquals(0.7f, SettingsViewModel.DEFAULT_TEMPERATURE, 1e-6f)
    }

    @Test fun default_top_p_is_0_9() {
        assertEquals(0.9f, SettingsViewModel.DEFAULT_TOP_P, 1e-6f)
    }

    @Test fun default_max_turns_is_15() {
        assertEquals(15, SettingsViewModel.DEFAULT_MAX_TURNS)
    }

    @Test fun default_presence_penalty_is_0() {
        assertEquals(0.0f, SettingsViewModel.DEFAULT_PRESENCE_PENALTY, 1e-6f)
    }

    @Test fun default_theme_choice_is_system() {
        assertEquals("system", SettingsViewModel.DEFAULT_THEME_CHOICE)
    }

    @Test fun default_dynamic_color_is_true() {
        assertTrue(SettingsViewModel.DEFAULT_DYNAMIC_COLOR)
    }

    // ── nCtx coercion ─────────────────────────────────────────────────────────

    @Test fun n_ctx_below_min_clamps_to_512() {
        val s = FakeSettings()
        s.setNCtx(0)
        assertEquals(512, s.nCtx)
    }

    @Test fun n_ctx_above_max_clamps_to_16384() {
        val s = FakeSettings()
        s.setNCtx(100_000)
        assertEquals(16384, s.nCtx)
    }

    @Test fun n_ctx_valid_value_set_exactly() {
        val s = FakeSettings()
        s.setNCtx(8192)
        assertEquals(8192, s.nCtx)
    }

    @Test fun n_ctx_min_boundary_accepted() {
        val s = FakeSettings()
        s.setNCtx(512)
        assertEquals(512, s.nCtx)
    }

    @Test fun n_ctx_max_boundary_accepted() {
        val s = FakeSettings()
        s.setNCtx(16384)
        assertEquals(16384, s.nCtx)
    }

    // ── Temperature coercion ──────────────────────────────────────────────────

    @Test fun temperature_below_zero_clamps_to_zero() {
        val s = FakeSettings()
        s.setTemperature(-0.5f)
        assertEquals(0f, s.temperature, 1e-6f)
    }

    @Test fun temperature_above_2_clamps_to_2() {
        val s = FakeSettings()
        s.setTemperature(3.0f)
        assertEquals(2.0f, s.temperature, 1e-6f)
    }

    @Test fun temperature_valid_value_set_exactly() {
        val s = FakeSettings()
        s.setTemperature(1.5f)
        assertEquals(1.5f, s.temperature, 1e-6f)
    }

    // ── TopP coercion ─────────────────────────────────────────────────────────

    @Test fun top_p_below_zero_clamps_to_zero() {
        val s = FakeSettings()
        s.setTopP(-1f)
        assertEquals(0f, s.topP, 1e-6f)
    }

    @Test fun top_p_above_1_clamps_to_1() {
        val s = FakeSettings()
        s.setTopP(2f)
        assertEquals(1f, s.topP, 1e-6f)
    }

    @Test fun top_p_valid_value_set_exactly() {
        val s = FakeSettings()
        s.setTopP(0.85f)
        assertEquals(0.85f, s.topP, 1e-6f)
    }

    // ── MaxTurns coercion ─────────────────────────────────────────────────────

    @Test fun max_turns_below_1_clamps_to_1() {
        val s = FakeSettings()
        s.setMaxTurns(0)
        assertEquals(1, s.maxTurns)
    }

    @Test fun max_turns_above_30_clamps_to_30() {
        val s = FakeSettings()
        s.setMaxTurns(999)
        assertEquals(30, s.maxTurns)
    }

    @Test fun max_turns_valid_value_set_exactly() {
        val s = FakeSettings()
        s.setMaxTurns(10)
        assertEquals(10, s.maxTurns)
    }

    // ── PresencePenalty coercion ──────────────────────────────────────────────

    @Test fun presence_penalty_below_zero_clamps_to_zero() {
        val s = FakeSettings()
        s.setPresencePenalty(-1f)
        assertEquals(0f, s.presencePenalty, 1e-6f)
    }

    @Test fun presence_penalty_above_2_clamps_to_2() {
        val s = FakeSettings()
        s.setPresencePenalty(5f)
        assertEquals(2f, s.presencePenalty, 1e-6f)
    }

    @Test fun presence_penalty_valid_value_set_exactly() {
        val s = FakeSettings()
        s.setPresencePenalty(1.2f)
        assertEquals(1.2f, s.presencePenalty, 1e-6f)
    }

    // ── ThemeChoice ───────────────────────────────────────────────────────────

    @Test fun theme_choice_dark_round_trip() {
        val s = FakeSettings()
        s.setThemeChoice("dark")
        assertEquals("dark", s.themeChoice)
    }

    @Test fun theme_choice_light_round_trip() {
        val s = FakeSettings()
        s.setThemeChoice("light")
        assertEquals("light", s.themeChoice)
    }

    @Test fun theme_choice_system_round_trip() {
        val s = FakeSettings()
        s.setThemeChoice("system")
        assertEquals("system", s.themeChoice)
    }

    // ── DynamicColor toggle ───────────────────────────────────────────────────

    @Test fun dynamic_color_toggle_false() {
        val s = FakeSettings()
        s.setDynamicColor(false)
        assertEquals(false, s.dynamicColor)
    }

    @Test fun dynamic_color_toggle_back_to_true() {
        val s = FakeSettings()
        s.setDynamicColor(false)
        s.setDynamicColor(true)
        assertEquals(true, s.dynamicColor)
    }

    // ── resetToDefaults ───────────────────────────────────────────────────────

    @Test fun reset_restores_all_defaults() {
        val s = FakeSettings()
        s.setNCtx(2048)
        s.setTemperature(1.8f)
        s.setTopP(0.5f)
        s.setMaxTurns(5)
        s.setPresencePenalty(1.5f)
        s.setThemeChoice("dark")
        s.setDynamicColor(false)

        s.resetToDefaults()

        assertEquals(SettingsViewModel.DEFAULT_N_CTX,            s.nCtx)
        assertEquals(SettingsViewModel.DEFAULT_TEMPERATURE,      s.temperature, 1e-6f)
        assertEquals(SettingsViewModel.DEFAULT_TOP_P,            s.topP, 1e-6f)
        assertEquals(SettingsViewModel.DEFAULT_MAX_TURNS,        s.maxTurns)
        assertEquals(SettingsViewModel.DEFAULT_PRESENCE_PENALTY, s.presencePenalty, 1e-6f)
        assertEquals(SettingsViewModel.DEFAULT_THEME_CHOICE,     s.themeChoice)
        assertEquals(SettingsViewModel.DEFAULT_DYNAMIC_COLOR,    s.dynamicColor)
    }
}
