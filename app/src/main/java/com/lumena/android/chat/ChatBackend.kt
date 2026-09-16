package com.lumena.android.chat

import com.lumena.android.ollama.OllamaClient
import com.lumena.android.ollama.OllamaMessage

/** A model-agnostic chat backend used by the workflow agent. */
interface ChatBackend {
    val label: String
    suspend fun complete(history: List<OllamaMessage>): Result<String>
}

class OllamaChatBackend(
    private val client: OllamaClient,
    private val model: String
) : ChatBackend {
    override val label: String = "Ollama · $model"

    override suspend fun complete(history: List<OllamaMessage>): Result<String> =
        client.chat(model, history)
}
