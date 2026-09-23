package com.lumena.android.ollama

import kotlin.math.roundToInt

/**
 * Bounded context telemetry for the model request currently visible to Lumena.
 *
 * inputBudgetTokens is Lumena's conservative request-input budget, not a claim
 * about a remote/cloud provider's hard context limit.
 */
data class ModelContextUsage(
    val promptTokens: Int,
    val promptTokensExact: Boolean,
    val inputBudgetTokens: Int,
    val requestedContextWindowTokens: Int,
    val reservedOutputTokens: Int,
    val generatedTokens: Int? = null,
    val compacted: Boolean = false
) {
    val percentOfInputBudget: Int
        get() = if (inputBudgetTokens <= 0) 0 else
            ((promptTokens.toDouble() / inputBudgetTokens.toDouble()) * 100.0)
                .roundToInt()
                .coerceAtLeast(0)

    fun compactLabel(): String {
        val prefix = if (promptTokensExact) "" else "~"
        val percent = percentOfInputBudget
        val warning = if (percent >= 85) " !" else ""
        return "CTX $prefix${formatTokenCount(promptTokens)}/" +
            "${formatTokenCount(inputBudgetTokens)} · $percent%$warning"
    }
}

interface ModelContextTelemetrySource {
    fun estimateContextUsage(messages: List<OllamaMessage>): ModelContextUsage
    fun lastContextUsage(): ModelContextUsage?
}

internal fun formatTokenCount(value: Int): String {
    val safe = value.coerceAtLeast(0)
    if (safe < 1_000) return safe.toString()

    val whole = safe / 1_000
    val remainder = safe % 1_000
    val tenth = remainder / 100
    return if (tenth == 0) {
        "${whole}k"
    } else {
        "$whole.${tenth}k"
    }
}
