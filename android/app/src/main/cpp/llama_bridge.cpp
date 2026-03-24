/**
 * llama_bridge.cpp
 *
 * Full JNI bridge between Kotlin and llama.cpp.
 * Implements: backendInit, initModel, freeModel, tokenize, decode,
 *             streamGenerate, cancelGenerate, saveKvCache, restoreKvCache.
 *
 * Threading model: all functions are synchronous; callers on Kotlin side
 * must dispatch to a background thread (e.g., Dispatchers.IO).
 * cancelGenerate() is safe to call from any thread — it sets an atomic flag
 * that the generation loop checks on every token.
 */

#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <cstring>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Backend initialisation (call once) ──────────────────────────────────────

static std::once_flag g_backend_init_flag;

static void ensure_backend_init() {
    std::call_once(g_backend_init_flag, []() {
        llama_backend_init();
        LOGI("llama backend initialised");
    });
}

// ── Context wrapper ──────────────────────────────────────────────────────────

struct LlamaHandle {
    llama_model*        model          = nullptr;
    llama_context*      ctx            = nullptr;
    std::mutex          mu;                        // serialise access per-handle
    std::atomic<bool>   cancel_flag{false};        // set by cancelGenerate()
};

static LlamaHandle* to_handle(jlong ptr) {
    return reinterpret_cast<LlamaHandle*>(static_cast<uintptr_t>(ptr));
}

// ── JNI helper: throw RuntimeException ──────────────────────────────────────

static void jni_throw(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, msg);
}

// ── TokenCallback helpers ────────────────────────────────────────────────────

struct CallbackRefs {
    jmethodID on_token    = nullptr;
    jmethodID on_complete = nullptr;
    jmethodID on_error    = nullptr;
};

static CallbackRefs get_callback_refs(JNIEnv* env, jobject cb) {
    CallbackRefs refs;
    jclass cls = env->GetObjectClass(cb);
    refs.on_token    = env->GetMethodID(cls, "onToken",    "(Ljava/lang/String;)V");
    refs.on_complete = env->GetMethodID(cls, "onComplete", "()V");
    refs.on_error    = env->GetMethodID(cls, "onError",    "(Ljava/lang/String;)V");
    return refs;
}

static void cb_error(JNIEnv* env, jobject cb, const CallbackRefs& refs, const char* msg) {
    LOGE("%s", msg);
    if (refs.on_error) {
        jstring jmsg = env->NewStringUTF(msg);
        env->CallVoidMethod(cb, refs.on_error, jmsg);
        env->DeleteLocalRef(jmsg);
    }
}

// ────────────────────────────────────────────────────────────────────────────
// JNI exports
// ────────────────────────────────────────────────────────────────────────────

extern "C" {

// ---------------------------------------------------------------------------
// backendInit() — call once before any model is loaded
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_ernos_mobile_LlamaRuntime_nativeBackendInit(JNIEnv* /*env*/, jobject /*obj*/) {
    ensure_backend_init();
}

// ---------------------------------------------------------------------------
// initModel(modelPath, nCtx, nThreads, nGpuLayers) → handle (Long)
// Returns 0 on failure.
// ---------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_ernos_mobile_LlamaRuntime_nativeInitModel(
        JNIEnv*  env,
        jobject  /*obj*/,
        jstring  j_path,
        jint     n_ctx,
        jint     n_threads,
        jint     n_gpu_layers)
{
    ensure_backend_init();

    const char* path = env->GetStringUTFChars(j_path, nullptr);
    LOGI("Loading model: %s  ctx=%d threads=%d gpu_layers=%d",
         path, n_ctx, n_threads, n_gpu_layers);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(j_path, path);

    if (!model) {
        LOGE("Failed to load model from file");
        jni_throw(env, "Failed to load model – check path and file integrity");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx          = static_cast<uint32_t>(n_ctx);
    cparams.n_threads      = static_cast<int32_t>(n_threads);
    cparams.n_threads_batch = static_cast<int32_t>(n_threads);
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        jni_throw(env, "Failed to create llama context");
        return 0L;
    }

    LOGI("Model loaded successfully");

    auto* handle = new LlamaHandle{model, ctx};
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(handle));
}

// ---------------------------------------------------------------------------
// freeModel(handle)
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_ernos_mobile_LlamaRuntime_nativeFreeModel(
        JNIEnv* /*env*/,
        jobject /*obj*/,
        jlong   j_handle)
{
    if (!j_handle) return;
    auto* h = to_handle(j_handle);
    std::lock_guard<std::mutex> lock(h->mu);
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
    LOGI("Model freed");
}

// ---------------------------------------------------------------------------
// cancelGenerate(handle) — signal the generation loop to stop.
// Safe to call from any thread (e.g., the UI thread when Stop is tapped).
// The loop checks this flag on every token and exits cleanly.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_ernos_mobile_LlamaRuntime_nativeCancelGenerate(
        JNIEnv* /*env*/,
        jobject /*obj*/,
        jlong   j_handle)
{
    if (!j_handle) return;
    auto* h = to_handle(j_handle);
    h->cancel_flag.store(true, std::memory_order_relaxed);
    LOGI("cancelGenerate requested");
}

// ---------------------------------------------------------------------------
// tokenize(handle, text) → IntArray of token IDs
// ---------------------------------------------------------------------------
JNIEXPORT jintArray JNICALL
Java_com_ernos_mobile_LlamaRuntime_nativeTokenize(
        JNIEnv*  env,
        jobject  /*obj*/,
        jlong    j_handle,
        jstring  j_text)
{
    auto* h = to_handle(j_handle);
    if (!h) return nullptr;

    const char* text = env->GetStringUTFChars(j_text, nullptr);
    int text_len = static_cast<int>(strlen(text));

    // Initial allocation – resize if needed
    std::vector<llama_token> tokens(text_len + 32);
    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    int n = llama_tokenize(vocab, text, text_len,
                           tokens.data(), static_cast<int32_t>(tokens.size()),
                           /* add_special= */ true,
                           /* parse_special= */ true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text, text_len,
                           tokens.data(), static_cast<int32_t>(tokens.size()),
                           true, true);
    }
    env->ReleaseStringUTFChars(j_text, text);

    if (n < 0) {
        LOGE("Tokenization failed");
        return nullptr;
    }

    jintArray result = env->NewIntArray(n);
    env->SetIntArrayRegion(result, 0, n,
                           reinterpret_cast<const jint*>(tokens.data()));
    return result;
}

// ---------------------------------------------------------------------------
// decode(handle, tokenIds) — process a batch of tokens through the model
// Returns true on success.
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_ernos_mobile_LlamaRuntime_nativeDecode(
        JNIEnv*   env,
        jobject   /*obj*/,
        jlong     j_handle,
        jintArray j_tokens)
{
    auto* h = to_handle(j_handle);
    if (!h) return JNI_FALSE;

    jsize n = env->GetArrayLength(j_tokens);
    std::vector<llama_token> tokens(n);
    env->GetIntArrayRegion(j_tokens, 0, n,
                           reinterpret_cast<jint*>(tokens.data()));

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(n));
    int ret = llama_decode(h->ctx, batch);
    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// streamGenerate(handle, prompt, maxTokens, temperature, topP,
//                presencePenalty, callback)
//
// Runs the full generate-sample-decode loop, calling callback.onToken() for
// each produced token piece, callback.onComplete() when done, and
// callback.onError() on failure.  The KV cache is cleared before the prompt
// is evaluated so each call starts with a fresh context.
//
// presencePenalty: applied as a repetition-penalty stage in the sampler
//   chain before temperature.  0.0 = no penalty; positive values penalise
//   tokens that have already appeared at least once.
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_ernos_mobile_LlamaRuntime_nativeStreamGenerate(
        JNIEnv*  env,
        jobject  /*obj*/,
        jlong    j_handle,
        jstring  j_prompt,
        jint     max_tokens,
        jfloat   temperature,
        jfloat   top_p,
        jfloat   presence_penalty,
        jobject  callback)
{
    auto* h = to_handle(j_handle);
    CallbackRefs refs = get_callback_refs(env, callback);

    if (!h || !h->model || !h->ctx) {
        cb_error(env, callback, refs, "Model not initialised");
        return;
    }

    const char* prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    // ── Tokenise prompt ─────────────────────────────────────────────────────
    std::vector<llama_token> prompt_tokens(prompt.size() + 64);
    const llama_vocab* vocab = llama_model_get_vocab(h->model);
    int n_prompt = llama_tokenize(
            vocab,
            prompt.c_str(), static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
            /* add_special= */ true,
            /* parse_special= */ true);

    if (n_prompt < 0) {
        prompt_tokens.resize(-n_prompt);
        n_prompt = llama_tokenize(
                vocab,
                prompt.c_str(), static_cast<int32_t>(prompt.size()),
                prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
                true, true);
    }
    if (n_prompt < 0) {
        cb_error(env, callback, refs, "Tokenization failed");
        return;
    }
    prompt_tokens.resize(n_prompt);

    // Check context limit
    int n_ctx = static_cast<int>(llama_n_ctx(h->ctx));
    if (n_prompt >= n_ctx - 4) {
        cb_error(env, callback, refs, "Prompt is too long for context window");
        return;
    }

    std::lock_guard<std::mutex> lock(h->mu);

    // ── Reset cancellation flag for this new run ─────────────────────────────
    h->cancel_flag.store(false, std::memory_order_relaxed);

    // ── Clear KV cache for fresh generation ─────────────────────────────────
    llama_memory_clear(llama_get_memory(h->ctx), true);

    // ── Decode prompt ────────────────────────────────────────────────────────
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), n_prompt);
    if (llama_decode(h->ctx, batch) != 0) {
        cb_error(env, callback, refs, "Failed to evaluate prompt");
        return;
    }

    // ── Set up sampler chain ─────────────────────────────────────────────────
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);

    // Presence penalty (applied first so tokens that appear in the prompt
    // are penalised before temperature scaling).
    // penalty_last_n = -1 → apply penalty to the full context window.
    //   In llama.cpp, -1 is the sentinel for n_ctx (all tokens in context).
    //   Using 0 would DISABLE the penalty, so -1 is the correct value here.
    // penalty_repeat = 1.0 → no multiplicative repetition penalty
    // penalty_freq   = 0.0 → no frequency-based scaling
    // penalty_present        additive penalty per unique token seen
    if (presence_penalty > 0.0f) {
        llama_sampler_chain_add(sampler,
            llama_sampler_init_penalties(
                /*penalty_last_n*/  -1,       // -1 = full context (n_ctx)
                /*penalty_repeat*/   1.0f,    // no multiplicative repetition penalty
                /*penalty_freq*/     0.0f,    // no frequency penalty
                /*penalty_present*/  presence_penalty));
    }

    // Temperature → top-p → distribution sampler
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ── Generation loop ──────────────────────────────────────────────────────
    // Use a std::string for token pieces so size is not bounded by a fixed
    // stack buffer — llama_token_to_piece can return up to ~8 bytes per token
    // in practice, but we allow up to 1024 to be safe with special tokens.
    static constexpr int PIECE_BUF_CAP = 1024;
    std::string piece(PIECE_BUF_CAP, '\0');
    int n_gen = 0;

    for (;;) {
        if (n_gen >= max_tokens) break;

        // ── Cooperative cancellation check ───────────────────────────────────
        if (h->cancel_flag.load(std::memory_order_relaxed)) {
            LOGI("Generation cancelled by user at token %d", n_gen);
            break;
        }

        llama_token new_token = llama_sampler_sample(sampler, h->ctx, -1);
        llama_sampler_accept(sampler, new_token);

        // End-of-generation check
        if (llama_vocab_is_eog(vocab, new_token)) break;

        // Convert token to UTF-8 piece.
        // Pass capacity-1 so there is always room for a null terminator.
        int len = llama_token_to_piece(vocab, new_token,
                                       piece.data(),
                                       static_cast<int>(piece.size()) - 1,
                                       /* lstrip= */ 0,
                                       /* special= */ true);

        if (len < 0) {
            // Buffer was too small (shouldn't happen with 1023 bytes, but handle it)
            LOGW("token_to_piece returned %d — skipping token %d", len, new_token);
        } else if (len > 0) {
            // Safe: len < piece.size(), so piece[len] is within bounds
            piece[static_cast<size_t>(len)] = '\0';
            jstring jpiece = env->NewStringUTF(piece.data());
            env->CallVoidMethod(callback, refs.on_token, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        // Decode the newly sampled token
        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(h->ctx, next_batch) != 0) {
            LOGW("Decode failed at token %d — stopping generation", n_gen);
            break;
        }

        n_gen++;
    }

    llama_sampler_free(sampler);
    LOGI("Generation complete: %d tokens", n_gen);
    env->CallVoidMethod(callback, refs.on_complete);
}

// ---------------------------------------------------------------------------
// saveKvCache(handle, filePath) → Boolean
//
// Serializes the entire KV cache for the given context to a binary file using
// llama_state_save_file.  The file is opaque and must be restored with the
// same model weights, nCtx, and llama.cpp version.
//
// Returns JNI_TRUE on success, JNI_FALSE on failure.
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_ernos_mobile_LlamaRuntime_nativeSaveKvCache(
        JNIEnv*  env,
        jobject  /*obj*/,
        jlong    j_handle,
        jstring  j_path)
{
    auto* h = to_handle(j_handle);
    if (!h || !h->ctx) {
        LOGE("nativeSaveKvCache: invalid handle");
        return JNI_FALSE;
    }

    const char* path = env->GetStringUTFChars(j_path, nullptr);
    LOGI("nativeSaveKvCache: saving to %s", path);

    // llama_state_save_file persists the full KV cache and sampler state.
    // Returns the number of bytes written on success, 0 on failure.
    bool saved = llama_state_save_file(h->ctx, path, nullptr, 0);

    env->ReleaseStringUTFChars(j_path, path);

    if (!saved) {
        LOGE("nativeSaveKvCache: llama_state_save_file failed");
        return JNI_FALSE;
    }

    LOGI("nativeSaveKvCache: saved successfully");
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
// restoreKvCache(handle, filePath) → Int
//
// Loads KV cache state from a file written by saveKvCache.  Must be called
// with the exact same model handle (same weights, nCtx, build).
//
// Returns the number of tokens restored on success (≥ 0), or -1 on failure.
// After a successful restore, only tokens AFTER the restored position should
// be submitted to decode/streamGenerate.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_ernos_mobile_LlamaRuntime_nativeRestoreKvCache(
        JNIEnv*  env,
        jobject  /*obj*/,
        jlong    j_handle,
        jstring  j_path)
{
    auto* h = to_handle(j_handle);
    if (!h || !h->ctx) {
        LOGE("nativeRestoreKvCache: invalid handle");
        return -1;
    }

    const char* path = env->GetStringUTFChars(j_path, nullptr);
    LOGI("nativeRestoreKvCache: restoring from %s", path);

    // llama_state_load_file populates the context KV cache from the file.
    // Returns the number of bytes read on success, 0 on failure.
    // The tokens_out / n_token_capacity parameters let llama.cpp report the
    // number of tokens it restored; we pass a small buffer to read that count.
    static constexpr int MAX_TOKENS = 131072;  // 128 K token context upper bound
    std::vector<llama_token> token_buf(MAX_TOKENS);
    size_t n_restored = 0;
    bool loaded = llama_state_load_file(
            h->ctx, path,
            token_buf.data(), token_buf.size(),
            &n_restored);

    env->ReleaseStringUTFChars(j_path, path);

    if (!loaded) {
        LOGE("nativeRestoreKvCache: llama_state_load_file failed");
        return -1;
    }

    LOGI("nativeRestoreKvCache: restored %zu tokens", n_restored);
    return static_cast<jint>(n_restored);
}

} // extern "C"
