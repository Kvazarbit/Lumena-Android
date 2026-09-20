package com.lumena.android.llama

import kotlin.math.min

data class LlamaGenerationConfig(
    val contextSize: Int,
    val maxTokens: Int,
    val batchSize: Int,
    val threads: Int
)

object LlamaRuntimePolicy {
    private const val GIB = 1024.0 * 1024.0 * 1024.0

    fun generationConfig(
        profile: LlamaRuntimeProfile,
        modelBytes: Long
    ): LlamaGenerationConfig {
        val largeModel = modelBytes >= (3.5 * GIB).toLong()
        return LlamaGenerationConfig(
            contextSize = if (largeModel) min(profile.contextSize, 2048) else profile.contextSize,
            maxTokens = if (largeModel) min(profile.maxTokens, 384) else profile.maxTokens,
            batchSize = if (largeModel) min(profile.batchSize, 128) else profile.batchSize,
            threads = if (largeModel) min(profile.threads, 4) else profile.threads
        )
    }

    fun requiredRamGb(modelBytes: Long): Double? {
        if (modelBytes <= 0L) return null
        val modelGb = modelBytes / GIB
        return modelGb + if (modelGb >= 3.5) 1.8 else 1.2
    }

    fun shouldDowngradeAutoGpu(
        loadedGpuLayers: Int,
        computeMode: String,
        profile: LlamaRuntimeProfile
    ): Boolean =
        computeMode == "auto" &&
            loadedGpuLayers != 0 &&
            (profile.memoryPressure || profile.powerSave || profile.thermalThrottled)

    fun chooseGpuLayers(
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
        if (modelGb <= 0.0) return 0
        if (modelGb >= 3.5) return 0
        if (profile.availableRamGb < modelGb + 2.0) return 0

        return when {
            modelGb <= 1.5 && profile.availableRamGb >= 4.0 -> 12
            modelGb <= 2.5 && profile.availableRamGb >= 4.5 -> 8
            modelGb < 3.5 && profile.availableRamGb >= 5.0 -> 4
            else -> 0
        }
    }
}
