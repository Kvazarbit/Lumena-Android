package com.lumena.android.llama

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlin.math.max
import kotlin.math.min

data class LlamaRuntimeProfile(
    val contextSize: Int,
    val maxTokens: Int,
    val batchSize: Int,
    val threads: Int,
    val totalRamGb: Double,
    val availableRamGb: Double,
    val cpuCores: Int,
    val gpuName: String?,
    val memoryPressure: Boolean,
    val powerSave: Boolean,
    val thermalThrottled: Boolean
) {
    val summary: String
        get() = buildString {
            append("%.1f GB RAM".format(totalRamGb))
            append(" · ")
            append(cpuCores)
            append(" CPU")
            append(" · ")
            append(threads)
            append(" threads")
            gpuName?.takeIf { it.isNotBlank() }?.let {
                append(" · Vulkan ")
                append(it)
            }
            append(" · ctx ")
            append(contextSize)
            append(" · batch ")
            append(batchSize)
            if (memoryPressure) append(" · memory-safe")
            if (powerSave) append(" · battery-safe")
            if (thermalThrottled) append(" · thermal-safe")
        }
}

data class LlamaHardwareInputs(
    val totalRamGb: Double,
    val availableRamGb: Double,
    val cpuCores: Int,
    val gpuName: String?,
    val lowMemory: Boolean,
    val powerSave: Boolean,
    val thermalThrottled: Boolean
)

object LlamaHardwarePolicy {
    fun resolve(inputs: LlamaHardwareInputs): LlamaRuntimeProfile {
        val totalGb = inputs.totalRamGb.coerceAtLeast(0.0)
        val availableGb = inputs.availableRamGb.coerceAtLeast(0.0)
        val cores = inputs.cpuCores.coerceAtLeast(1)

        val memoryPressure = inputs.lowMemory ||
            availableGb < 2.0 ||
            (totalGb > 0.0 && availableGb / totalGb < 0.12)

        var contextSize = when {
            totalGb >= 12.0 -> 4096
            totalGb >= 8.0 -> 4096
            totalGb >= 6.0 -> 3072
            else -> 2048
        }

        var batchSize = when {
            totalGb >= 12.0 -> 512
            totalGb >= 8.0 -> 384
            totalGb >= 6.0 -> 256
            else -> 128
        }

        var threads = when {
            cores >= 8 -> 6
            cores >= 6 -> 4
            cores >= 4 -> 3
            else -> max(1, cores)
        }

        var maxTokens = when {
            totalGb >= 12.0 -> 768
            totalGb >= 8.0 -> 640
            totalGb >= 6.0 -> 512
            else -> 384
        }

        if (memoryPressure) {
            contextSize = min(contextSize, 2048)
            batchSize = min(batchSize, 128)
            maxTokens = min(maxTokens, 384)
        }

        if (inputs.powerSave) {
            threads = min(threads, 4)
            batchSize = min(batchSize, 256)
        }

        if (inputs.thermalThrottled) {
            threads = max(1, threads / 2)
            batchSize = min(batchSize, 192)
            maxTokens = min(maxTokens, 512)
        }

        threads = threads.coerceIn(1, cores)

        return LlamaRuntimeProfile(
            contextSize = contextSize,
            maxTokens = maxTokens,
            batchSize = batchSize,
            threads = threads,
            totalRamGb = totalGb,
            availableRamGb = availableGb,
            cpuCores = cores,
            gpuName = inputs.gpuName?.takeIf { it.isNotBlank() },
            memoryPressure = memoryPressure,
            powerSave = inputs.powerSave,
            thermalThrottled = inputs.thermalThrottled
        )
    }
}

object LlamaHardwareProfile {
    private const val GIB = 1024.0 * 1024.0 * 1024.0
    private val detectedGpuName: String by lazy {
        runCatching { LlamaNative.nativeGpuInfo().trim() }.getOrDefault("")
    }

    fun detect(context: Context): LlamaRuntimeProfile {
        val app = context.applicationContext
        val activity = app.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val power = app.getSystemService(PowerManager::class.java)

        val totalGb = memory.totalMem / GIB
        val availableGb = memory.availMem / GIB
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val thermalThrottled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

        return LlamaHardwarePolicy.resolve(
            LlamaHardwareInputs(
                totalRamGb = totalGb,
                availableRamGb = availableGb,
                cpuCores = cores,
                gpuName = detectedGpuName.takeIf { it.isNotBlank() },
                lowMemory = memory.lowMemory,
                powerSave = power.isPowerSaveMode,
                thermalThrottled = thermalThrottled
            )
        )
    }
}
