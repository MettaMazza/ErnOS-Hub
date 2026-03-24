package com.ernos.mobile.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.ernos.mobile.memory.db.AppDatabase
import com.ernos.mobile.memory.db.ChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Tier 2 — Autosave with Semantic Search
 *
 * Embeds text chunks with the on-device all-MiniLM-L6-v2 ONNX model and stores
 * them in Room's `chunks` table.  Cosine similarity search is done in-memory by
 * loading all embeddings from Room and computing dot products.
 *
 * ## Model files (push both to getExternalFilesDir(null)):
 *   1. `all-MiniLM-L6-v2.onnx`  — the ONNX model
 *   2. `bert-vocab.txt`          — the standard BERT/MiniLM 30,522-token vocab
 *      (same file shipped with sentence-transformers all-MiniLM-L6-v2;
 *       download from: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
 *
 * ## Tokenisation — two-tier strategy:
 *   TIER A (when bert-vocab.txt is present):
 *     True BERT WordPiece tokenisation using the vocab lookup.
 *     - Lowercase + basic punctuation splitting
 *     - Greedy longest-match WordPiece
 *     - Unknown tokens map to [UNK] (id=100)
 *     - [CLS]=101, [SEP]=102, [PAD]=0
 *
 *   TIER B (fallback — vocab file absent):
 *     FNV-1a hash approximation described below.  Logged as a warning at runtime
 *     so operators know to push the vocab file.  Embeddings will be imprecise
 *     but the system degrades gracefully (keyword search via Tier 4 still works).
 *
 * If the ONNX model file is absent, embedding calls are no-ops and semantic
 * search returns an empty list.
 */
class Tier2AutoSave(private val context: Context) {

    companion object {
        private const val TAG = "Tier2AutoSave"

        /** Maximum sequence length accepted by all-MiniLM-L6-v2. */
        const val MAX_SEQ_LEN = 128

        /** Embedding dimensionality for all-MiniLM-L6-v2. */
        const val EMBED_DIM = 384

        /** BERT WordPiece vocabulary size. */
        const val VOCAB_SIZE = 30522

        /** [CLS] token ID in BERT vocabulary. */
        const val CLS_ID = 101L

        /** [SEP] token ID in BERT vocabulary. */
        const val SEP_ID = 102L

        /** [PAD] token ID in BERT vocabulary. */
        const val PAD_ID = 0L

        /** [UNK] token ID in BERT vocabulary. */
        const val UNK_ID = 100L

        const val MODEL_FILENAME = "all-MiniLM-L6-v2.onnx"
        const val VOCAB_FILENAME  = "bert-vocab.txt"

        /** Minimum cosine similarity for a chunk to be returned by [search]. */
        private const val SIMILARITY_THRESHOLD = 0.30f

        /** Maximum word length WordPiece will try to fully decompose. */
        private const val MAX_WORDPIECE_LEN = 100
    }

    private val db       = AppDatabase.getInstance(context)
    private val chunkDao = db.chunkDao()

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession?  = null

    /** Guards concurrent calls to [loadModel] — prevents double-loading the ONNX session. */
    private val loadModelMutex = Mutex()

    /**
     * Vocab map: token-string → token-id.
     * Null until [loadModel] has successfully loaded the vocab file.
     * When null, [tokenise] falls back to the FNV-1a approximation.
     */
    var vocab: Map<String, Long>? = null
        private set

    /** True when the ONNX model is loaded and ready for inference. */
    val isModelLoaded: Boolean get() = ortSession != null

    /** True when the true BERT vocab is loaded (best tokenisation quality). */
    val isVocabLoaded: Boolean get() = vocab != null

    // ── Session lifecycle ─────────────────────────────────────────────────────

    /**
     * Load the ONNX model and (separately) the BERT vocab file.
     *
     * Idempotent and concurrency-safe: guarded by [loadModelMutex] so concurrent
     * calls (e.g., from Application.onCreate and onResume) do not double-load the
     * ONNX session.  Returns immediately if the session is already open.
     * Runs on [Dispatchers.IO].
     */
    suspend fun loadModel() = withContext(Dispatchers.IO) {
        loadModelMutex.withLock {
            val extDir = context.getExternalFilesDir(null) ?: context.filesDir

            // Load vocab first (cheap) so tokenisation is ready before inference.
            if (vocab == null) {
                val vocabFile = File(extDir, VOCAB_FILENAME)
                if (vocabFile.exists()) {
                    try {
                        val map = HashMap<String, Long>(VOCAB_SIZE * 2)
                        vocabFile.bufferedReader().useLines { lines ->
                            lines.forEachIndexed { idx, token ->
                                map[token.trim()] = idx.toLong()
                            }
                        }
                        vocab = map
                        Log.i(TAG, "Loaded BERT vocab: ${map.size} tokens from ${vocabFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load bert-vocab.txt: ${e.message} — using FNV-1a fallback")
                    }
                } else {
                    Log.w(
                        TAG,
                        "bert-vocab.txt not found at ${vocabFile.absolutePath}. " +
                        "Push this file alongside the ONNX model for accurate embeddings. " +
                        "Falling back to FNV-1a hash approximation.",
                    )
                }
            }

            if (ortSession != null) return@withLock

            val modelFile = File(extDir, MODEL_FILENAME)
            if (!modelFile.exists()) {
                Log.w(TAG, "ONNX model not found at ${modelFile.absolutePath} — semantic search disabled")
                return@withLock
            }

            try {
                val env  = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions()
                ortSession = env.createSession(modelFile.absolutePath, opts)
                ortEnv    = env
                Log.i(TAG, "ONNX session loaded from ${modelFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ONNX model: ${e.message}", e)
            }
        }
    }

    fun closeModel() {
        ortSession?.close()
        ortEnv?.close()
        ortSession = null
        ortEnv     = null
    }

    // ── Embedding ─────────────────────────────────────────────────────────────

    /** Embed a single [text] and store it in the chunks table. No-ops if the model is not loaded. */
    suspend fun embedAndStore(text: String, sourceRef: String = "") = withContext(Dispatchers.IO) {
        val vec = embed(text) ?: return@withContext
        chunkDao.insert(
            ChunkEntity(
                text      = text,
                embedding = vec.joinToString(","),
                sourceRef = sourceRef,
            )
        )
        Log.d(TAG, "Stored embedding for ${text.take(60)}… (ref=$sourceRef)")
    }

    /**
     * Bulk-insert pre-built [ChunkEntity] objects that were already embedded
     * by the caller.  Used by [MemoryManager.flushToTier2] to avoid re-running
     * ONNX inference on chunks that were computed in a per-message loop.
     */
    internal suspend fun insertChunks(chunks: List<com.ernos.mobile.memory.db.ChunkEntity>) =
        withContext(Dispatchers.IO) {
            if (chunks.isNotEmpty()) chunkDao.insertAll(chunks)
        }

    /** Embed a batch of (text, sourceRef) pairs and bulk-insert. */
    suspend fun embedAndStoreAll(texts: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext
        val chunks = texts.mapNotNull { (text, ref) ->
            val vec = embed(text) ?: return@mapNotNull null
            ChunkEntity(text = text, embedding = vec.joinToString(","), sourceRef = ref)
        }
        if (chunks.isNotEmpty()) {
            chunkDao.insertAll(chunks)
            Log.i(TAG, "Stored ${chunks.size}/${texts.size} embeddings in Tier 2")
        }
    }

    // ── Semantic search ───────────────────────────────────────────────────────

    /**
     * Return up to [topK] chunks with cosine similarity >= [SIMILARITY_THRESHOLD] to [query].
     * Falls back to an empty list if the model is not loaded.
     */
    suspend fun search(query: String, topK: Int = 5): List<ScoredChunk> = withContext(Dispatchers.IO) {
        val queryVec  = embed(query) ?: return@withContext emptyList()
        val allChunks = chunkDao.allChunks()

        allChunks
            .mapNotNull { chunk ->
                val chunkVec = parseEmbedding(chunk.embedding) ?: return@mapNotNull null
                val sim = cosineSimilarity(queryVec, chunkVec)
                if (sim >= SIMILARITY_THRESHOLD) ScoredChunk(chunk.text, sim, chunk.sourceRef)
                else null
            }
            .sortedByDescending { it.score }
            .take(topK)
    }

    data class ScoredChunk(
        val text: String,
        val score: Float,
        val sourceRef: String,
    )

    // ── ONNX inference ────────────────────────────────────────────────────────

    /**
     * Embed [text] and return a unit-normalised float array of [EMBED_DIM] elements.
     * Returns null if the model is not loaded or inference fails.
     */
    fun embed(text: String): FloatArray? {
        val session = ortSession ?: return null
        val env     = ortEnv    ?: return null

        return try {
            val tokenIds  = tokenise(text)
            val len       = tokenIds.size
            val attMask   = LongArray(len) { 1L }
            val tokenType = LongArray(len) { 0L }
            val shape     = longArrayOf(1, len.toLong())

            val inputIdsTensor  = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds),  shape)
            val attMaskTensor   = OnnxTensor.createTensor(env, LongBuffer.wrap(attMask),   shape)
            val tokenTypeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenType), shape)

            val inputs = mapOf(
                "input_ids"      to inputIdsTensor,
                "attention_mask" to attMaskTensor,
                "token_type_ids" to tokenTypeTensor,
            )

            session.run(inputs).use { output ->
                // all-MiniLM-L6-v2 output 0 → token_embeddings [1, seq_len, 384].
                // Mean-pool across the seq dimension (all tokens attended equally).
                @Suppress("UNCHECKED_CAST")
                val tokenEmbeds = (output[0].value as Array<Array<FloatArray>>)[0]
                val pooled = FloatArray(EMBED_DIM)
                for (i in 0 until len) {
                    for (j in 0 until EMBED_DIM) pooled[j] += tokenEmbeds[i][j]
                }
                for (j in 0 until EMBED_DIM) pooled[j] /= len
                normalise(pooled)
            }
        } catch (e: Exception) {
            Log.e(TAG, "embed() failed: ${e.message}", e)
            null
        }
    }

    // ── Tokenisation ─────────────────────────────────────────────────────────

    /**
     * Convert [text] into a BERT-style token-ID sequence capped at [MAX_SEQ_LEN].
     *
     * When [vocab] is loaded: true BERT WordPiece tokenisation.
     * When [vocab] is null:   FNV-1a fallback (logged at first call).
     */
    internal fun tokenise(text: String): LongArray {
        return if (vocab != null) {
            wordpieceTokenise(text)
        } else {
            Log.w(TAG, "tokenise(): vocab not loaded — using FNV-1a approximation")
            fnv1aTokenise(text)
        }
    }

    // ── WordPiece tokenisation ────────────────────────────────────────────────

    /**
     * Greedy longest-match WordPiece tokenisation following the standard BERT algorithm:
     *
     *   1. Lowercase the text and split on whitespace / punctuation boundaries.
     *   2. For each word, attempt to greedily decompose into sub-tokens from the vocab.
     *      Continuation pieces are prefixed with "##".
     *   3. If a word cannot be decomposed (no sub-token path found), emit [UNK_ID].
     *   4. The result is wrapped with [CLS_ID] and [SEP_ID] and truncated to [MAX_SEQ_LEN].
     */
    private fun wordpieceTokenise(text: String): LongArray {
        val v = vocab ?: return fnv1aTokenise(text)

        val words = basicTokenise(text)
        val ids   = mutableListOf<Long>()
        ids.add(CLS_ID)

        for (word in words) {
            if (ids.size >= MAX_SEQ_LEN - 1) break  // leave room for [SEP]
            if (word.length > MAX_WORDPIECE_LEN) {
                ids.add(UNK_ID); continue
            }

            val subIds = mutableListOf<Long>()
            var isBad  = false
            var start  = 0

            while (start < word.length) {
                var end   = word.length
                var found = false

                while (start < end) {
                    val substr = if (start == 0) word.substring(start, end)
                                 else "##" + word.substring(start, end)
                    val id = v[substr]
                    if (id != null) {
                        subIds.add(id)
                        found = true
                        start = end
                        break
                    }
                    end--
                }

                if (!found) { isBad = true; break }
            }

            if (isBad) {
                ids.add(UNK_ID)
            } else {
                // Respect MAX_SEQ_LEN: add as many sub-tokens as fit
                val remaining = MAX_SEQ_LEN - 1 - ids.size
                ids.addAll(subIds.take(remaining))
            }
        }

        ids.add(SEP_ID)
        return ids.toLongArray()
    }

    /**
     * Basic pre-tokenisation: lowercase, split on whitespace and punctuation.
     * Produces word strings for WordPiece processing.
     */
    private fun basicTokenise(text: String): List<String> {
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            when {
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
                isPunctuation(ch) -> sb.append(' ').append(ch).append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.split(' ').filter { it.isNotEmpty() }
    }

    private fun isPunctuation(c: Char): Boolean =
        (c in '!'..'/') || (c in ':'..('@')) || (c in '['..('`')) || (c in '{'..('~'))

    // ── FNV-1a fallback tokenisation ──────────────────────────────────────────

    /**
     * FNV-1a-based approximation used when the vocab file is unavailable.
     * Maps each whitespace-split token to a deterministic index in [1, VOCAB_SIZE-1].
     * [CLS]/[SEP] are placed correctly.
     */
    private fun fnv1aTokenise(text: String): LongArray {
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        val maxWords = MAX_SEQ_LEN - 2
        val trimmed  = if (words.size > maxWords) words.subList(0, maxWords) else words

        val ids = LongArray(trimmed.size + 2)
        ids[0] = CLS_ID
        for (i in trimmed.indices) ids[i + 1] = fnv1aHash(trimmed[i])
        ids[ids.size - 1] = SEP_ID
        return ids
    }

    // ── Exposed for tests ─────────────────────────────────────────────────────

    /** FNV-1a 32-bit hash mapped into [1, VOCAB_SIZE - 1]. */
    internal fun fnv1aHash(word: String): Long {
        var hash = 2166136261L
        for (ch in word) {
            hash = hash xor ch.code.toLong()
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return (hash % (VOCAB_SIZE - 1)).coerceAtLeast(1L)
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    internal fun normalise(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm == 0f) return vec
        return FloatArray(vec.size) { vec[it] / norm }
    }

    internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }

    internal fun parseEmbedding(csv: String): FloatArray? =
        try {
            val parts = csv.split(",")
            FloatArray(parts.size) { parts[it].toFloat() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse embedding: ${e.message}")
            null
        }
}
