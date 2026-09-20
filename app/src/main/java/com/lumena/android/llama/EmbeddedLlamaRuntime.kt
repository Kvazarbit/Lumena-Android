package com.lumena.android.llama

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * One process-wide embedded runtime.
 *
 * The GGUF stays loaded between chat turns. Switching modelRef unloads the old model
 * exactly once and loads the newly selected model.
 */
object EmbeddedLlamaRuntime {
    private const val GIB = 1024.0 * 1024.0 * 1024.0
    private val gate = Mutex()

    @Volatile private var handle: Long = 0
    @Volatile private var loadedModelRef: String = ""
    @Volatile private var loadedComputeMode: String = "auto"
    @Volatile private var loadedGpuLayers: Int = 0

    suspend fun generate(
        context: Context,
        modelRef: String,
        prompt: String,
        profile: LlamaRuntimeProfile,
        computeMode: String,
        temperature: Float
    ): String = gate.withLock {
        withContext(Dispatchers.IO) {
            ensureLoaded(context.applicationContext, modelRef, profile, computeMode)
            LlamaNative.nativeGenerate(
                handle = handle,
                prompt = prompt,
                contextSize = profile.contextSize,
                maxTokens = profile.maxTokens,
                temperature = temperature,
                threads = profile.threads,
                batchSize = profile.batchSize
            )
        }
    }

    fun cancelActiveGeneration() {
        LlamaNative.nativeCancel()
    }

    fun backendLabel(): String = when {
        handle == 0L -> "not loaded yet"
        loadedGpuLayers < 0 -> "Vulkan GPU · full offload"
        loadedGpuLayers > 0 -> "Vulkan GPU · $loadedGpuLayers layers"
        else -> "CPU"
    }

    fun requestedComputeLabel(): String = when (loadedComputeMode) {
        "cpu" -> "CPU"
        "gpu" -> "Vulkan GPU"
        else -> "Auto"
    }

    suspend fun unload() = gate.withLock {
        withContext(Dispatchers.IO) {
            val current = handle
            handle = 0
            loadedModelRef = ""
            loadedComputeMode = "auto"
            loadedGpuLayers = 0
            if (current != 0L) LlamaNative.nativeFreeModel(current)
        }
    }

    private fun ensureLoaded(
        context: Context,
        modelRef: String,
        profile: LlamaRuntimeProfile,
        computeMode: String
    ) {
        require(modelRef.isNotBlank()) { "Choose a GGUF model first" }
        val normalizedMode = computeMode.takeIf { it in setOf("auto", "cpu", "gpu") } ?: "auto"
        if (handle != 0L && loadedModelRef == modelRef && loadedComputeMode == normalizedMode) return

        val old = handle
        handle = 0
        loadedModelRef = ""
        loadedComputeMode = "auto"
        loadedGpuLayers = 0
        if (old != 0L) LlamaNative.nativeFreeModel(old)

        val loaded = if (modelRef.startsWith("content://")) {
            loadContentUri(context, Uri.parse(modelRef), profile, normalizedMode)
        } else {
            val file = File(modelRef)
            require(file.isFile) { "GGUF model not found: $modelRef" }
            loadWithGpuFallback(
                modelBytes = file.length(),
                profile = profile,
                computeMode = normalizedMode
            ) { gpuLayers ->
                LlamaNative.nativeLoadModel(modelRef, gpuLayers)
            }
        }

        check(loaded != 0L) {
            val native = LlamaNative.nativeLastError().trim()
            if (native.isBlank()) {
                "llama.cpp could not load this GGUF model."
            } else {
                "llama.cpp could not load this GGUF model.\n$native"
            }
        }
        handle = loaded
        loadedModelRef = modelRef
        loadedComputeMode = normalizedMode
    }

    private fun loadContentUri(
        context: Context,
        uri: Uri,
        profile: LlamaRuntimeProfile,
        computeMode: String
    ): Long {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Android could not open the selected GGUF file")
        return descriptor.use { pfd ->
            check(pfd.fd >= 0) { "Android returned an invalid file descriptor for the GGUF model" }
            loadWithGpuFallback(
                modelBytes = pfd.statSize.coerceAtLeast(0L),
                profile = profile,
                computeMode = computeMode
            ) { gpuLayers ->
                LlamaNative.nativeLoadModelFd(pfd.fd, gpuLayers)
            }
        }
    }

    private fun loadWithGpuFallback(
        modelBytes: Long,
        profile: LlamaRuntimeProfile,
        computeMode: String,
        loader: (Int) -> Long
    ): Long {
        val gpuLayers = chooseGpuLayers(profile, modelBytes, computeMode)
        if (gpuLayers != 0) {
            val gpuHandle = loader(gpuLayers)
            if (gpuHandle != 0L) {
                loadedGpuLayers = gpuLayers
                return gpuHandle
            }
        }

        loadedGpuLayers = 0
        return loader(0)
    }

    private fun chooseGpuLayers(
        profile: LlamaRuntimeProfile,
        modelBytes: Long,
        computeMode: String
    ): Int {
        if (computeMode == "cpu") return 0
        if (profile.gpuName.isNullOrBlank()) return 0

        val modelGb = if (modelBytes > 0) modelBytes / GIB else 0.0

        if (computeMode == "gpu") {
            val safeFullOffload =
                modelGb <= 0.0 ||
                    (
                        modelGb <= profile.totalRamGb * 0.50 &&
                            profile.availableRamGb >= modelGb + 1.5
                    )
            if (safeFullOffload) return -1

            return when {
                profile.availableRamGb >= 4.0 -> 20
                profile.availableRamGb >= 3.0 -> 12
                profile.availableRamGb >= 2.0 -> 8
                else -> 0
            }
        }

        if (profile.memoryPressure || profile.powerSave || profile.thermalThrottled) return 0

        // AUTO must prioritize stability on Android. Full Vulkan offload can make
        // some mobile drivers terminate the whole process during model loading, so
        // AUTO never requests full offload. Large GGUFs stay on CPU; smaller models
        // may use a modest partial offload.
        if (modelGb <= 0.0) return 0

        if (modelGb >= 3.5) {
            return 0
        }

        if (profile.availableRamGb < modelGb + 2.0) {
            return 0
        }

        return when {
            modelGb <= 1.5 && profile.availableRamGb >= 4.0 -> 12
            modelGb <= 2.5 && profile.availableRamGb >= 4.5 -> 8
            modelGb < 3.5 && profile.availableRamGb >= 5.0 -> 4
            else -> 0
        }
    }
}
