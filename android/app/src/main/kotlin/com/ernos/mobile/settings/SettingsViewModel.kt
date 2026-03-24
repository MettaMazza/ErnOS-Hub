package com.ernos.mobile.settings

import android.app.Application
import android.util.Log
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ernos.mobile.ErnOSApplication
import com.ernos.mobile.memory.Tier5Scratchpad
import kotlinx.coroutines.launch

/**
 * SettingsViewModel
 *
 * Holds all user-adjustable inference parameters and theme preferences.
 * Reads persisted values from [Tier5Scratchpad] on init and writes back
 * on every user change so settings survive app restarts.
 *
 * Parameters:
 *   nCtx              — context window (512–16384 tokens)
 *   temperature       — sampling temperature (0.0–2.0)
 *   topP              — nucleus sampling (0.0–1.0)
 *   maxTurns          — ReAct loop max turns (1–30)
 *   presencePenalty   — presence penalty (0.0–2.0)
 *
 * Theme:
 *   themeChoice       — "system" | "dark" | "light" | "dynamic"
 *   dynamicColor      — use Android 12+ dynamic (wallpaper) colors
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SettingsViewModel"

        // DataStore keys (separate from Tier5 engine keys to avoid collisions)
        val KEY_N_CTX              = intPreferencesKey("settings_n_ctx")
        val KEY_TEMPERATURE        = floatPreferencesKey("settings_temperature")
        val KEY_TOP_P              = floatPreferencesKey("settings_top_p")
        val KEY_MAX_TURNS          = intPreferencesKey("settings_max_turns")
        val KEY_PRESENCE_PENALTY   = floatPreferencesKey("settings_presence_penalty")
        val KEY_THEME_CHOICE       = stringPreferencesKey("settings_theme_choice")
        val KEY_DYNAMIC_COLOR      = booleanPreferencesKey("settings_dynamic_color")
        /** ARGB int for user's custom primary colour. Defaults to ErnOS blue. */
        val KEY_CUSTOM_PRIMARY_COLOR = intPreferencesKey("settings_custom_primary_color")

        // Defaults
        const val DEFAULT_N_CTX            = 4096
        const val DEFAULT_TEMPERATURE      = 0.7f
        const val DEFAULT_TOP_P            = 0.9f
        const val DEFAULT_MAX_TURNS        = 15
        const val DEFAULT_PRESENCE_PENALTY = 0.0f
        const val DEFAULT_THEME_CHOICE     = "system"
        const val DEFAULT_DYNAMIC_COLOR    = true
        /** ErnOS blue as default custom primary colour (0xFF1E88E5) */
        val DEFAULT_CUSTOM_PRIMARY_COLOR: Int = Color(0xFF1E88E5).toArgb()
    }

    private val scratchpad = ErnOSApplication.memoryManager.tier5

    // ── Inference parameters ──────────────────────────────────────────────────

    val nCtx            = mutableIntStateOf(DEFAULT_N_CTX)
    val temperature     = mutableFloatStateOf(DEFAULT_TEMPERATURE)
    val topP            = mutableFloatStateOf(DEFAULT_TOP_P)
    val maxTurns        = mutableIntStateOf(DEFAULT_MAX_TURNS)
    val presencePenalty = mutableFloatStateOf(DEFAULT_PRESENCE_PENALTY)

    // ── Theme ─────────────────────────────────────────────────────────────────

    /** "system" | "dark" | "light" | "custom" */
    val themeChoice          = mutableStateOf(DEFAULT_THEME_CHOICE)
    val dynamicColor         = mutableStateOf(DEFAULT_DYNAMIC_COLOR)
    /**
     * ARGB int for the user's chosen custom primary colour.
     * Only applied when [themeChoice] == "custom".
     */
    val customPrimaryColor   = mutableIntStateOf(DEFAULT_CUSTOM_PRIMARY_COLOR)

    // ── Load / save ───────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            loadFromDataStore()
        }
    }

    private suspend fun loadFromDataStore() {
        nCtx.intValue              = scratchpad.getOrDefault(KEY_N_CTX,               DEFAULT_N_CTX)
        temperature.floatValue     = scratchpad.getOrDefault(KEY_TEMPERATURE,         DEFAULT_TEMPERATURE)
        topP.floatValue            = scratchpad.getOrDefault(KEY_TOP_P,               DEFAULT_TOP_P)
        maxTurns.intValue          = scratchpad.getOrDefault(KEY_MAX_TURNS,           DEFAULT_MAX_TURNS)
        presencePenalty.floatValue = scratchpad.getOrDefault(KEY_PRESENCE_PENALTY,    DEFAULT_PRESENCE_PENALTY)
        themeChoice.value          = scratchpad.getOrDefault(KEY_THEME_CHOICE,        DEFAULT_THEME_CHOICE)
        dynamicColor.value         = scratchpad.getOrDefault(KEY_DYNAMIC_COLOR,       DEFAULT_DYNAMIC_COLOR)
        customPrimaryColor.intValue = scratchpad.getOrDefault(KEY_CUSTOM_PRIMARY_COLOR, DEFAULT_CUSTOM_PRIMARY_COLOR)
        Log.i(TAG, "Settings loaded: nCtx=${nCtx.intValue} temp=${temperature.floatValue} " +
            "topP=${topP.floatValue} maxTurns=${maxTurns.intValue} theme=${themeChoice.value} " +
            "customPrimary=#${Integer.toHexString(customPrimaryColor.intValue)}")
    }

    // ── Setters — each one persists immediately ────────────────────────────────

    fun setNCtx(value: Int) {
        nCtx.intValue = value.coerceIn(512, 16384)
        persist { scratchpad.set(KEY_N_CTX, nCtx.intValue) }
    }

    fun setTemperature(value: Float) {
        temperature.floatValue = value.coerceIn(0f, 2f)
        persist { scratchpad.set(KEY_TEMPERATURE, temperature.floatValue) }
    }

    fun setTopP(value: Float) {
        topP.floatValue = value.coerceIn(0f, 1f)
        persist { scratchpad.set(KEY_TOP_P, topP.floatValue) }
    }

    fun setMaxTurns(value: Int) {
        maxTurns.intValue = value.coerceIn(1, 30)
        persist { scratchpad.set(KEY_MAX_TURNS, maxTurns.intValue) }
    }

    fun setPresencePenalty(value: Float) {
        presencePenalty.floatValue = value.coerceIn(0f, 2f)
        persist { scratchpad.set(KEY_PRESENCE_PENALTY, presencePenalty.floatValue) }
    }

    fun setThemeChoice(value: String) {
        themeChoice.value = value
        persist { scratchpad.set(KEY_THEME_CHOICE, value) }
    }

    fun setDynamicColor(value: Boolean) {
        dynamicColor.value = value
        persist { scratchpad.set(KEY_DYNAMIC_COLOR, value) }
    }

    /**
     * Persist a custom primary colour (ARGB int) and automatically switch
     * [themeChoice] to "custom" so the colour is applied immediately.
     *
     * Call from SettingsScreen when the user confirms a colour-picker selection
     * or taps a preset swatch.
     */
    fun setCustomPrimaryColor(argb: Int) {
        customPrimaryColor.intValue = argb
        persist { scratchpad.set(KEY_CUSTOM_PRIMARY_COLOR, argb) }
        // Auto-switch to custom theme so the chosen colour takes effect right away.
        if (themeChoice.value != "custom") {
            setThemeChoice("custom")
        }
        Log.i(TAG, "Custom primary color set: #${Integer.toHexString(argb)}, theme=custom")
    }

    fun resetToDefaults() {
        setNCtx(DEFAULT_N_CTX)
        setTemperature(DEFAULT_TEMPERATURE)
        setTopP(DEFAULT_TOP_P)
        setMaxTurns(DEFAULT_MAX_TURNS)
        setPresencePenalty(DEFAULT_PRESENCE_PENALTY)
        setThemeChoice(DEFAULT_THEME_CHOICE)
        setDynamicColor(DEFAULT_DYNAMIC_COLOR)
        setCustomPrimaryColor(DEFAULT_CUSTOM_PRIMARY_COLOR)
        Log.i(TAG, "Settings reset to defaults")
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
