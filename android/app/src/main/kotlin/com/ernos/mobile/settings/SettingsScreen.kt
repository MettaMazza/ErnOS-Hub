package com.ernos.mobile.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * SettingsScreen
 *
 * Allows the user to tune all inference parameters and appearance preferences.
 * All changes take effect immediately and persist across sessions via DataStore.
 *
 * Parameters exposed:
 *   - Context window (512–16384) — triggers [onNCtxChanged] on change so
 *     [ChatViewModel.reloadCurrentModel] is called automatically
 *   - Temperature (0.0–2.0)
 *   - Top-P (0.0–1.0)
 *   - Max ReAct turns (1–30)
 *   - Presence penalty (0.0–2.0)
 *
 * Appearance:
 *   - Theme (System / Dark / Light / Custom)
 *   - Custom primary colour (hue + saturation/brightness sliders — only when theme = "custom")
 *   - Dynamic color (Android 12+)
 *
 * @param onNCtxChanged Called after nCtx slider is released with the new nCtx
 *   value (already snapped to a 512-multiple).  Pass
 *   `{ nCtx -> chatViewModel.reloadCurrentModel(nCtx) }` from the nav host so
 *   the model is reloaded with the exact value chosen on the slider — no
 *   DataStore read/write race possible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNCtxChanged: ((Int) -> Unit)? = null,
    vm: SettingsViewModel = viewModel(),
) {
    val nCtx              by vm.nCtx
    val temperature       by vm.temperature
    val topP              by vm.topP
    val maxTurns          by vm.maxTurns
    val presencePenalty   by vm.presencePenalty
    val themeChoice       by vm.themeChoice
    val dynamicColor      by vm.dynamicColor
    val customPrimaryArgb by vm.customPrimaryColor

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.resetToDefaults() }) {
                        Icon(
                            imageVector        = Icons.Default.RestartAlt,
                            contentDescription = "Reset to defaults",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            // ── Inference parameters ──────────────────────────────────────────

            SectionHeader("Inference Parameters")

            SettingSlider(
                label                 = "Context window",
                persistedValue        = nCtx.toFloat(),
                valueRange            = 512f..16384f,
                steps                 = 31,
                format                = { "${((it / 512).toInt() * 512)}" },
                onValueChangeFinished = { v ->
                    val snappedNCtx = ((v / 512).toInt() * 512).coerceIn(512, 16384)
                    vm.setNCtx(snappedNCtx)
                    // Pass value directly to avoid DataStore async write race.
                    onNCtxChanged?.invoke(snappedNCtx)
                },
                testTag               = "slider_n_ctx",
            )

            SettingSlider(
                label                 = "Temperature",
                persistedValue        = temperature,
                valueRange            = 0f..2f,
                steps                 = 39,
                format                = { "%.2f".format(it) },
                onValueChangeFinished = { vm.setTemperature(it) },
                testTag               = "slider_temperature",
            )

            SettingSlider(
                label                 = "Top-P",
                persistedValue        = topP,
                valueRange            = 0f..1f,
                steps                 = 19,
                format                = { "%.2f".format(it) },
                onValueChangeFinished = { vm.setTopP(it) },
                testTag               = "slider_top_p",
            )

            SettingSlider(
                label                 = "Max ReAct turns",
                persistedValue        = maxTurns.toFloat(),
                valueRange            = 1f..30f,
                steps                 = 28,
                format                = { it.toInt().toString() },
                onValueChangeFinished = { vm.setMaxTurns(it.toInt()) },
                testTag               = "slider_max_turns",
            )

            SettingSlider(
                label                 = "Presence penalty",
                persistedValue        = presencePenalty,
                valueRange            = 0f..2f,
                steps                 = 39,
                format                = { "%.2f".format(it) },
                onValueChangeFinished = { vm.setPresencePenalty(it) },
                testTag               = "slider_presence_penalty",
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ── Appearance ────────────────────────────────────────────────────

            SectionHeader("Appearance")

            Text(
                text  = "Theme",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("theme_chips_row"),
            ) {
                listOf("system", "dark", "light", "custom").forEach { choice ->
                    FilterChip(
                        selected = themeChoice == choice,
                        onClick  = { vm.setThemeChoice(choice) },
                        label    = { Text(choice.replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.testTag("theme_chip_$choice"),
                    )
                }
            }

            // Custom colour picker — only visible when "custom" theme is selected
            AnimatedVisibility(
                visible = themeChoice == "custom",
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                CustomColorPicker(
                    currentArgb  = customPrimaryArgb,
                    onColorChosen = { vm.setCustomPrimaryColor(it) },
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text  = "Dynamic color",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text  = "Use wallpaper colors (Android 12+)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked         = dynamicColor,
                    onCheckedChange = { vm.setDynamicColor(it) },
                    modifier        = Modifier.testTag("switch_dynamic_color"),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // ── Reset button ──────────────────────────────────────────────────

            OutlinedButton(
                onClick  = { vm.resetToDefaults() },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("button_reset_defaults"),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset all settings to defaults")
            }
        }
    }
}

// ── Custom colour picker ───────────────────────────────────────────────────────

/**
 * A simple inline colour picker that lets the user choose a hue (0–360°) and
 * brightness (0.2–1.0) via two sliders, and previews the resulting colour.
 *
 * The chosen colour is applied via [onColorChosen] as an ARGB int.
 * No third-party colour-picker library is required.
 */
@Composable
private fun CustomColorPicker(
    currentArgb:  Int,
    onColorChosen: (Int) -> Unit,
) {
    // Decompose currentArgb to HSV so sliders reflect the persisted value
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(currentArgb, hsv)

    var hue        by remember(currentArgb) { mutableFloatStateOf(hsv[0]) }
    var brightness by remember(currentArgb) { mutableFloatStateOf(hsv[2]) }
    val previewColor = Color.hsv(hue, 0.8f, brightness)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .testTag("custom_color_picker"),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Colour swatch
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(previewColor)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .testTag("custom_color_swatch"),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = "Hue",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value                = hue,
                    onValueChange        = { hue = it },
                    onValueChangeFinished = {
                        val argb = Color.hsv(hue, 0.8f, brightness).toArgb()
                        onColorChosen(argb)
                    },
                    valueRange           = 0f..360f,
                    steps                = 359,
                    modifier             = Modifier.testTag("slider_hue"),
                )
                Text(
                    text  = "Brightness",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value                = brightness,
                    onValueChange        = { brightness = it },
                    onValueChangeFinished = {
                        val argb = Color.hsv(hue, 0.8f, brightness).toArgb()
                        onColorChosen(argb)
                    },
                    valueRange           = 0.2f..1.0f,
                    steps                = 15,
                    modifier             = Modifier.testTag("slider_brightness"),
                )
            }
        }

        // Quick-access preset swatches
        Text(
            text  = "Quick presets",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "ErnBlue"  to Color(0xFF1E88E5),
                "Purple"   to Color(0xFF8B5CF6),
                "Teal"     to Color(0xFF14B8A6),
                "Orange"   to Color(0xFFF97316),
                "Rose"     to Color(0xFFF43F5E),
            ).forEach { (name, color) ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (previewColor == color) 3.dp else 1.dp,
                            color = if (previewColor == color)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable {
                            val argb = color.toArgb()
                            android.graphics.Color.colorToHSV(argb, hsv)
                            hue        = hsv[0]
                            brightness = hsv[2]
                            onColorChosen(argb)
                        }
                        .testTag("preset_swatch_$name"),
                )
            }
        }
    }
}

// ── Helper composables ─────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text       = title,
        style      = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color      = MaterialTheme.colorScheme.primary,
        modifier   = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * A labelled slider that keeps local ephemeral state while dragging and
 * calls [onValueChangeFinished] (which persists the value) when the user
 * releases the thumb.
 *
 * [persistedValue] drives the initial state and resets the local state
 * whenever the ViewModel emits a new value (e.g. after "Reset to defaults").
 */
@Composable
private fun SettingSlider(
    label:                String,
    persistedValue:       Float,
    valueRange:           ClosedFloatingPointRange<Float>,
    steps:                Int,
    format:               (Float) -> String,
    onValueChangeFinished: (Float) -> Unit,
    testTag:              String = "",
) {
    var localValue by remember(persistedValue) { mutableFloatStateOf(persistedValue) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text       = format(localValue),
                style      = MaterialTheme.typography.bodyMedium,
                color      = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value                = localValue,
            onValueChange        = { localValue = it },
            onValueChangeFinished = { onValueChangeFinished(localValue) },
            valueRange           = valueRange,
            steps                = steps,
            modifier             = Modifier
                .fillMaxWidth()
                .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        )
    }
}
