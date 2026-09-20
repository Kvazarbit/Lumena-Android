#include <jni.h>
#include <string>
#include "llama.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeVersion(JNIEnv *env, jobject) {
    std::string value = "llama.cpp embedded";
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeLoadModel(
        JNIEnv *env, jobject, jstring modelPath, jint gpuLayers) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    llama_backend_init();
    auto params = llama_model_default_params();
    params.n_gpu_layers = gpuLayers;
    llama_model *model = llama_model_load_from_file(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(model);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumena_android_llama_LlamaNative_nativeFreeModel(
        JNIEnv *, jobject, jlong handle) {
    auto *model = reinterpret_cast<llama_model *>(handle);
    if (model) llama_model_free(model);
}
