package com.lumena.android.ollama

import com.lumena.android.llama.LlamaHardwareInputs
import com.lumena.android.llama.LlamaHardwarePolicy
import com.lumena.android.llama.LlamaRuntimeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaContextPolicyTest {
    private fun profile(
        total: Double = 15.0,
        available: Double = 9.0,
        lowMemory: Boolean = false,
        powerSave: Boolean = false,
        thermal: Boolean = false
    ): LlamaRuntimeProfile = LlamaHardwarePolicy.resolve(
        LlamaHardwareInputs(
            totalRamGb = total,
            availableRamGb = available,
            cpuCores = 8,
            gpuName = "Adreno 750",
            lowMemory = lowMemory,
            powerSave = powerSave,
            thermalThrottled = thermal
        )
    )

    @Test
    fun healthyPhoneUsesNormalAdaptiveOllamaBudget() {
        val budget = OllamaContextPolicy.budget(profile(), retry = false)

        assertEquals(4096, budget.options.num_ctx)
        assertEquals(768, budget.options.num_predict)
        assertEquals(0.15, budget.options.temperature, 0.0001)
    }

    @Test
    fun memoryPressureShrinksOllamaBudget() {
        val budget = OllamaContextPolicy.budget(
            profile(available = 1.5, lowMemory = true),
            retry = false
        )

        assertEquals(2048, budget.options.num_ctx)
        assertEquals(384, budget.options.num_predict)
    }

    @Test
    fun retryNeverExpandsContextOrPrediction() {
        val normal = OllamaContextPolicy.budget(profile(), retry = false)
        val retry = OllamaContextPolicy.budget(profile(), retry = true)

        assertTrue(retry.options.num_ctx <= normal.options.num_ctx)
        assertTrue(retry.options.num_predict <= normal.options.num_predict)
        assertEquals(0.10, retry.options.temperature, 0.0001)
    }

    @Test
    fun clippedSystemPreservesRulesAndDynamicTail() {
        val budget = OllamaRequestBudget(
            options = OllamaOptions(num_ctx = 2048, num_predict = 384),
            maxChars = 6000,
            maxPerMessage = 2000
        )
        val system = "RULES-HEAD-" + "x".repeat(4000) + "-TASK-AND-MEMORY-TAIL"
        val compacted = OllamaContextPolicy.compact(
            listOf(
                OllamaMessage("system", system),
                OllamaMessage("user", "latest question")
            ),
            budget
        )

        val clipped = compacted.first().content
        assertTrue(clipped.startsWith("RULES-HEAD-"))
        assertTrue(clipped.contains("middle system context omitted"))
        assertTrue(clipped.endsWith("-TASK-AND-MEMORY-TAIL"))
        assertTrue(compacted.any { it.role == "user" && it.content == "latest question" })
    }

    @Test
    fun oldConversationDropsBeforeLatestTurn() {
        val budget = OllamaRequestBudget(
            options = OllamaOptions(num_ctx = 2048, num_predict = 384),
            maxChars = 4000,
            maxPerMessage = 2000
        )
        val messages = listOf(
            OllamaMessage("system", "system"),
            OllamaMessage("user", "a".repeat(2500) + "-OLD-USER-END"),
            OllamaMessage("assistant", "b".repeat(2500) + "-OLD-ASSISTANT-END"),
            OllamaMessage("user", "latest-user")
        )

        val compacted = OllamaContextPolicy.compact(messages, budget)

        assertTrue(compacted.last().content.contains("latest-user"))
        assertFalse(compacted.any { it.content.contains("-OLD-USER-END") })
    }

    @Test
    fun normalBudgetReservesGenerationAndSafetyHeadroom() {
        val budget = OllamaContextPolicy.budget(profile(), retry = false)

        assertEquals(6144, budget.maxChars)
        assertTrue(budget.maxPerMessage <= budget.maxChars / 2)
    }

    @Test
    fun pressureBudgetShrinksPromptCharsWithContext() {
        val budget = OllamaContextPolicy.budget(
            profile(available = 1.5, lowMemory = true),
            retry = false
        )

        assertEquals(2816, budget.maxChars)
        assertTrue(budget.maxPerMessage <= 1408)
    }

    @Test
    fun compactionNeverExceedsDeclaredMaxChars() {
        val budget = OllamaRequestBudget(
            options = OllamaOptions(num_ctx = 2048, num_predict = 384),
            maxChars = 3000,
            maxPerMessage = 2400
        )
        val compacted = OllamaContextPolicy.compact(
            listOf(
                OllamaMessage("system", "S".repeat(8000)),
                OllamaMessage("user", "U".repeat(7000))
            ),
            budget
        )

        assertTrue(compacted.sumOf { it.content.length } <= budget.maxChars)
        assertTrue(compacted.any { it.role == "user" && it.content.isNotEmpty() })
        assertTrue(compacted.first().content.length <= budget.maxChars / 2)
    }

}
