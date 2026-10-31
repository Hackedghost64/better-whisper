#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <algorithm>
#include <android/log.h>
#include "llama.h"
#include "common.h"
#include "sampling.h"

#define TAG "LlamaIntentJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

struct IntentContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    common_sampler* sampler = nullptr;
    int n_threads = 4;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_betterwhisper_core_intent_LlamaIntentEngine_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring jModelPath,
    jint nThreads
) {
    if (!jModelPath) {
        LOGE("nativeInit: null model path provided");
        return 0;
    }

    const char* model_path = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("Initializing LlamaIntentEngine with model: %s (threads: %d)", model_path, nThreads);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jModelPath, model_path);

    if (!model) {
        LOGE("Failed to load model from file");
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 1024; // Compact context suitable for conversational speech repairs
    ctx_params.n_batch = 256;
    ctx_params.n_ubatch = 256;
    ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
    ctx_params.n_threads_batch = nThreads > 0 ? nThreads : 4;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create llama_context");
        llama_model_free(model);
        return 0;
    }

    common_params_sampling sparams;
    sparams.temp = 0.0f; // Deterministic greedy decoding
    sparams.top_p = 1.0f;
    common_sampler* sampler = common_sampler_init(model, sparams);

    auto* intentCtx = new IntentContext();
    intentCtx->model = model;
    intentCtx->ctx = ctx;
    intentCtx->sampler = sampler;
    intentCtx->n_threads = ctx_params.n_threads;

    LOGI("Successfully initialized IntentContext at %p", intentCtx);
    return reinterpret_cast<jlong>(intentCtx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_betterwhisper_core_intent_LlamaIntentEngine_nativeRepair(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jPrompt,
    jint maxTokens
) {
    auto* intentCtx = reinterpret_cast<IntentContext*>(handle);
    if (!intentCtx || !intentCtx->ctx || !intentCtx->model) {
        LOGE("Invalid handle passed to nativeRepair");
        return env->NewStringUTF("");
    }

    const char* prompt_str = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(prompt_str ? prompt_str : "");
    if (prompt_str) {
        env->ReleaseStringUTFChars(jPrompt, prompt_str);
    }

    if (prompt.empty()) {
        return env->NewStringUTF("");
    }

    // Clear KV cache from any previous run
    llama_memory_clear(llama_get_memory(intentCtx->ctx), false);
    if (intentCtx->sampler) {
        common_sampler_reset(intentCtx->sampler);
    }

    // Tokenize prompt
    const llama_vocab* vocab = llama_model_get_vocab(intentCtx->model);
    std::vector<llama_token> tokens = common_tokenize(intentCtx->ctx, prompt, true, true);
    if (tokens.empty()) {
        LOGW("Empty token stream produced for prompt");
        return env->NewStringUTF("");
    }

    // Process prompt in batches
    common_batch batch(intentCtx->ctx);
    int n_tokens = static_cast<int>(tokens.size());
    for (int i = 0; i < n_tokens; i += 256) {
        int cur_batch = std::min(n_tokens - i, 256);
        batch.clear();
        for (int j = 0; j < cur_batch; j++) {
            bool want_logits = (i + j == n_tokens - 1);
            batch.add(tokens[i + j], i + j, 0, want_logits);
        }
        if (llama_process(intentCtx->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("llama_process failed during prompt evaluation");
            return env->NewStringUTF("");
        }
    }

    // Autoregressive generation loop
    std::string outputText;
    int cur_pos = n_tokens;
    int limit = maxTokens > 0 ? maxTokens : 48;

    for (int i = 0; i < limit; i++) {
        llama_token new_token = common_sampler_sample(intentCtx->sampler, intentCtx->ctx, -1);
        common_sampler_accept(intentCtx->sampler, new_token, true);

        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        std::string piece = common_token_to_piece(intentCtx->ctx, new_token);
        // Early stop on delimiter or end tokens
        if (piece.find("<|im_end|>") != std::string::npos ||
            piece.find("<|endoftext|>") != std::string::npos) {
            break;
        }

        // Only break on newline once non-empty text has started
        if (piece.find("\n") != std::string::npos) {
            if (!outputText.empty()) {
                break;
            }
            continue; // Skip leading newline before assistant reply
        }

        outputText += piece;

        // Decode new token for next step
        batch.clear();
        batch.add(new_token, cur_pos++, 0, true);
        if (llama_process(intentCtx->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("llama_process decode failed during token generation");
            break;
        }
    }

    // Trim whitespace and outer quotation marks
    size_t first = outputText.find_first_not_of(" \t\r\n\"");
    size_t last = outputText.find_last_not_of(" \t\r\n\"");
    if (first != std::string::npos && last != std::string::npos) {
        outputText = outputText.substr(first, last - first + 1);
    } else {
        outputText.clear();
    }

    return env->NewStringUTF(outputText.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_betterwhisper_core_intent_LlamaIntentEngine_nativeRelease(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    auto* intentCtx = reinterpret_cast<IntentContext*>(handle);
    if (!intentCtx) return;

    if (intentCtx->sampler) {
        common_sampler_free(intentCtx->sampler);
    }
    if (intentCtx->ctx) {
        llama_free(intentCtx->ctx);
    }
    if (intentCtx->model) {
        llama_model_free(intentCtx->model);
    }
    delete intentCtx;
    LOGI("Released IntentContext");
}
