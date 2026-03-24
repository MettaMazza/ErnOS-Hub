package com.ernos.mobile.modelhub

import android.app.Application
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * ModelHubViewModel
 *
 * Queries the HuggingFace Hub API to browse Qwen-family GGUF models.
 * Supports searching, listing model metadata (size, quantization), and
 * downloading GGUF files with progress tracking.
 *
 * Search queries the `/api/models` endpoint filtered to GGUF format
 * and the `Qwen` organisation by default. Users can refine the query
 * to find any GGUF model on HuggingFace Hub.
 */
class ModelHubViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ModelHubViewModel"

        private const val HF_API_MODELS  = "https://huggingface.co/api/models"
        private const val HF_CDN_BASE    = "https://huggingface.co"

        /** Default search query pre-populated in the search field. */
        const val DEFAULT_QUERY = "Qwen GGUF"
    }

    // ── State ─────────────────────────────────────────────────────────────────

    val searchQuery    = mutableStateOf(DEFAULT_QUERY)
    val isSearching    = mutableStateOf(false)
    val searchError    = mutableStateOf<String?>(null)
    val models         = mutableStateListOf<HuggingFaceModel>()

    /**
     * Map of modelId → download state.
     * Uses [mutableStateMapOf] (a Compose [SnapshotStateMap]) so that any
     * put/remove on this map triggers recomposition in [ModelHubScreen].
     */
    val downloads      = mutableStateMapOf<String, DownloadState>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var downloadJob: Job? = null

    // ── Search ────────────────────────────────────────────────────────────────

    init {
        search(DEFAULT_QUERY)
    }

    fun search(query: String = searchQuery.value) {
        if (isSearching.value) return
        searchQuery.value = query
        isSearching.value = true
        searchError.value = null
        models.clear()

        viewModelScope.launch {
            try {
                val results = fetchModels(query)
                models.addAll(results)
                Log.i(TAG, "Found ${results.size} models for query: $query")
            } catch (e: Exception) {
                Log.e(TAG, "Search error: ${e.message}", e)
                searchError.value = "Search failed: ${e.message}"
            } finally {
                isSearching.value = false
            }
        }
    }

    private suspend fun fetchModels(query: String): List<HuggingFaceModel> = withContext(Dispatchers.IO) {
        val encoded = query.trim().replace(" ", "+")
        val url     = "$HF_API_MODELS?search=$encoded&filter=gguf&limit=20&sort=downloads&direction=-1"
        Log.d(TAG, "GET $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "ErnOS-ModelHub/1.0 Android")
            .get()
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            response.body?.string() ?: throw Exception("Empty response from HuggingFace API")
        }

        // Step 1: parse the lightweight model list (no file info)
        val stubs = parseModelStubs(body)

        // Step 2: hydrate each model with its GGUF file list
        stubs.mapNotNull { stub ->
            try {
                val details = fetchModelDetails(stub.modelId)
                stub.copy(ggufFiles = details)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch details for ${stub.modelId}: ${e.message}")
                stub // keep it with empty ggufFiles — user sees "No GGUF files"
            }
        }
    }

    /**
     * Fetch individual model details including the full file tree.
     * Uses `?blobs=true` to get file sizes in the `siblings` array.
     */
    private fun fetchModelDetails(modelId: String): List<GgufFile> {
        val url = "$HF_API_MODELS/$modelId?blobs=true"
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "ErnOS-ModelHub/1.0 Android")
            .get()
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string() ?: return emptyList()
        }

        val obj = JSONObject(body)
        val siblings = obj.optJSONArray("siblings") ?: return emptyList()
        val ggufFiles = mutableListOf<GgufFile>()
        for (j in 0 until siblings.length()) {
            val sib  = siblings.getJSONObject(j)
            val name = sib.optString("rfilename", "")
            if (name.endsWith(".gguf", ignoreCase = true)) {
                val size  = sib.optLong("size", -1)
                val quant = parseQuantization(name)
                ggufFiles.add(GgufFile(name, size, quant))
            }
        }
        return ggufFiles
    }

    /** Parse the search results into lightweight stubs (no file lists). */
    private fun parseModelStubs(json: String): List<HuggingFaceModel> {
        val array = JSONArray(json)
        val list  = mutableListOf<HuggingFaceModel>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val modelId   = obj.optString("modelId", "")
            val author    = obj.optString("author", "unknown")
            val downloads = obj.optLong("downloads", 0)
            val likes     = obj.optInt("likes", 0)
            val createdAt = obj.optString("createdAt", "")

            if (modelId.isNotBlank()) {
                list.add(
                    HuggingFaceModel(
                        modelId   = modelId,
                        author    = author,
                        downloads = downloads,
                        likes     = likes,
                        createdAt = createdAt,
                        ggufFiles = emptyList(), // hydrated later
                    )
                )
            }
        }
        return list
    }

    /** Extract quantization tag from a GGUF filename (e.g., "Q4_K_M" from "qwen2-7b-Q4_K_M.gguf"). */
    private fun parseQuantization(filename: String): String {
        val regex = Regex("""[Qq]\d+_[A-Z_\d]+""")
        return regex.find(filename)?.value ?: "unknown"
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Start downloading [file] from [model] into the app's external files directory.
     * Progress is tracked in [downloads]. Only one download runs at a time.
     */
    fun downloadFile(model: HuggingFaceModel, file: GgufFile) {
        val key = "${model.modelId}/${file.name}"
        if (downloads[key]?.status == DownloadStatus.RUNNING) return

        downloads[key] = DownloadState(key, DownloadStatus.RUNNING, 0f, null)
        downloadJob?.cancel()

        downloadJob = viewModelScope.launch {
            val outputDir  = getApplication<Application>().getExternalFilesDir(null)
                ?: getApplication<Application>().filesDir
            val outputFile = File(outputDir, file.name)
            val url        = "$HF_CDN_BASE/${model.modelId}/resolve/main/${file.name}"

            Log.i(TAG, "Downloading $url → ${outputFile.absolutePath}")
            try {
                withContext(Dispatchers.IO) {
                    downloadWithProgress(url, outputFile) { progress ->
                        downloads[key] = DownloadState(key, DownloadStatus.RUNNING, progress, null)
                    }
                }
                downloads[key] = DownloadState(key, DownloadStatus.DONE, 1f, outputFile.absolutePath)
                Log.i(TAG, "Download complete: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                downloads[key] = DownloadState(key, DownloadStatus.ERROR, 0f, null, e.message)
                outputFile.delete()
            }
        }
    }

    fun cancelDownload(key: String) {
        downloadJob?.cancel()
        downloads[key] = downloads[key]?.copy(status = DownloadStatus.IDLE) ?: return
    }

    private fun downloadWithProgress(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val request  = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body        = response.body ?: throw Exception("Empty response body")
            val contentLen  = body.contentLength()
            var downloaded  = 0L

            FileOutputStream(dest).use { out ->
                val buf = ByteArray(8 * 1024)
                body.byteStream().use { input ->
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        val progress = if (contentLen > 0) downloaded.toFloat() / contentLen else 0f
                        onProgress(progress)
                    }
                }
            }
        }
    }
}

// ── Data models ───────────────────────────────────────────────────────────────

data class HuggingFaceModel(
    val modelId:   String,
    val author:    String,
    val downloads: Long,
    val likes:     Int,
    val createdAt: String,
    val ggufFiles: List<GgufFile>,
)

data class GgufFile(
    val name:  String,
    val size:  Long,   // bytes, -1 if unknown
    val quant: String,
) {
    /** Human-readable file size. */
    val sizeLabel: String get() = when {
        size < 0               -> "unknown size"
        size < 1024 * 1024     -> "%.1f KB".format(size / 1024f)
        size < 1024 * 1024 * 1024 -> "%.1f MB".format(size / (1024f * 1024f))
        else                   -> "%.2f GB".format(size / (1024f * 1024f * 1024f))
    }
}

data class DownloadState(
    val key:      String,
    val status:   DownloadStatus,
    val progress: Float,         // 0.0–1.0
    val localPath: String?,
    val error:    String? = null,
)

enum class DownloadStatus { IDLE, RUNNING, DONE, ERROR }
