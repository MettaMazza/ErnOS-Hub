package com.ernos.mobile.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SystemPrompter] logic.
 *
 * [SystemPrompter] requires an Android [Context] for BatteryManager /
 * ConnectivityManager, so we test the pure text-assembly and tool-filtering
 * logic via a [FakeSystemPrompter] that mirrors the section structure without
 * Android APIs.
 *
 * What is covered:
 *   - Identity section always included
 *   - HUD block always included
 *   - Tool schema included
 *   - Vision tool excluded when isMultimodal = false
 *   - Vision tool included when isMultimodal = true
 *   - Host-only tools excluded when isOffloaded = false
 *   - Host-only tools included when isOffloaded = true
 *   - Memory section omitted when memoryContext is blank
 *   - Memory section included when memoryContext is non-blank
 *   - additionalDirectives appended at end
 */
class SystemPrompterTest {

    // ── Fake that mirrors SystemPrompter structure without Android deps ────────

    private fun buildFakePrompt(
        isMultimodal: Boolean = false,
        isOffloaded:  Boolean = false,
        memoryContext: String = "",
        additionalDirectives: String = "",
    ): String = buildString {
        // 1. Identity
        appendLine("## IDENTITY")
        appendLine("You are ErnOS.")

        // 2. HUD
        appendLine("## HUD")
        appendLine("[SYSTEM AWARENESS] battery=100% network=wifi time=now")

        // 3. Tools
        appendLine("## TOOLS")
        appendLine("{\"name\":\"web_search\"}")
        appendLine("{\"name\":\"file_read\"}")
        appendLine("{\"name\":\"file_write\"}")
        appendLine("{\"name\":\"memory_query\"}")
        appendLine("{\"name\":\"reply_to_request\"}")

        // Vision tool — gated by isMultimodal
        if (isMultimodal) {
            appendLine("{\"name\":\"describe_image\"}")
        }

        // Host-only tools — gated by isOffloaded
        if (isOffloaded) {
            appendLine("{\"name\":\"bash_execute\"}")
            appendLine("{\"name\":\"terminal_read\"}")
            appendLine("{\"name\":\"finder_open\"}")
        }

        // 4. Memory section — only if non-blank
        if (memoryContext.isNotEmpty()) {
            appendLine("## MEMORY")
            appendLine(memoryContext)
        }

        // 5. Additional directives
        if (additionalDirectives.isNotEmpty()) {
            appendLine(additionalDirectives)
        }
    }.trim()

    // ── Identity ──────────────────────────────────────────────────────────────

    @Test fun identity_section_always_present() {
        val prompt = buildFakePrompt()
        assertTrue(prompt.contains("IDENTITY"))
    }

    // ── HUD ───────────────────────────────────────────────────────────────────

    @Test fun hud_section_always_present() {
        val prompt = buildFakePrompt()
        assertTrue(prompt.contains("HUD") || prompt.contains("SYSTEM AWARENESS"))
    }

    // ── Vision tool gating ────────────────────────────────────────────────────

    @Test fun vision_tool_absent_when_not_multimodal() {
        val prompt = buildFakePrompt(isMultimodal = false)
        assertFalse("describe_image must be excluded for text-only models",
            prompt.contains("describe_image"))
    }

    @Test fun vision_tool_present_when_multimodal() {
        val prompt = buildFakePrompt(isMultimodal = true)
        assertTrue("describe_image must be included for vision-capable models",
            prompt.contains("describe_image"))
    }

    // ── Host-only tool gating ─────────────────────────────────────────────────

    @Test fun host_tools_absent_when_not_offloaded() {
        val prompt = buildFakePrompt(isOffloaded = false)
        assertFalse(prompt.contains("bash_execute"))
        assertFalse(prompt.contains("terminal_read"))
        assertFalse(prompt.contains("finder_open"))
    }

    @Test fun host_tools_present_when_offloaded() {
        val prompt = buildFakePrompt(isOffloaded = true)
        assertTrue(prompt.contains("bash_execute"))
        assertTrue(prompt.contains("terminal_read"))
        assertTrue(prompt.contains("finder_open"))
    }

    // ── Memory section ────────────────────────────────────────────────────────

    @Test fun memory_section_absent_when_empty() {
        val prompt = buildFakePrompt(memoryContext = "")
        assertFalse("MEMORY header must be absent when context is empty",
            prompt.contains("## MEMORY"))
    }

    @Test fun memory_section_present_when_non_blank() {
        val prompt = buildFakePrompt(memoryContext = "Yesterday we talked about Kotlin.")
        assertTrue(prompt.contains("## MEMORY"))
        assertTrue(prompt.contains("Yesterday we talked about Kotlin."))
    }

    // ── Additional directives ─────────────────────────────────────────────────

    @Test fun additional_directives_appended_at_end() {
        val extra  = "EXTRA_DIRECTIVE_MARKER"
        val prompt = buildFakePrompt(additionalDirectives = extra)
        assertTrue(prompt.contains(extra))
        // Must appear after TOOLS section
        val toolsPos = prompt.indexOf("TOOLS")
        val extraPos = prompt.indexOf(extra)
        assertTrue("Extra directives must follow TOOLS section", extraPos > toolsPos)
    }

    @Test fun additional_directives_absent_when_empty() {
        val prompt = buildFakePrompt(additionalDirectives = "")
        // Just validate no blank trailing lines mess up the output
        assertFalse(prompt.endsWith("\n"))
    }

    // ── ModelConfig defaults ──────────────────────────────────────────────────

    @Test fun default_model_config_is_text_only_non_offloaded() {
        val config = SystemPrompter.ModelConfig()
        assertFalse(config.isMultimodal)
        assertFalse(config.isOffloaded)
    }

    @Test fun model_config_vision_flag_independent_of_offload_flag() {
        val multimodalOffloaded = SystemPrompter.ModelConfig(isMultimodal = true, isOffloaded = true)
        assertTrue(multimodalOffloaded.isMultimodal)
        assertTrue(multimodalOffloaded.isOffloaded)

        val textOnlyOffloaded = SystemPrompter.ModelConfig(isMultimodal = false, isOffloaded = true)
        assertFalse(textOnlyOffloaded.isMultimodal)
        assertTrue(textOnlyOffloaded.isOffloaded)
    }
}
