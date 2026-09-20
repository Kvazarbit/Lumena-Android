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

        val maxChars = (context * 3)
            .coerceIn(6_000, 14_000)
        val maxPerMessage = (maxChars / 3)
            .coerceIn(2_000, 5_000)

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

        val clippedSystem = system?.copy(
            content = clipSystem(system.content, budget.maxPerMessage)
        )

        var used = clippedSystem?.content?.length ?: 0
        val recent = ArrayList<OllamaMessage>()
        for (message in nonSystem.asReversed()) {
            val clipped = message.content.takeLast(budget.maxPerMessage)
            if (recent.isNotEmpty() && used + clipped.length > budget.maxChars) break
            recent += message.copy(content = clipped)
            used += clipped.length
            if (used >= budget.maxChars) break
        }
        recent.reverse()

        return buildList {
            clippedSystem?.let(::add)
            addAll(recent)
        }
    }

    private fun clipSystem(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val marker = "\n...[middle system context omitted]...\n"
        val available = (limit - marker.length).coerceAtLeast(0)
        val head = (available * 2) / 3
        val tail = available - head
        return text.take(head) + marker + text.takeLast(tail)
    }
}
