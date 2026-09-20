package com.lumena.android.llama

/**
 * Thin JNI boundary for the embedded llama.cpp runtime.
 * GGUF files stay outside the APK so models can be changed without reinstalling Lumena.
 */
object LlamaNative {
    init {
        System.loadLibrary("lumena_llama")
    }

    external fun nativeVersion(): String
    external fun nativeGpuInfo(): String
    external fun nativeLoadModel(modelPath: String, gpuLayers: Int = 0): Long
    external fun nativeLoadModelFd(fd: Int, gpuLayers: Int = 0): Long
    external fun nativeProbeModel(modelPath: String): String
    external fun nativeProbeModelFd(fd: Int): String
    external fun nativeLastError(): String
    external fun nativeApplyChatTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean = true
    ): String
    external fun nativeCountTokens(handle: Long, text: String): Int
    external fun nativeFreeModel(handle: Long)
    external fun nativeCancel()
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        contextSize: Int,
        maxTokens: Int,
        temperature: Float,
        threads: Int,
        batchSize: Int
    ): String

    fun isAvailable(): Boolean = runCatching { nativeVersion().isNotBlank() }.getOrDefault(false)
}
