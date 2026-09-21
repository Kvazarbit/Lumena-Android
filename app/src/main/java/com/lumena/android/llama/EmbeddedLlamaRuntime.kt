package com.lumena.android.llama

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

            val generation = LlamaRuntimePolicy.generationConfig(
                profile = profile,
                modelBytes = loadedModelBytes
            )
            val maxPromptTokens = (generation.contextSize - generation.maxTokens - 8)
                .coerceAtLeast(32)

            val fitted = ChatContextPolicy.fit(
                roles = roles,
                contents = contents,
                maxPromptTokens = maxPromptTokens,
                formatter = { candidateRoles, candidateContents ->
                    applyChatTemplate(candidateRoles, candidateContents)
                },
                tokenCounter = { text ->
                    LlamaNative.nativeCountTokens(handle, text)
                }
            )

            check(fitted.prompt.isNotBlank()) {
                val native = LlamaNative.nativeLastError().trim()
                if (native.isBlank()) {
                    "This GGUF has no usable chat template."
                } else {
                    "Could not apply this GGUF chat template.\n$native"
                }
            }

            check(fitted.fits) {
                "Conversation is too large for the safe context budget even after dropping " +
                    "old history. prompt=${fitted.promptTokens}, limit=$maxPromptTokens tokens. " +
                    "System instructions and the newest user turn were preserved."
            }

            coroutineContext.ensureActive()
            val response = LlamaNative.nativeGenerate(
                handle = handle,
                prompt = fitted.prompt,
                contextSize = generation.contextSize,
                maxTokens = generation.maxTokens,
                temperature = temperature,
                threads = generation.threads,
                batchSize = generation.batchSize
            )
            coroutineContext.ensureActive()
            response
        }
    }

    private fun applyChatTemplate(
        roles: Array<String>,
        contents: Array<String>
    ): String {
        var prompt = LlamaNative.nativeApplyChatTemplate(
            handle = handle,
            roles = roles,
            contents = contents,
            addAssistant = true
        )
        if (prompt.isNotBlank() || roles.none { it == "system" }) return prompt

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
        return prompt
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
        val sameLoadedModel =
            handle != 0L &&
                loadedModelRef == modelRef &&
                loadedComputeMode == normalizedMode

        val mustDowngradeAutoGpu =
            sameLoadedModel &&
                LlamaRuntimePolicy.shouldDowngradeAutoGpu(
                    loadedGpuLayers = loadedGpuLayers,
                    computeMode = normalizedMode,
                    profile = profile
                )

        if (sameLoadedModel && !mustDowngradeAutoGpu) return

        val old = handle
        handle = 0
        loadedModelRef = ""
        loadedComputeMode = "auto"
        loadedGpuLayers = 0
        loadedModelBytes = 0L
        if (old != 0L) LlamaNative.nativeFreeModel(old)

        val loaded = if (modelRef.startsWith("content://")) {
            loadContentUri(context, Uri.parse(modelRef), normalizedMode)
        } else {
            val file = File(modelRef)
            require(file.isFile) { "GGUF model not found: $modelRef" }
            val fileBytes = file.length()
            val probe = LlamaLoadDiagnostics.parseProbe(
                LlamaNative.nativeProbeModel(modelRef)
            )
            val bytes = LlamaRuntimePolicy.effectiveModelBytes(fileBytes, probe.modelSizeBytes)
            val loadProfile = LlamaHardwareProfile.detect(context)
            if (!probe.ok) {
                throwLoadFailure(
                    probe = probe,
                    profile = loadProfile,
                    fileBytes = bytes,
                    nativeLog = probe.raw
                )
            }
            requireSafeMemory(bytes, loadProfile)
            val result = loadWithGpuFallback(
                modelBytes = bytes,
                profile = loadProfile,
                computeMode = normalizedMode
            ) { gpuLayers ->
                LlamaNative.nativeLoadModel(modelRef, gpuLayers)
            }
            if (result != 0L) {
                loadedModelBytes = bytes
            } else {
                throwLoadFailure(
                    probe = probe,
                    profile = loadProfile,
                    fileBytes = bytes,
                    nativeLog = LlamaNative.nativeLastError()
                )
            }
            result
        }

        check(loaded != 0L) {
            "Embedded model load failed without a native diagnostic."
        }
        handle = loaded
        loadedModelRef = modelRef
        loadedComputeMode = normalizedMode
    }

    private fun loadContentUri(
        context: Context,
        uri: Uri,
        computeMode: String
    ): Long {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (failure: java.io.FileNotFoundException) {
            throw IllegalStateException("Embedded model load failed. Selected GGUF is unavailable: ${failure.message}", failure)
        } catch (failure: SecurityException) {
            throw IllegalStateException("Embedded model load failed. Permission to read selected GGUF was denied.", failure)
        }
            ?: error("Embedded model load failed. Android could not open the selected GGUF file")
        return descriptor.use { pfd ->
            check(pfd.fd >= 0) { "Embedded model load failed. Invalid Android file descriptor for the GGUF model" }
            val fileBytes = pfd.statSize
            val probe = LlamaLoadDiagnostics.parseProbe(
                LlamaNative.nativeProbeModelFd(pfd.fd)
            )
            val bytes = LlamaRuntimePolicy.effectiveModelBytes(fileBytes, probe.modelSizeBytes)
            val loadProfile = LlamaHardwareProfile.detect(context)
            if (!probe.ok) {
                throwLoadFailure(
                    probe = probe,
                    profile = loadProfile,
                    fileBytes = bytes,
                    nativeLog = probe.raw
                )
            }

            requireSafeMemory(bytes, loadProfile)
            val result = loadWithGpuFallback(
                modelBytes = bytes,
                profile = loadProfile,
                computeMode = computeMode
            ) { gpuLayers ->
                LlamaNative.nativeLoadModelFd(pfd.fd, gpuLayers)
            }
            if (result != 0L) {
                loadedModelBytes = bytes
            } else {
                throwLoadFailure(
                    probe = probe,
                    profile = loadProfile,
                    fileBytes = bytes,
                    nativeLog = LlamaNative.nativeLastError()
                )
            }
            result
        }
    }

    private fun throwLoadFailure(
        probe: LlamaModelProbeInfo,
        profile: LlamaRuntimeProfile,
        fileBytes: Long,
        nativeLog: String
    ): Nothing {
        val failure = LlamaLoadDiagnostics.classify(
            probe = probe,
            nativeLog = nativeLog,
            availableRamGb = profile.availableRamGb,
            fileBytes = fileBytes
        )
        error(
            failure.userMessage +
                "\n" +
                failure.technicalSummary
        )
    }

    private fun requireSafeMemory(modelBytes: Long, profile: LlamaRuntimeProfile) {
        check(modelBytes > 0L) {
            "Embedded model load failed. Cannot determine GGUF size for the RAM safety check."
        }
        val modelGb = modelBytes / GIB
        val requiredGb = LlamaRuntimePolicy.requiredRamGb(modelBytes) ?: return
        check(profile.availableRamGb >= requiredGb) {
            "Embedded model load failed. Not enough free RAM to load this GGUF safely. " +
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
