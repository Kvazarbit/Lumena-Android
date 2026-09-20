package com.lumena.android.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaPolicyTest {
    private fun profile(
        total: Double = 15.0,
        available: Double = 9.5,
        cores: Int = 8,
        gpu: String? = "Adreno 750",
        lowMemory: Boolean = false,
        powerSave: Boolean = false,
        thermal: Boolean = false
    ): LlamaRuntimeProfile = LlamaHardwarePolicy.resolve(
        LlamaHardwareInputs(
            totalRamGb = total,
            availableRamGb = available,
            cpuCores = cores,
            gpuName = gpu,
            lowMemory = lowMemory,
            powerSave = powerSave,
            thermalThrottled = thermal
        )
    )

    @Test
    fun highRamEightCorePhoneGetsFullNormalProfile() {
        val p = profile()

        assertEquals(4096, p.contextSize)
        assertEquals(512, p.batchSize)
        assertEquals(6, p.threads)
        assertEquals(768, p.maxTokens)
        assertEquals("Adreno 750", p.gpuName)
    }

    @Test
    fun memoryPressureShrinksContextAndBatch() {
        val p = profile(
            available = 1.5,
            lowMemory = true
        )

        assertTrue(p.memoryPressure)
        assertEquals(2048, p.contextSize)
        assertEquals(128, p.batchSize)
        assertEquals(384, p.maxTokens)
    }

    @Test
    fun thermalPressureCutsThreadsAndBatch() {
        val p = profile(thermal = true)

        assertTrue(p.thermalThrottled)
        assertEquals(3, p.threads)
        assertEquals(192, p.batchSize)
        assertEquals(512, p.maxTokens)
    }

    @Test
    fun powerSaveNeverIncreasesCompute() {
        val normal = profile()
        val saved = profile(powerSave = true)

        assertTrue(saved.threads <= normal.threads)
        assertTrue(saved.batchSize <= normal.batchSize)
    }

    @Test
    fun largeModelGetsSaferGenerationEnvelope() {
        val p = profile()
        val fiveGb = (5.0 * 1024.0 * 1024.0 * 1024.0).toLong()

        val config = LlamaRuntimePolicy.generationConfig(p, fiveGb)

        assertEquals(2048, config.contextSize)
        assertEquals(128, config.batchSize)
        assertEquals(384, config.maxTokens)
        assertEquals(4, config.threads)
    }

    @Test
    fun autoKeepsLargeModelOnCpu() {
        val p = profile()
        val fiveGb = (5.0 * 1024.0 * 1024.0 * 1024.0).toLong()

        assertEquals(0, LlamaRuntimePolicy.chooseGpuLayers(p, fiveGb, "auto"))
    }

    @Test
    fun autoCanPartiallyOffloadSmallModelWhenHealthy() {
        val p = profile()
        val oneGb = (1.0 * 1024.0 * 1024.0 * 1024.0).toLong()

        assertEquals(12, LlamaRuntimePolicy.chooseGpuLayers(p, oneGb, "auto"))
    }

    @Test
    fun thermalOrPowerSafeModeDisablesAutoGpuOffload() {
        val oneGb = (1.0 * 1024.0 * 1024.0 * 1024.0).toLong()

        assertEquals(
            0,
            LlamaRuntimePolicy.chooseGpuLayers(profile(thermal = true), oneGb, "auto")
        )
        assertEquals(
            0,
            LlamaRuntimePolicy.chooseGpuLayers(profile(powerSave = true), oneGb, "auto")
        )
    }

    @Test
    fun autoGpuDowngradesWhenRuntimeBecomesUnsafe() {
        assertTrue(
            LlamaRuntimePolicy.shouldDowngradeAutoGpu(
                loadedGpuLayers = 8,
                computeMode = "auto",
                profile = profile(thermal = true)
            )
        )
        assertTrue(
            LlamaRuntimePolicy.shouldDowngradeAutoGpu(
                loadedGpuLayers = 8,
                computeMode = "auto",
                profile = profile(available = 1.5, lowMemory = true)
            )
        )
        assertTrue(
            !LlamaRuntimePolicy.shouldDowngradeAutoGpu(
                loadedGpuLayers = 8,
                computeMode = "gpu",
                profile = profile(thermal = true)
            )
        )
    }

    @Test
    fun explicitCpuAlwaysMeansZeroGpuLayers() {
        val oneGb = (1.0 * 1024.0 * 1024.0 * 1024.0).toLong()
        assertEquals(0, LlamaRuntimePolicy.chooseGpuLayers(profile(), oneGb, "cpu"))
    }

    @Test
    fun memoryGuardScalesWithModelSize() {
        val twoGb = (2.0 * 1024.0 * 1024.0 * 1024.0).toLong()
        val fiveGb = (5.0 * 1024.0 * 1024.0 * 1024.0).toLong()

        val smallRequired = LlamaRuntimePolicy.requiredRamGb(twoGb)!!
        val largeRequired = LlamaRuntimePolicy.requiredRamGb(fiveGb)!!

        assertTrue(smallRequired in 3.19..3.21)
        assertTrue(largeRequired in 6.79..6.81)
    }
}
