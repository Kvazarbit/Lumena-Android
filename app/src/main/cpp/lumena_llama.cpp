#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>
#include "llama.h"
#include "ggml-backend.h"

namespace {
std::mutex g_mutex;
std::mutex g_log_mutex;
std::once_flag g_backend_once;
std::atomic<bool> g_cancel_requested{false};
llama_model * g_model = nullptr;
std::string g_last_log;

void append_log(const char * text) {
    if (!text || !text[0]) return;
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_log.append(text);
    constexpr size_t MAX_LOG = 24 * 1024;
    if (g_last_log.size() > MAX_LOG) {
        g_last_log.erase(0, g_last_log.size() - MAX_LOG);
    }
}

void llama_log_capture(enum ggml_log_level, const char * text, void *) {
    append_log(text);
}

void clear_last_log() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_log.clear();
}

std::string last_log_copy() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return g_last_log;
}

void ensure_backend_init() {
    std::call_once(g_backend_once, [] {
        llama_log_set(llama_log_capture, nullptr);
        llama_backend_init();
        ggml_backend_load_all();
    });
}

llama_model_params model_params_for(jint gpuLayers) {
    auto params = llama_model_default_params();
    params.n_gpu_layers = gpuLayers < 0 ? -1 : std::max(0, (int) gpuLayers);
    return params;
}


llama_model_params probe_model_params() {
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.no_alloc = true;
    params.vocab_only = false;
    params.check_tensors = false;
    return params;
}

std::string model_probe_summary(llama_model * model) {
    if (!model) {
        return std::string("ERROR\n") + last_log_copy();
    }

    char desc[1024] = {0};
    llama_model_desc(model, desc, sizeof(desc));

    char arch[256] = {0};
    const int arch_n = llama_model_meta_val_str(
        model,
        "general.architecture",
        arch,
        sizeof(arch)
    );

    std::string out = "OK\n";
    out += "architecture=";
    out += arch_n >= 0 ? arch : "(unknown)";
    out += "\n";
    out += "description=";
    out += desc[0] ? desc : "(unknown)";
    out += "\n";
    out += "model_size_bytes=" + std::to_string(llama_model_size(model)) + "\n";
    out += "parameters=" + std::to_string(llama_model_n_params(model)) + "\n";
    return out;
}

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
#ifdef GGML_USE_VULKAN
    return env->NewStringUTF("llama.cpp embedded adaptive + vulkan");
#else
    return env->NewStringUTF("llama.cpp embedded adaptive + cpu");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeGpuInfo(JNIEnv * env, jobject) {
    ensure_backend_init();
    const size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) continue;
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            const char * desc = ggml_backend_dev_description(dev);
            const char * name = ggml_backend_dev_name(dev);
            const char * value = (desc && desc[0]) ? desc : name;
            return env->NewStringUTF(value ? value : "Vulkan GPU");
        }
    }
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeProbeModel(
        JNIEnv * env, jobject, jstring modelPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    clear_last_log();
    ensure_backend_init();

    const std::string path = jstr(env, modelPath);
    if (path.empty()) {
        return env->NewStringUTF("ERROR\nModel path is empty.");
    }

    llama_model * probe = llama_model_load_from_file(path.c_str(), probe_model_params());
    const std::string summary = model_probe_summary(probe);
    if (probe) llama_model_free(probe);
    return env->NewStringUTF(summary.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeProbeModelFd(
        JNIEnv * env, jobject, jint fd) {
    std::lock_guard<std::mutex> lock(g_mutex);
    clear_last_log();

    if (fd < 0) {
        return env->NewStringUTF("ERROR\nInvalid Android file descriptor.");
    }

    const int dup_fd = ::dup(fd);
    if (dup_fd < 0) {
        const std::string error = std::string("ERROR\ndup(fd) failed: ") + std::strerror(errno);
        return env->NewStringUTF(error.c_str());
    }

    FILE * file = ::fdopen(dup_fd, "rb");
    if (!file) {
        const std::string error = std::string("ERROR\nfdopen failed: ") + std::strerror(errno);
        ::close(dup_fd);
        return env->NewStringUTF(error.c_str());
    }

    ensure_backend_init();
    llama_model * probe = llama_model_load_from_file_ptr(file, probe_model_params());
    const std::string summary = model_probe_summary(probe);
    if (probe) llama_model_free(probe);
    ::fclose(file);
    return env->NewStringUTF(summary.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeLoadModel(
        JNIEnv * env, jobject, jstring modelPath, jint gpuLayers) {
    g_cancel_requested.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lock(g_mutex);
    clear_last_log();

    const std::string path = jstr(env, modelPath);
    if (path.empty()) {
        append_log("Model path is empty.\n");
        return 0;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    ensure_backend_init();
    g_model = llama_model_load_from_file(path.c_str(), model_params_for(gpuLayers));
    if (!g_model) append_log("llama_model_load_from_file returned null.\n");
    g_cancel_requested.store(false, std::memory_order_release);
    return reinterpret_cast<jlong>(g_model);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeLoadModelFd(
        JNIEnv *, jobject, jint fd, jint gpuLayers) {
    g_cancel_requested.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> lock(g_mutex);
    clear_last_log();

    if (fd < 0) {
        append_log("Invalid Android file descriptor.\n");
        return 0;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    const int dup_fd = ::dup(fd);
    if (dup_fd < 0) {
        append_log((std::string("dup(fd) failed: ") + std::strerror(errno) + "\n").c_str());
        return 0;
    }

    FILE * file = ::fdopen(dup_fd, "rb");
    if (!file) {
        append_log((std::string("fdopen failed: ") + std::strerror(errno) + "\n").c_str());
        ::close(dup_fd);
        return 0;
    }

    ensure_backend_init();
    g_model = llama_model_load_from_file_ptr(file, model_params_for(gpuLayers));
    ::fclose(file);

    if (!g_model) append_log("llama_model_load_from_file_ptr returned null.\n");
    g_cancel_requested.store(false, std::memory_order_release);
    return reinterpret_cast<jlong>(g_model);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeLastError(JNIEnv * env, jobject) {
    const std::string log = last_log_copy();
    return env->NewStringUTF(log.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeApplyChatTemplate(
        JNIEnv * env,
        jobject,
        jlong handle,
        jobjectArray rolesArray,
        jobjectArray contentsArray,
        jboolean addAssistant) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto * model = reinterpret_cast<llama_model *>(handle);
    if (!model || model != g_model) {
        append_log("nativeApplyChatTemplate called without an active model.\n");
        return env->NewStringUTF("");
    }

    const jsize n_roles = rolesArray ? env->GetArrayLength(rolesArray) : 0;
    const jsize n_contents = contentsArray ? env->GetArrayLength(contentsArray) : 0;
    if (n_roles <= 0 || n_roles != n_contents) {
        append_log("Chat template roles/contents length mismatch.\n");
        return env->NewStringUTF("");
    }

    std::vector<std::string> roles;
    std::vector<std::string> contents;
    roles.reserve((size_t) n_roles);
    contents.reserve((size_t) n_roles);

    for (jsize i = 0; i < n_roles; ++i) {
        auto roleObj = (jstring) env->GetObjectArrayElement(rolesArray, i);
        auto contentObj = (jstring) env->GetObjectArrayElement(contentsArray, i);
        roles.push_back(jstr(env, roleObj));
        contents.push_back(jstr(env, contentObj));
        if (roleObj) env->DeleteLocalRef(roleObj);
        if (contentObj) env->DeleteLocalRef(contentObj);
    }

    std::vector<llama_chat_message> chat;
    chat.reserve((size_t) n_roles);
    for (jsize i = 0; i < n_roles; ++i) {
        llama_chat_message msg {
            roles[(size_t) i].c_str(),
            contents[(size_t) i].c_str()
        };
        chat.push_back(msg);
    }

    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl || !tmpl[0]) {
        append_log("GGUF model has no default chat template.\n");
        return env->NewStringUTF("");
    }

    size_t initial = 1024;
    for (const auto & role : roles) initial += role.size();
    for (const auto & content : contents) initial += content.size() * 2;
    std::vector<char> buffer(std::max<size_t>(initial, 4096));

    int32_t needed = llama_chat_apply_template(
        tmpl,
        chat.data(),
        chat.size(),
        addAssistant == JNI_TRUE,
        buffer.data(),
        (int32_t) buffer.size()
    );

    if (needed < 0) {
        append_log("llama_chat_apply_template failed for this GGUF template.\n");
        return env->NewStringUTF("");
    }

    if ((size_t) needed >= buffer.size()) {
        buffer.resize((size_t) needed + 1);
        needed = llama_chat_apply_template(
            tmpl,
            chat.data(),
            chat.size(),
            addAssistant == JNI_TRUE,
            buffer.data(),
            (int32_t) buffer.size()
        );
        if (needed < 0) {
            append_log("llama_chat_apply_template failed after resize.\n");
            return env->NewStringUTF("");
        }
    }

    return env->NewStringUTF(std::string(buffer.data(), (size_t) needed).c_str());
}


extern "C" JNIEXPORT jint JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeCountTokens(
        JNIEnv * env, jobject, jlong handle, jstring textValue) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto * model = reinterpret_cast<llama_model *>(handle);
    if (!model || model != g_model) return 0;

    const std::string text = jstr(env, textValue);
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const int needed = llama_tokenize(
        vocab,
        text.c_str(),
        text.size(),
        nullptr,
        0,
        true,
        true
    );
    if (needed >= 0) return needed;
    return -needed;
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

    // Kotlin pre-fits chat history with this exact tokenizer. Never silently
    // drop the beginning of a formatted prompt because it can remove system/template tokens.
    const int max_prompt_tokens = std::max(32, wanted_ctx - wanted_predict - 8);
    if ((int) tokens.size() > max_prompt_tokens) {
        append_log(
            ("Prompt exceeds exact token budget: " +
             std::to_string(tokens.size()) + " > " +
             std::to_string(max_prompt_tokens) + "\n").c_str()
        );
        return env->NewStringUTF("");
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
