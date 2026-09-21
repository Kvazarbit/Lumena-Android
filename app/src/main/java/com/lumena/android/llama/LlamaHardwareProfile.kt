package com.lumena.android.llama

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

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
