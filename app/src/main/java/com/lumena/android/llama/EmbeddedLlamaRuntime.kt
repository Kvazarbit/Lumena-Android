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
    @Volatile private var loadedGpuLayers: Int = 0

    suspend fun generate(
        context: Context,
        modelRef: String,
        prompt: String,
        profile: LlamaRuntimeProfile,
        temperature: Float
    ): String = gate.withLock {
        withContext(Dispatchers.IO) {
            ensureLoaded(context.applicationContext, modelRef, profile)
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

    suspend fun unload() = gate.withLock {
        withContext(Dispatchers.IO) {
            val current = handle
            handle = 0
            loadedModelRef = ""
            loadedGpuLayers = 0
            if (current != 0L) LlamaNative.nativeFreeModel(current)
        }
    }

    private fun ensureLoaded(context: Context, modelRef: String, profile: LlamaRuntimeProfile) {
        require(modelRef.isNotBlank()) { "Choose a GGUF model first" }
        if (handle != 0L && loadedModelRef == modelRef) return

        val old = handle
        handle = 0
        loadedModelRef = ""
        loadedGpuLayers = 0
        if (old != 0L) LlamaNative.nativeFreeModel(old)

        val loaded = if (modelRef.startsWith("content://")) {
            loadContentUri(context, Uri.parse(modelRef), profile)
        } else {
            val file = File(modelRef)
            require(file.isFile) { "GGUF model not found: $modelRef" }
            loadWithGpuFallback(modelRef, file.length(), profile)
        }

        check(loaded != 0L) {
            "llama.cpp could not load this GGUF model. Re-select the file or choose a smaller compatible GGUF."
        }
        handle = loaded
        loadedModelRef = modelRef
    }

    private fun loadContentUri(
        context: Context,
        uri: Uri,
        profile: LlamaRuntimeProfile
    ): Long {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Android could not open the selected GGUF file")
        return descriptor.use { pfd ->
            check(pfd.fd >= 0) { "Android returned an invalid file descriptor for the GGUF model" }
            loadWithGpuFallback(
                path = "/proc/self/fd/${pfd.fd}",
                modelBytes = pfd.statSize.coerceAtLeast(0L),
                profile = profile
            )
        }
    }

    private fun loadWithGpuFallback(
        path: String,
        modelBytes: Long,
        profile: LlamaRuntimeProfile
    ): Long {
        val gpuLayers = chooseGpuLayers(profile, modelBytes)
        if (gpuLayers != 0) {
            val gpuHandle = LlamaNative.nativeLoadModel(path, gpuLayers)
            if (gpuHandle != 0L) {
                loadedGpuLayers = gpuLayers
                return gpuHandle
            }
        }

        loadedGpuLayers = 0
        return LlamaNative.nativeLoadModel(path, 0)
    }

    private fun chooseGpuLayers(profile: LlamaRuntimeProfile, modelBytes: Long): Int {
        if (profile.gpuName.isNullOrBlank()) return 0
        if (profile.memoryPressure || profile.powerSave || profile.thermalThrottled) return 0

        val modelGb = if (modelBytes > 0) modelBytes / GIB else 0.0
        val safeFullOffload = when {
            modelGb <= 0.0 ->
                profile.totalRamGb >= 12.0 && profile.availableRamGb >= 6.0
            else ->
                modelGb <= min(6.0, profile.totalRamGb * 0.38) &&
                    profile.availableRamGb >= modelGb + 2.5
        }

        if (safeFullOffload) return -1

        val safePartialOffload =
            modelGb > 0.0 &&
                modelGb <= profile.totalRamGb * 0.50 &&
                profile.availableRamGb >= 3.5

        return if (safePartialOffload) 20 else 0
    }
}
