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
    private val temperature: Float = 0.15f
) : Closeable, ChatModelClient {
    private val appContext = context.applicationContext

    suspend fun chat(messages: List<OllamaMessage>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val profile = LlamaHardwareProfile.detect(appContext)
            val prompt = buildPrompt(messages, profile.contextSize)
            val text = EmbeddedLlamaRuntime.generate(
                context = appContext,
                modelRef = modelRef,
                prompt = prompt,
                profile = profile,
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

    /**
     * Clients are intentionally cheap facades. Closing one must not evict the process-wide model,
     * otherwise every agent step would pay the GGUF load cost again.
     */
    override fun close() = Unit

    private fun buildPrompt(messages: List<OllamaMessage>, contextSize: Int): String {
        // Conservative multilingual budget. Native code still enforces the exact token limit.
        val charBudget = (contextSize * 4).coerceAtLeast(4_096)
        val system = messages.firstOrNull { it.role == "system" }
        val systemText = system?.let { "System: ${it.content}\n" }.orEmpty()
        val keptSystem = if (systemText.length <= charBudget / 2) {
            systemText
        } else {
            systemText.take(charBudget / 2) + "\n"
        }

        var remaining = (charBudget - keptSystem.length - 32).coerceAtLeast(512)
        val recent = ArrayDeque<String>()
        for (message in messages.asReversed()) {
            if (message.role == "system") continue
            val role = when (message.role) {
                "assistant" -> "Assistant"
                else -> "User"
            }
            val line = "$role: ${message.content}\n"
            if (line.length > remaining && recent.isNotEmpty()) break
            val kept = if (line.length <= remaining) line else line.takeLast(remaining)
            recent.addFirst(kept)
            remaining -= kept.length
            if (remaining <= 0) break
        }

        return buildString {
            append(keptSystem)
            recent.forEach(::append)
            append("Assistant:")
        }
    }

    companion object {
        fun cancelActiveGeneration() = EmbeddedLlamaRuntime.cancelActiveGeneration()
    }
}
