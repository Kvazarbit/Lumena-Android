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

        // num_ctx is shared by prompt + generation. Reserve generation plus a
        // deterministic safety margin, then use a conservative multilingual
        // character/token guard. This prevents tool traces and Cyrillic text from
        // silently consuming the output budget.
        val safetyTokens = 256
        val inputTokenBudget = (context - predict - safetyTokens).coerceAtLeast(512)
        val maxChars = (inputTokenBudget * 2)
            .coerceIn(2_400, 10_000)
        val maxPerMessage = (maxChars / 2)
            .coerceIn(1_200, 4_000)

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

        // Keep system rules, but never let them consume the whole request. The
        // latest user/tool evidence must retain room inside maxChars.
        val systemLimit = min(budget.maxPerMessage, (budget.maxChars / 2).coerceAtLeast(1))
        val clippedSystem = system?.copy(
            content = clipSystem(system.content, systemLimit)
        )

        var used = clippedSystem?.content?.length ?: 0
        val recent = ArrayList<OllamaMessage>()
        for (message in nonSystem.asReversed()) {
            val remaining = (budget.maxChars - used).coerceAtLeast(0)
            if (remaining == 0) break
            val perMessage = min(budget.maxPerMessage, remaining)
            val clipped = message.content.takeLast(perMessage)
            if (clipped.isEmpty()) continue
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
