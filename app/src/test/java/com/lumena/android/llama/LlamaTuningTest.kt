package com.lumena.android.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaTuningTest {
    private val automatic = LlamaGenerationConfig(2048, 384, 128, 4)

    @Test fun automaticPreservesPolicy() {
        assertEquals(automatic, LlamaTuning().applyTo(automatic))
    }

    @Test fun economyReducesAllGenerationLimits() {
        assertEquals(LlamaGenerationConfig(1024, 128, 32, 2), LlamaTuning.ECONOMY.applyTo(automatic))
    }

    @Test fun manualCannotExceedHardwareOrLargeModelSafetyCaps() {
        assertEquals(automatic, LlamaTuning(4096, 512, 8, 768).applyTo(automatic))
    }

    @Test fun shortContextAlsoLimitsPredictionLikeNative() {
        val result = LlamaTuning(contextTokens = 512).applyTo(automatic)
        assertEquals(512, result.contextSize)
        assertEquals(170, result.maxTokens)
        assertTrue(result.batchSize <= result.contextSize)
    }

    @Test fun invalidStoredValuesReturnToAutomatic() {
        assertEquals(LlamaTuning(), LlamaTuning(-1, 7, 999, -100, Int.MAX_VALUE).normalized())
    }

    @Test fun thermalThreadLimitStillWinsOverEconomyPreset() {
        val limited = automatic.copy(threads = 1, batchSize = 32)
        assertEquals(1, LlamaTuning.ECONOMY.applyTo(limited).threads)
    }
}
