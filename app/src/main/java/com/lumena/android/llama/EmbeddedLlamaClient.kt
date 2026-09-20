package com.lumena.android.llama

import android.content.Context
import com.lumena.android.ollama.ChatModelClient
import com.lumena.android.ollama.OllamaMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Lightweight chat facade over the process-wide EmbeddedLlamaRuntime.
 *
 * Creating a new client no longer reloads the GGUF. The runtime keeps the selected model
 * warm and reuses it until the user selects another model.
 */
class EmbeddedLlamaClient(
    context: Context,
    private val modelRef: String,
    private val computeMode: String = "auto",
    private val temperature: Float = 0.15f
) : Closeable, ChatModelClient {
    private val appContext = context.applicationContext

    suspend fun chat(messages: List<OllamaMessage>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val profile = LlamaHardwareProfile.detect(appContext)
            val prepared = prepareMessages(messages, profile.contextSize)
            val text = EmbeddedLlamaRuntime.generate(
                context = appContext,
                modelRef = modelRef,
                roles = prepared.map { it.role }.toTypedArray(),
                contents = prepared.map { it.content }.toTypedArray(),
                profile = profile,
                computeMode = computeMode,
                temperature = temperature
            ).trim()
            check(text.isNotBlank()) { "llama.cpp returned no text" }
            Result.success(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> =
        chat(messages)

    override suspend fun chatStreaming(
        model: String,
        messages: List<OllamaMessage>,
        onPartial: (String) -> Unit
    ): Result<String> {
        val result = chat(messages)
        result.getOrNull()?.let(onPartial)
        return result
    }

    /**
     * Clients are intentionally cheap facades. Closing one must not evict the process-wide model,
     * otherwise every agent step would pay the GGUF load cost again.
     */
    override fun close() = Unit

    private fun prepareMessages(
        messages: List<OllamaMessage>,
        contextSize: Int
    ): List<OllamaMessage> {
        val charBudget = (contextSize * 4).coerceAtLeast(4_096)
        val system = messages.firstOrNull { it.role == "system" }

        val kept = ArrayDeque<OllamaMessage>()
        var remaining = charBudget

        val systemMessage = system?.let {
            val content = if (it.content.length <= charBudget / 2) {
                it.content
            } else {
                it.content.take(charBudget / 2)
            }
            remaining -= content.length
            OllamaMessage("system", content)
        }

        for (message in messages.asReversed()) {
            if (message.role == "system") continue
            val normalizedRole = when (message.role) {
                "assistant" -> "assistant"
                else -> "user"
            }
            val content = if (message.content.length <= remaining) {
                message.content
            } else {
                message.content.takeLast(remaining.coerceAtLeast(0))
            }

            if (content.isBlank() && kept.isNotEmpty()) break
            kept.addFirst(OllamaMessage(normalizedRole, content))
            remaining -= content.length
            if (remaining <= 0) break
        }

        return buildList {
            systemMessage?.let(::add)
            addAll(kept)
        }
    }

    companion object {
        fun cancelActiveGeneration() = EmbeddedLlamaRuntime.cancelActiveGeneration()
    }
}
