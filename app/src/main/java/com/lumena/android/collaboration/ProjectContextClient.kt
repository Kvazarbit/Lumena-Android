package com.lumena.android.collaboration

import com.lumena.android.ollama.ChatModelClient
import com.lumena.android.ollama.OllamaMessage
import kotlinx.coroutines.CancellationException

/** Last boundary before inference, including private derived history and restored pending turns. */
class ProjectContextClient(
    private val delegate: ChatModelClient,
    private val backend: String,
    private val url: String,
    private val localAttested: (String) -> Boolean,
    private val privateProjects: () -> Set<String>,
    private val ownerAllowsRemote: (String) -> Boolean
) : ChatModelClient {
    private fun refusal(model: String): String? {
        if (CollaborationReview.localRoute(backend, url, model, localAttested(model))) return null
        return try {
            if (privateProjects().all(ownerAllowsRemote)) null else
                "PROJECT_CONTEXT_PRIVATE: контекст цього сеансу локальний. Обери вбудовану модель, " +
                    "підтвердь локальну Ollama або явно дозволь зовнішню передачу в параметрах кожного проєкту цього сеансу."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { "PROJECT_CONTEXT_PRIVATE: не вдалося перевірити дозволи; запит не надіслано." }
    }
    override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> =
        refusal(model)?.let { Result.failure(IllegalStateException(it)) } ?: delegate.chat(model, messages)

    override suspend fun chatStreaming(model: String, messages: List<OllamaMessage>, onPartial: (String) -> Unit): Result<String> =
        refusal(model)?.let { Result.failure(IllegalStateException(it)) } ?: delegate.chatStreaming(model, messages, onPartial)
}
