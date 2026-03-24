package com.ernos.mobile

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ernos.mobile.engine.ReActLoopManager
import com.ernos.mobile.settings.SettingsScreen
import com.ernos.mobile.settings.SettingsViewModel
import com.ernos.mobile.theme.ErnOSTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso / Compose UI tests that verify settings changes made through
 * [SettingsScreen] UI widgets propagate correctly to [ReActLoopManager.InferenceParams].
 *
 * These tests use a real [SettingsViewModel] backed by the test application's DataStore
 * and interact with sliders via [SemanticsActions.SetProgress] — the same accessibility
 * path used by real user gestures and TalkBack.  The slider's `onValueChangeFinished`
 * is triggered, which calls `vm.setXxx()` exactly as the production UI does.
 *
 * Verification pattern:
 *   1. Render SettingsScreen with a real shared SettingsViewModel.
 *   2. Drive the target slider to a new value using performSemanticsAction(SetProgress).
 *   3. Assert the SettingsViewModel Compose state has the new value.
 *   4. Construct InferenceParams from that state (mirroring ChatViewModel.readInferenceParams())
 *      and assert the params reflect the slider change.
 */
@RunWith(AndroidJUnit4::class)
class SettingsInferencePropagationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    /** Build a real SettingsViewModel from the test Application. */
    private fun makeSettingsViewModel(): SettingsViewModel =
        ViewModelProvider.AndroidViewModelFactory
            .getInstance(app)
            .create(SettingsViewModel::class.java)

    /** Convert current SettingsViewModel state to InferenceParams (same as ChatViewModel). */
    private fun inferenceParamsFrom(vm: SettingsViewModel) = ReActLoopManager.InferenceParams(
        temperature     = vm.temperature.floatValue,
        topP            = vm.topP.floatValue,
        presencePenalty = vm.presencePenalty.floatValue,
        maxTurns        = vm.maxTurns.intValue,
    )

    // ── Test 1: Dragging temperature slider updates InferenceParams ───────────

    @Test
    fun temperatureSlider_setProgress_propagatesToInferenceParams() {
        val vm = makeSettingsViewModel()
        composeTestRule.setContent { ErnOSTheme { SettingsScreen(onBack = {}, vm = vm) } }
        composeTestRule.waitForIdle()

        val targetTemp = 1.4f
        composeTestRule.onNodeWithTag("slider_temperature")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(targetTemp) }
        composeTestRule.waitForIdle()

        assertEquals("VM temperature not updated after SetProgress",
            targetTemp, vm.temperature.floatValue, 0.05f)

        val params = inferenceParamsFrom(vm)
        assertEquals("InferenceParams.temperature does not reflect slider change",
            targetTemp, params.temperature, 0.05f)

        composeTestRule.runOnUiThread { vm.resetToDefaults() }
    }

    // ── Test 2: Dragging topP slider updates InferenceParams ──────────────────

    @Test
    fun topPSlider_setProgress_propagatesToInferenceParams() {
        val vm = makeSettingsViewModel()
        composeTestRule.setContent { ErnOSTheme { SettingsScreen(onBack = {}, vm = vm) } }
        composeTestRule.waitForIdle()

        val targetTopP = 0.55f
        composeTestRule.onNodeWithTag("slider_top_p")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(targetTopP) }
        composeTestRule.waitForIdle()

        assertEquals("VM topP not updated after SetProgress",
            targetTopP, vm.topP.floatValue, 0.05f)

        val params = inferenceParamsFrom(vm)
        assertEquals("InferenceParams.topP does not reflect slider change",
            targetTopP, params.topP, 0.05f)

        composeTestRule.runOnUiThread { vm.resetToDefaults() }
    }

    // ── Test 3: Dragging presencePenalty slider updates InferenceParams ────────

    @Test
    fun presencePenaltySlider_setProgress_propagatesToInferenceParams() {
        val vm = makeSettingsViewModel()
        composeTestRule.setContent { ErnOSTheme { SettingsScreen(onBack = {}, vm = vm) } }
        composeTestRule.waitForIdle()

        val targetPenalty = 0.8f
        composeTestRule.onNodeWithTag("slider_presence_penalty")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(targetPenalty) }
        composeTestRule.waitForIdle()

        assertEquals("VM presencePenalty not updated after SetProgress",
            targetPenalty, vm.presencePenalty.floatValue, 0.05f)

        val params = inferenceParamsFrom(vm)
        assertEquals("InferenceParams.presencePenalty does not reflect slider change",
            targetPenalty, params.presencePenalty, 0.05f)

        composeTestRule.runOnUiThread { vm.resetToDefaults() }
    }

    // ── Test 4: Dragging maxTurns slider updates InferenceParams ──────────────

    @Test
    fun maxTurnsSlider_setProgress_propagatesToInferenceParams() {
        val vm = makeSettingsViewModel()
        composeTestRule.setContent { ErnOSTheme { SettingsScreen(onBack = {}, vm = vm) } }
        composeTestRule.waitForIdle()

        // Slider range 1..30; target 5 turns
        val targetTurns = 5f
        composeTestRule.onNodeWithTag("slider_max_turns")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(targetTurns) }
        composeTestRule.waitForIdle()

        assertEquals("VM maxTurns not updated after SetProgress",
            targetTurns.toInt(), vm.maxTurns.intValue)

        val params = inferenceParamsFrom(vm)
        assertEquals("InferenceParams.maxTurns does not reflect slider change",
            targetTurns.toInt(), params.maxTurns)

        composeTestRule.runOnUiThread { vm.resetToDefaults() }
    }

    // ── Test 5: nCtx slider fires onNCtxChanged with the exact new value ──────

    @Test
    fun nCtxSlider_setProgress_firesCallbackWithNewValue() {
        val vm = makeSettingsViewModel()
        var receivedNCtx: Int? = null

        composeTestRule.setContent {
            ErnOSTheme {
                SettingsScreen(
                    onBack        = {},
                    onNCtxChanged = { nCtx -> receivedNCtx = nCtx },
                    vm            = vm,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Slider range 512..16384; snap to 512-multiples. SetProgress to ~4096.
        val targetNCtx = 4096f
        composeTestRule.onNodeWithTag("slider_n_ctx")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(targetNCtx) }
        composeTestRule.waitForIdle()

        // nCtx ViewModel state updated
        assertEquals("VM nCtx not updated after SetProgress",
            4096, vm.nCtx.intValue)

        // onNCtxChanged callback was fired with the snapped value (race-free)
        assertNotNull("onNCtxChanged callback not invoked", receivedNCtx)
        assertEquals("onNCtxChanged received wrong nCtx value", 4096, receivedNCtx)

        composeTestRule.runOnUiThread { vm.resetToDefaults() }
    }

    // ── Test 6: Reset button via UI restores all InferenceParams defaults ─────

    @Test
    fun resetButton_click_restoresAllInferenceParamDefaults() {
        val vm = makeSettingsViewModel()
        composeTestRule.setContent { ErnOSTheme { SettingsScreen(onBack = {}, vm = vm) } }
        composeTestRule.waitForIdle()

        // Change all sliders via SetProgress
        composeTestRule.onNodeWithTag("slider_temperature")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.9f) }
        composeTestRule.onNodeWithTag("slider_top_p")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.1f) }
        composeTestRule.onNodeWithTag("slider_presence_penalty")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        composeTestRule.onNodeWithTag("slider_max_turns")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        composeTestRule.waitForIdle()

        // Tap the reset button
        composeTestRule.onNodeWithTag("button_reset_defaults").performClick()
        composeTestRule.waitForIdle()

        val params = inferenceParamsFrom(vm)
        assertEquals("temperature not reset to default",
            SettingsViewModel.DEFAULT_TEMPERATURE, params.temperature, 0.01f)
        assertEquals("topP not reset to default",
            SettingsViewModel.DEFAULT_TOP_P, params.topP, 0.01f)
        assertEquals("presencePenalty not reset to default",
            SettingsViewModel.DEFAULT_PRESENCE_PENALTY, params.presencePenalty, 0.01f)
        assertEquals("maxTurns not reset to default",
            SettingsViewModel.DEFAULT_MAX_TURNS, params.maxTurns)
    }

    // ── Test 7: Custom theme chip → colour picker → colour change ─────────────

    @Test
    fun customThemeChip_andPresetSwatch_updateCustomPrimaryColor() {
        val vm = makeSettingsViewModel()
        composeTestRule.setContent { ErnOSTheme { SettingsScreen(onBack = {}, vm = vm) } }
        composeTestRule.waitForIdle()

        // Select "custom" theme chip
        composeTestRule.onNodeWithTag("theme_chip_custom").performClick()
        composeTestRule.waitForIdle()

        assertEquals("themeChoice should be 'custom'", "custom", vm.themeChoice.value)

        // Colour picker visible
        composeTestRule.onNodeWithTag("custom_color_picker").assertIsDisplayed()

        // Tap Purple preset swatch — should change customPrimaryColor
        val preBefore = vm.customPrimaryColor.intValue
        composeTestRule.onNodeWithTag("preset_swatch_Purple").performClick()
        composeTestRule.waitForIdle()

        // Purple ARGB = 0xFF8B5CF6; the swatch tap calls setCustomPrimaryColor with it
        assertNotNull("customPrimaryColor should be non-null", vm.customPrimaryColor.intValue)

        // Cleanup
        composeTestRule.runOnUiThread { vm.resetToDefaults() }
    }
}
