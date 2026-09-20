#include <jni.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

namespace {
std::mutex g_mutex;
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
    return env->NewStringUTF("llama.cpp embedded");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeLoadModel(
        JNIEnv * env, jobject, jstring modelPath, jint gpuLayers) {
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
    return reinterpret_cast<jlong>(g_model);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeFreeModel(
        JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto * model = reinterpret_cast<llama_model *>(handle);
    if (model && model == g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring promptText,
        jint contextSize, jint maxTokens, jfloat temperature) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto * model = reinterpret_cast<llama_model *>(handle);
    if (!model || model != g_model) return env->NewStringUTF("");

    const std::string prompt = jstr(env, promptText);
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const int wanted_ctx = std::max(512, (int) contextSize);
    const int wanted_predict = std::max(1, (int) maxTokens);

    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) return env->NewStringUTF("");
    std::vector<llama_token> tokens((size_t) n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), n_prompt, true, true) < 0) {
        return env->NewStringUTF("");
    }

    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) std::max(wanted_ctx, n_prompt + wanted_predict + 8);
    cp.n_batch = (uint32_t) std::min((int) cp.n_ctx, std::max(512, n_prompt));
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) return env->NewStringUTF("");

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.01f, temperature)));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    std::string response;
    for (int produced = 0; produced < wanted_predict; ++produced) {
        if (llama_decode(ctx, batch) != 0) break;
        const llama_token next = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        response += token_piece(vocab, next);
        batch = llama_batch_get_one(const_cast<llama_token *>(&next), 1);
    }

    llama_sampler_free(sampler);
    llama_free(ctx);
    return env->NewStringUTF(response.c_str());
}
