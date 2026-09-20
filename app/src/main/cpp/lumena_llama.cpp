#include <jni.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

namespace {
std::mutex g_mutex;
std::atomic<bool> g_cancel_requested{false};
llama_model * g_model = nullptr;

std::string jstr(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * raw = env->GetStringUTFChars(value, nullptr);
    std::string out(raw ? raw : "");
    if (raw) env->ReleaseStringUTFChars(value, raw);
    return out;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buf(256);
    int n = llama_token_to_piece(vocab, token, buf.data(), (int) buf.size(), 0, true);
    if (n < 0) {
        buf.resize((size_t) -n);
        n = llama_token_to_piece(vocab, token, buf.data(), (int) buf.size(), 0, true);
    }
    return n > 0 ? std::string(buf.data(), (size_t) n) : std::string();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeVersion(JNIEnv * env, jobject) {
    return env->NewStringUTF("llama.cpp embedded adaptive");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeLoadModel(
        JNIEnv * env, jobject, jstring modelPath, jint gpuLayers) {
    // If a load is requested while generation is still finishing, ask generation to exit first.
    g_cancel_requested.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lock(g_mutex);
    const std::string path = jstr(env, modelPath);
    if (path.empty()) return 0;
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_init();
    ggml_backend_load_all();
    auto params = llama_model_default_params();
    params.n_gpu_layers = std::max(0, (int) gpuLayers);
    g_model = llama_model_load_from_file(path.c_str(), params);
    g_cancel_requested.store(false, std::memory_order_release);
    return reinterpret_cast<jlong>(g_model);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeFreeModel(
        JNIEnv *, jobject, jlong handle) {
    g_cancel_requested.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lock(g_mutex);
    auto * model = reinterpret_cast<llama_model *>(handle);
    if (model && model == g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeCancel(JNIEnv *, jobject) {
    // Never take g_mutex here: nativeGenerate intentionally owns it for the whole run.
    // This atomic flag is what makes STOP able to interrupt inference instead of only the UI.
    g_cancel_requested.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring promptText,
        jint contextSize, jint maxTokens, jfloat temperature,
        jint threads, jint batchSize) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto * model = reinterpret_cast<llama_model *>(handle);
    if (!model || model != g_model) return env->NewStringUTF("");

    g_cancel_requested.store(false, std::memory_order_release);

    const std::string prompt = jstr(env, promptText);
    const llama_vocab * vocab = llama_model_get_vocab(model);

    const int model_ctx = std::max(512, (int) llama_model_n_ctx_train(model));
    const int wanted_ctx = std::clamp((int) contextSize, 512, std::min(model_ctx, 32768));
    const int wanted_predict = std::clamp(
        (int) maxTokens,
        1,
        std::max(1, wanted_ctx / 3)
    );
    const int wanted_threads = std::clamp((int) threads, 1, 32);
    const int wanted_batch = std::clamp((int) batchSize, 32, wanted_ctx);

    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) return env->NewStringUTF("");

    std::vector<llama_token> tokens((size_t) n_prompt);
    const int tokenized = llama_tokenize(
        vocab, prompt.c_str(), prompt.size(), tokens.data(), n_prompt, true, true
    );
    if (tokenized < 0) return env->NewStringUTF("");
    tokens.resize((size_t) tokenized);

    // Hard memory guard: context never grows just because chat history grew.
    const int max_prompt_tokens = std::max(32, wanted_ctx - wanted_predict - 8);
    if ((int) tokens.size() > max_prompt_tokens) {
        tokens.erase(tokens.begin(), tokens.end() - max_prompt_tokens);
    }

    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) wanted_ctx;
    cp.n_batch = (uint32_t) wanted_batch;
    cp.n_ubatch = (uint32_t) std::min(wanted_batch, 256);
    cp.n_threads = wanted_threads;
    cp.n_threads_batch = wanted_threads;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) return env->NewStringUTF("");

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.01f, temperature)));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    bool decode_failed = false;
    for (size_t offset = 0; offset < tokens.size(); offset += (size_t) wanted_batch) {
        if (g_cancel_requested.load(std::memory_order_acquire)) break;
        const int count = (int) std::min((size_t) wanted_batch, tokens.size() - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(ctx, batch) != 0) {
            decode_failed = true;
            break;
        }
    }

    std::string response;
    if (!decode_failed && !g_cancel_requested.load(std::memory_order_acquire)) {
        for (int produced = 0; produced < wanted_predict; ++produced) {
            if (g_cancel_requested.load(std::memory_order_acquire)) break;

            const llama_token next = llama_sampler_sample(sampler, ctx, -1);
            if (llama_vocab_is_eog(vocab, next)) break;
            response += token_piece(vocab, next);

            llama_token decoded = next;
            llama_batch next_batch = llama_batch_get_one(&decoded, 1);
            if (llama_decode(ctx, next_batch) != 0) break;
        }
    }

    llama_sampler_free(sampler);
    llama_free(ctx);
    return env->NewStringUTF(response.c_str());
}
