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
    fun budgetReservesOutputAndTokenizerSafetyMargin() {
        val budget = OllamaContextPolicy.budget(profile(), retry = false)
        val context = budget.options.num_ctx
        val predict = budget.options.num_predict
        val safetyTokens = maxOf(256, context / 8)
        val theoreticalCharCeiling = (context - predict - safetyTokens).coerceAtLeast(512) * 2

        assertTrue(budget.maxChars <= theoreticalCharCeiling)
        assertTrue(budget.maxChars < context * 3)
    }

    @Test
    fun compactionNeverExceedsHardCharacterBudget() {
        val budget = OllamaRequestBudget(
            options = OllamaOptions(num_ctx = 2048, num_predict = 384),
            maxChars = 1000,
            maxPerMessage = 900
        )
        val compacted = OllamaContextPolicy.compact(
            listOf(
                OllamaMessage("system", "SYSTEM-HEAD-" + "s".repeat(3000) + "-SYSTEM-TAIL"),
                OllamaMessage("user", "old-" + "o".repeat(3000)),
                OllamaMessage("assistant", "middle-" + "m".repeat(3000)),
                OllamaMessage("user", "LATEST-" + "x".repeat(3000) + "-END")
            ),
            budget
        )

        assertTrue(compacted.sumOf { it.content.length } <= budget.maxChars)
        assertTrue(compacted.last().content.endsWith("-END"))
        assertTrue(compacted.first().role == "system")
        assertTrue(compacted.first().content.contains("middle system context omitted"))
    }

}
