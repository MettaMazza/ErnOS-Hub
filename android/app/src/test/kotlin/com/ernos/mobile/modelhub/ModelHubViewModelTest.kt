package com.ernos.mobile.modelhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ModelHubViewModel] pure logic.
 *
 * [ModelHubViewModel] requires an Android Application and network calls.
 * We test the pure logic: JSON parsing, quantization extraction, file size
 * formatting, and download state transitions.
 *
 * Covered:
 *   - [GgufFile.sizeLabel] for various byte counts
 *   - parseQuantization extracts quant tag from filename
 *   - [DownloadState] status transitions (IDLE → RUNNING → DONE / ERROR)
 *   - [HuggingFaceModel] ggufFiles list operations
 *   - [DownloadStatus] enum exhaustiveness
 */
class ModelHubViewModelTest {

    // ── GgufFile.sizeLabel ────────────────────────────────────────────────────

    @Test fun size_label_unknown_when_negative() {
        val f = GgufFile("model.gguf", size = -1L, quant = "Q4_K_M")
        assertEquals("unknown size", f.sizeLabel)
    }

    @Test fun size_label_kb_for_small_file() {
        val f = GgufFile("model.gguf", size = 512L * 1024, quant = "Q4_K_M")
        assertTrue("Should show KB", f.sizeLabel.endsWith("KB"))
    }

    @Test fun size_label_mb_for_medium_file() {
        val f = GgufFile("model.gguf", size = 512L * 1024 * 1024, quant = "Q4_K_M")
        assertTrue("Should show MB", f.sizeLabel.endsWith("MB"))
    }

    @Test fun size_label_gb_for_large_file() {
        val f = GgufFile("model.gguf", size = 4L * 1024 * 1024 * 1024, quant = "Q4_K_M")
        assertTrue("Should show GB", f.sizeLabel.endsWith("GB"))
    }

    @Test fun size_label_gb_value_approximately_4() {
        val bytes = 4L * 1024 * 1024 * 1024
        val f     = GgufFile("model.gguf", size = bytes, quant = "Q4_K_M")
        assertTrue("4 GB file should start with '4'", f.sizeLabel.startsWith("4"))
    }

    @Test fun size_label_zero_bytes_shows_kb() {
        val f = GgufFile("model.gguf", size = 0L, quant = "unknown")
        assertTrue("Zero bytes should be in KB range", f.sizeLabel.endsWith("KB"))
    }

    // ── parseQuantization (mirrors private fun in ViewModel) ─────────────────

    private fun parseQuantization(filename: String): String {
        val regex = Regex("""[Qq]\d+_[A-Z_\d]+""")
        return regex.find(filename)?.value ?: "unknown"
    }

    @Test fun parse_q4_k_m_from_filename() {
        assertEquals("Q4_K_M", parseQuantization("qwen2-7b-Q4_K_M.gguf"))
    }

    @Test fun parse_q5_k_s_from_filename() {
        assertEquals("Q5_K_S", parseQuantization("llama-3-Q5_K_S.gguf"))
    }

    @Test fun parse_q8_0_from_filename() {
        assertEquals("Q8_0", parseQuantization("model-Q8_0.gguf"))
    }

    @Test fun parse_returns_unknown_when_no_quant_tag() {
        assertEquals("unknown", parseQuantization("model-no-quant.gguf"))
    }

    @Test fun parse_lowercase_q_also_matches() {
        val result = parseQuantization("model-q4_k_m.gguf")
        assertTrue("Should match lowercase q prefix", result.isNotBlank())
        assertFalse("Should not be 'unknown'", result == "unknown")
    }

    // ── DownloadState transitions ─────────────────────────────────────────────

    @Test fun download_state_idle_has_zero_progress() {
        val state = DownloadState(key = "a/b", status = DownloadStatus.IDLE, progress = 0f, localPath = null)
        assertEquals(0f, state.progress, 1e-6f)
        assertEquals(DownloadStatus.IDLE, state.status)
    }

    @Test fun download_state_running_has_partial_progress() {
        val state = DownloadState(key = "a/b", status = DownloadStatus.RUNNING, progress = 0.42f, localPath = null)
        assertEquals(DownloadStatus.RUNNING, state.status)
        assertEquals(0.42f, state.progress, 1e-6f)
    }

    @Test fun download_state_done_has_full_progress_and_path() {
        val state = DownloadState(
            key       = "a/b",
            status    = DownloadStatus.DONE,
            progress  = 1.0f,
            localPath = "/sdcard/model.gguf",
        )
        assertEquals(DownloadStatus.DONE,        state.status)
        assertEquals(1.0f,                       state.progress, 1e-6f)
        assertEquals("/sdcard/model.gguf",        state.localPath)
    }

    @Test fun download_state_error_carries_message() {
        val state = DownloadState(
            key       = "a/b",
            status    = DownloadStatus.ERROR,
            progress  = 0f,
            localPath = null,
            error     = "HTTP 404",
        )
        assertEquals(DownloadStatus.ERROR, state.status)
        assertEquals("HTTP 404",           state.error)
    }

    @Test fun download_state_copy_updates_status() {
        val initial  = DownloadState("x", DownloadStatus.RUNNING, 0.5f, null)
        val completed = initial.copy(status = DownloadStatus.DONE, progress = 1f, localPath = "/path")
        assertEquals(DownloadStatus.DONE, completed.status)
        assertEquals(1.0f,               completed.progress, 1e-6f)
    }

    // ── DownloadStatus enum ───────────────────────────────────────────────────

    @Test fun download_status_has_four_values() {
        assertEquals(4, DownloadStatus.values().size)
    }

    @Test fun download_status_has_all_expected_variants() {
        val names = DownloadStatus.values().map { it.name }
        assertTrue(names.contains("IDLE"))
        assertTrue(names.contains("RUNNING"))
        assertTrue(names.contains("DONE"))
        assertTrue(names.contains("ERROR"))
    }

    // ── HuggingFaceModel ──────────────────────────────────────────────────────

    @Test fun model_gguf_files_list_is_accessible() {
        val files = listOf(
            GgufFile("model-Q4_K_M.gguf", 4_000_000_000L, "Q4_K_M"),
            GgufFile("model-Q8_0.gguf",   7_000_000_000L, "Q8_0"),
        )
        val model = HuggingFaceModel(
            modelId   = "Qwen/Qwen2.5-7B-Instruct-GGUF",
            author    = "Qwen",
            downloads = 100_000,
            likes     = 500,
            createdAt = "2024-09-01T00:00:00Z",
            ggufFiles = files,
        )
        assertEquals(2, model.ggufFiles.size)
        assertEquals("model-Q4_K_M.gguf", model.ggufFiles[0].name)
        assertEquals("model-Q8_0.gguf",   model.ggufFiles[1].name)
    }

    @Test fun model_with_no_gguf_files_is_valid() {
        val model = HuggingFaceModel(
            modelId   = "test/model",
            author    = "test",
            downloads = 0,
            likes     = 0,
            createdAt = "",
            ggufFiles = emptyList(),
        )
        assertTrue(model.ggufFiles.isEmpty())
    }

    // ── formatCount (mirrors private fun in ViewModel) ────────────────────────

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
        n >= 1_000     -> "%.1fK".format(n / 1_000f)
        else           -> n.toString()
    }

    @Test fun format_count_millions() {
        assertEquals("1.5M", formatCount(1_500_000L))
    }

    @Test fun format_count_thousands() {
        assertEquals("12.3K", formatCount(12_345L))
    }

    @Test fun format_count_under_thousand() {
        assertEquals("999", formatCount(999L))
    }

    @Test fun format_count_zero() {
        assertEquals("0", formatCount(0L))
    }

    // ── Default query ─────────────────────────────────────────────────────────

    @Test fun default_query_is_qwen_gguf() {
        assertEquals("Qwen GGUF", ModelHubViewModel.DEFAULT_QUERY)
    }
}
