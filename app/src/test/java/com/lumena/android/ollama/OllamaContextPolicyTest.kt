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
    fun retryShrinksPromptBudgetEvenWhenHardwareAlreadyUsesSmallContext() {
        val constrained = profile(available = 1.5, lowMemory = true)
        val normal = OllamaContextPolicy.budget(constrained, retry = false)
        val retry = OllamaContextPolicy.budget(constrained, retry = true)

        assertEquals(normal.options.num_ctx, retry.options.num_ctx)
        assertEquals(normal.options.num_predict, retry.options.num_predict)
        assertTrue(retry.maxChars < normal.maxChars)
        assertTrue(retry.maxPerMessage <= normal.maxPerMessage)
    }

    @Test
    fun adaptiveBudgetReservesPredictionAndSafetyTokens() {
        val budget = OllamaContextPolicy.budget(profile(), retry = false)
        val reserveTokens = maxOf(128, budget.options.num_ctx / 16)
        val safeInputChars =
            (budget.options.num_ctx - budget.options.num_predict - reserveTokens) * 2

        assertTrue(budget.maxChars <= safeInputChars)
        assertTrue(budget.maxChars > 0)
        assertTrue(budget.maxPerMessage <= budget.maxChars / 2)
    }

    @Test
    fun compactionStrictlyHonorsTotalBudgetUnderLargeSystemAndLatestTurn() {
        val budget = OllamaRequestBudget(
            options = OllamaOptions(num_ctx = 2048, num_predict = 384),
            maxChars = 3000,
            maxPerMessage = 2500
        )
        val compacted = OllamaContextPolicy.compact(
            listOf(
                OllamaMessage("system", "SYS-" + "s".repeat(7000) + "-SYSTEM-TAIL"),
                OllamaMessage("user", "OLD-" + "o".repeat(4000)),
                OllamaMessage("assistant", "MID-" + "m".repeat(4000)),
                OllamaMessage("user", "NEW-" + "n".repeat(4000) + "-LATEST-TAIL")
            ),
            budget
        )

        assertTrue(compacted.sumOf { it.content.length } <= budget.maxChars)
        assertTrue(compacted.last().role == "user")
        assertTrue(compacted.last().content.endsWith("-LATEST-TAIL"))
        assertFalse(compacted.any { it.content.startsWith("OLD-") })
    }

    @Test
    fun newestLargeWebToolResultPreservesFramingEvidenceAndContinuation() {
        val stdout = buildString {
            append("{\"provider\":\"bing-rss\",\"results\":[")
            append("{\"url\":\"https://primary.example/article\",")
            append("\"title\":\"Primary source\",\"snippet\":\"")
            append("e".repeat(7_000))
            append("\"}]}")
        }
        val toolResult = LocalWorkflowAgent.toolResultMessage(
            tool = "web.search",
            ok = true,
            stdout = stdout,
            stderr = "",
            error = null
        )
        val budget = OllamaRequestBudget(
            options = OllamaOptions(num_ctx = 2048, num_predict = 384),
            maxChars = 2_200,
            maxPerMessage = 1_800
        )

        val compacted = OllamaContextPolicy.compact(
            listOf(
                OllamaMessage("system", "SYS"),
                toolResult
            ),
            budget
        )

        val latest = compacted.last().content
        assertTrue(compacted.sumOf { it.content.length } <= budget.maxChars)
        assertTrue(latest.startsWith("TOOL_RESULT for web.search:"))
        assertTrue(latest.contains("ok=true"))
        assertTrue(latest.contains("\"provider\":\"bing-rss\""))
        assertTrue(latest.contains("https://primary.example/article"))
        assertTrue(latest.contains("middle recent context omitted"))
        assertTrue(latest.contains("Continue the SAME goal"))
        assertTrue(latest.endsWith("or report partial JSON."))
    }

    @Test
    fun usageTelemetryDistinguishesEstimateFromExactServerCount() {
        val original = listOf(
            OllamaMessage("system", "S".repeat(2_000)),
            OllamaMessage("user", "U".repeat(2_000))
        )
        val budget = OllamaRequestBudget(
            options = OllamaOptions(num_ctx = 2048, num_predict = 384),
            maxChars = 2_000,
            maxPerMessage = 1_200,
            maxInputTokens = 800
        )
        val compacted = OllamaContextPolicy.compact(original, budget)

        val estimated = OllamaContextPolicy.usage(
            originalMessages = original,
            compactedMessages = compacted,
            budget = budget
        )
        assertFalse(estimated.promptTokensExact)
        assertEquals(
            (compacted.sumOf { it.content.length } + 1) / 2,
            estimated.promptTokens
        )
        assertEquals(800, estimated.inputBudgetTokens)
        assertTrue(estimated.compacted)
        assertTrue(estimated.compactLabel().startsWith("CTX ~"))

        val exact = OllamaContextPolicy.usage(
            originalMessages = original,
            compactedMessages = compacted,
            budget = budget,
            exactPromptTokens = 612,
            generatedTokens = 42
        )
        assertTrue(exact.promptTokensExact)
        assertEquals(612, exact.promptTokens)
        assertEquals(42, exact.generatedTokens)
        assertEquals("CTX 612/800 · 77%", exact.compactLabel())
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
    fun compactionNeverExceedsHardBudgetAcrossBoundarySizes() {
        val messages = listOf(
            OllamaMessage("system", "SYSTEM-" + "s".repeat(4000) + "-TAIL"),
            OllamaMessage("user", "older-" + "o".repeat(4000)),
            OllamaMessage("assistant", "middle-" + "m".repeat(4000)),
            OllamaMessage("user", "LATEST-" + "x".repeat(4000) + "-END")
        )

        for (maxChars in listOf(1, 8, 32, 40, 64, 127, 512, 999, 2000, 4096)) {
            val budget = OllamaRequestBudget(
                options = OllamaOptions(num_ctx = 4096, num_predict = 512),
                maxChars = maxChars,
                maxPerMessage = maxChars
            )
            val compacted = OllamaContextPolicy.compact(messages, budget)

            assertTrue(
                "budget=$maxChars actual=" + compacted.sumOf { it.content.length },
                compacted.sumOf { it.content.length } <= maxChars
            )
            assertTrue(compacted.none { it.content.length > maxChars })
        }
    }

}
