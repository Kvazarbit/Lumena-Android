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
    @Volatile private var loadedModelBytes: Long = 0L

    suspend fun generate(
        context: Context,
        modelRef: String,
        roles: Array<String>,
        contents: Array<String>,
        profile: LlamaRuntimeProfile,
        computeMode: String,
        temperature: Float
    ): String = gate.withLock {
        withContext(Dispatchers.IO) {
            ensureLoaded(context.applicationContext, modelRef, profile, computeMode)

            var prompt = LlamaNative.nativeApplyChatTemplate(
                handle = handle,
                roles = roles,
                contents = contents,
                addAssistant = true
            )

            if (prompt.isBlank() && roles.any { it == "system" }) {
                val systemText = roles.indices
                    .filter { roles[it] == "system" }
                    .joinToString("\n\n") { contents[it] }
                    .trim()

                val compactRoles = mutableListOf<String>()
                val compactContents = mutableListOf<String>()
                var systemMerged = false

                for (i in roles.indices) {
                    if (roles[i] == "system") continue
                    if (!systemMerged && roles[i] == "user") {
                        compactRoles += "user"
                        compactContents += buildString {
                            if (systemText.isNotBlank()) {
                                append("System instructions:\n")
                                append(systemText)
                                append("\n\n")
                            }
                            append(contents[i])
                        }
                        systemMerged = true
                    } else {
                        compactRoles += roles[i]
                        compactContents += contents[i]
                    }
                }

                if (!systemMerged && systemText.isNotBlank()) {
                    compactRoles.add(0, "user")
                    compactContents.add(0, "System instructions:\n$systemText")
                }

                prompt = LlamaNative.nativeApplyChatTemplate(
                    handle = handle,
                    roles = compactRoles.toTypedArray(),
                    contents = compactContents.toTypedArray(),
                    addAssistant = true
                )
            }

            check(prompt.isNotBlank()) {
                val native = LlamaNative.nativeLastError().trim()
                if (native.isBlank()) {
                    "This GGUF has no usable chat template."
                } else {
                    "Could not apply this GGUF chat template.\n$native"
                }
            }

            val generation = LlamaRuntimePolicy.generationConfig(
                profile = profile,
                modelBytes = loadedModelBytes
            )

            LlamaNative.nativeGenerate(
                handle = handle,
                prompt = prompt,
                contextSize = generation.contextSize,
                maxTokens = generation.maxTokens,
                temperature = temperature,
                threads = generation.threads,
                batchSize = generation.batchSize
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
            loadedModelBytes = 0L
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
        loadedModelBytes = 0L
        if (old != 0L) LlamaNative.nativeFreeModel(old)

        val loaded = if (modelRef.startsWith("content://")) {
            loadContentUri(context, Uri.parse(modelRef), profile, normalizedMode)
        } else {
            val file = File(modelRef)
            require(file.isFile) { "GGUF model not found: $modelRef" }
            val bytes = file.length()
            requireSafeMemory(bytes, profile)
            val result = loadWithGpuFallback(
                modelBytes = bytes,
                profile = profile,
                computeMode = normalizedMode
            ) { gpuLayers ->
                LlamaNative.nativeLoadModel(modelRef, gpuLayers)
            }
            if (result != 0L) loadedModelBytes = bytes
            result
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
            val bytes = pfd.statSize.coerceAtLeast(0L)
            requireSafeMemory(bytes, profile)
            val result = loadWithGpuFallback(
                modelBytes = bytes,
                profile = profile,
                computeMode = computeMode
            ) { gpuLayers ->
                LlamaNative.nativeLoadModelFd(pfd.fd, gpuLayers)
            }
            if (result != 0L) loadedModelBytes = bytes
            result
        }
    }

    private fun requireSafeMemory(modelBytes: Long, profile: LlamaRuntimeProfile) {
        if (modelBytes <= 0L) return
        val modelGb = modelBytes / GIB
        val requiredGb = LlamaRuntimePolicy.requiredRamGb(modelBytes) ?: return
        check(profile.availableRamGb >= requiredGb) {
            "Not enough free RAM to load this GGUF safely. " +
                "Model %.1f GB, available %.1f GB, recommended at least %.1f GB."
                    .format(modelGb, profile.availableRamGb, requiredGb)
        }
    }

    private fun loadWithGpuFallback(
        modelBytes: Long,
        profile: LlamaRuntimeProfile,
        computeMode: String,
        loader: (Int) -> Long
    ): Long {
        val gpuLayers = LlamaRuntimePolicy.chooseGpuLayers(profile, modelBytes, computeMode)
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


}
