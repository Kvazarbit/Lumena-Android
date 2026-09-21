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

        // Keep deterministic room for both generated output and tokenizer variance.
        // Character counts are only a conservative proxy for model tokens.
        val safetyTokens = maxOf(256, context / 8)
        val inputTokens = (context - predict - safetyTokens).coerceAtLeast(512)
        val maxChars = (inputTokens * 2)
            .coerceIn(2_000, 10_000)
        val maxPerMessage = ((maxChars * 2) / 3)
            .coerceIn(1_200, 5_000)

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

        val hardLimit = budget.maxChars.coerceAtLeast(1)
        val perMessageLimit = budget.maxPerMessage.coerceAtLeast(1)
        val system = messages.firstOrNull { it.role == "system" }
        val nonSystem = messages.filterNot { it.role == "system" }

        // Reserve space for the newest turn first. The previous implementation
        // could exceed maxChars because it always admitted one recent message.
        val latest = nonSystem.lastOrNull()?.let { message ->
            val limit = min(perMessageLimit, maxOf(1, hardLimit / 2))
            message.copy(content = message.content.takeLast(limit))
        }
        val latestChars = latest?.content?.length ?: 0

        val systemLimit = min(perMessageLimit, (hardLimit - latestChars).coerceAtLeast(0))
        val clippedSystem = system?.takeIf { systemLimit > 0 }?.copy(
            content = clipSystem(system.content, systemLimit)
        )

        var used = (clippedSystem?.content?.length ?: 0) + latestChars
        val olderRecent = ArrayList<OllamaMessage>()
        val older = if (nonSystem.isEmpty()) emptyList() else nonSystem.dropLast(1)
        for (message in older.asReversed()) {
            val room = hardLimit - used
            if (room <= 0) break
            val clipped = message.content.takeLast(min(perMessageLimit, room))
            // Prefer complete recent-message slices over squeezing an older turn
            // into a tiny remainder that would add little semantic value.
            if (clipped.length < min(message.content.length, perMessageLimit)) break
            olderRecent += message.copy(content = clipped)
            used += clipped.length
        }
        olderRecent.reverse()

        return buildList {
            clippedSystem?.let(::add)
            addAll(olderRecent)
            latest?.let(::add)
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
