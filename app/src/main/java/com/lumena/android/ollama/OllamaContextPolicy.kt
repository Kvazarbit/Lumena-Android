package com.lumena.android.ollama

import com.lumena.android.llama.LlamaRuntimeProfile
import kotlin.math.min

data class OllamaRequestBudget(
    val options: OllamaOptions,
    val maxChars: Int,
    val maxPerMessage: Int
)

object OllamaContextPolicy {
    fun budget(
        profile: LlamaRuntimeProfile?,
        retry: Boolean
    ): OllamaRequestBudget {
        val baseContext = profile?.contextSize ?: 4096
        val basePredict = profile?.maxTokens ?: 768

        val context = if (retry) min(baseContext, 3072) else baseContext
        val predict = if (retry) min(basePredict, 512) else basePredict
        val temperature = if (retry) 0.10 else 0.15

        // Reserve output tokens and an additional safety margin before deriving
        // an approximate character budget. Two chars/token is intentionally
        // conservative for Cyrillic + JSON/tool traces.
        val reserveTokens = maxOf(128, context / 16)
        val rawInputTokens = (context - predict - reserveTokens).coerceAtLeast(512)
        // Context pressure must produce a genuinely smaller second request even
        // on profiles that already run at a 2K/3K context.
        val inputTokens = if (retry) {
            (rawInputTokens * 2 / 3).coerceAtLeast(384)
        } else {
            rawInputTokens
        }
        val maxChars = (inputTokens * 2)
            .coerceIn(2_000, 12_000)
        val maxPerMessage = (maxChars / 2)
            .coerceIn(1_000, 4_000)

        return OllamaRequestBudget(
            options = OllamaOptions(
                num_ctx = context,
                num_predict = predict,
                temperature = temperature
            ),
            maxChars = maxChars,
            maxPerMessage = maxPerMessage
        )
    }

    fun compact(
        messages: List<OllamaMessage>,
        budget: OllamaRequestBudget
    ): List<OllamaMessage> {
        if (messages.isEmpty()) return messages

        val system = messages.firstOrNull { it.role == "system" }
        val nonSystem = messages.filterNot { it.role == "system" }

        // Never let the system prompt consume the whole request budget: the
        // newest non-system turn must retain space. Then enforce the total budget
        // strictly while walking history from newest to oldest.
        val systemLimit = min(budget.maxPerMessage, budget.maxChars / 2)
        val clippedSystem = system?.copy(
            content = clipSystem(system.content, systemLimit)
        )

        var remaining = (budget.maxChars - (clippedSystem?.content?.length ?: 0))
            .coerceAtLeast(0)
        val recent = ArrayList<OllamaMessage>()
        for (message in nonSystem.asReversed()) {
            if (remaining <= 0) break
            val requested = min(budget.maxPerMessage, message.content.length)
            val take = if (recent.isEmpty()) {
                min(requested, remaining)
            } else {
                if (requested > remaining) break
                requested
            }
            val clipped = message.content.takeLast(take)
            if (clipped.isEmpty()) continue
            recent += message.copy(content = clipped)
            remaining -= clipped.length
        }
        recent.reverse()

        return buildList {
            clippedSystem?.let(::add)
            addAll(recent)
        }
    }

    private fun clipSystem(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val marker = "\n...[middle system context omitted]...\n"
        if (limit <= marker.length) return text.take(limit)
        val available = limit - marker.length
        val head = (available * 2) / 3
        val tail = available - head
        return text.take(head) + marker + text.takeLast(tail)
    }
}
