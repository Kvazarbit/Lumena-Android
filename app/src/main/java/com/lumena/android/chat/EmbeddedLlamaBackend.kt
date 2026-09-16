package com.lumena.android.chat

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.lumena.android.ollama.OllamaMessage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * llama.cpp/JNI backend bundled into the APK through the upstream llama.android library.
 * The native engine is a singleton, so all model/session access is serialized here.
 */
class EmbeddedLlamaBackend(
    private val context: Context,
    private val modelPath: String,
    private val modelName: String
) : ChatBackend {
    override val label: String = "Embedded · $modelName"

    override suspend fun complete(history: List<OllamaMessage>): Result<String> =
        EmbeddedLlamaRuntime.complete(context.applicationContext, modelPath, history)
}

private object EmbeddedLlamaRuntime {
    private val mutex = Mutex()
    private var engine: InferenceEngine? = null
    private var activeModelPath: String? = null
    private var activeSystemPrompt: String? = null

    // Number of externally visible messages that the native session already knows about.
    // After a generation the native model also knows the assistant reply, so this is
    // history.size + 1 even before the caller appends that reply to its own list.
    private var syncedHistorySize: Int = 1

    suspend fun complete(
        context: Context,
        modelPath: String,
        history: List<OllamaMessage>
    ): Result<String> = mutex.withLock {
        runCatching {
            require(modelPath.isNotBlank()) { "Choose an embedded GGUF model first" }
            val systemPrompt = history.firstOrNull { it.role == "system" }?.content
                ?.takeIf { it.isNotBlank() }
                ?: "You are Lumena, a local Android assistant."

            val e = engine ?: AiChat.getInferenceEngine(context).also { engine = it }
            awaitInitialized(e)

            val mustReload = activeModelPath != modelPath ||
                activeSystemPrompt != systemPrompt ||
                !e.state.value.isModelLoaded

            if (mustReload) {
                resetIfNeeded(e)
                awaitInitialized(e)
                e.loadModel(modelPath)
                e.setSystemPrompt(systemPrompt)
                activeModelPath = modelPath
                activeSystemPrompt = systemPrompt
                syncedHistorySize = 1 // system prompt only
            }

            val finalUser = history.lastOrNull()
                ?: error("Conversation is empty")
            require(finalUser.role == "user") {
                "Embedded backend expects the latest message to be a user/tool-result message"
            }

            val sessionIsAligned = history.size == syncedHistorySize + 1
            val prompt = if (sessionIsAligned) {
                finalUser.content
            } else {
                // If Android recreated the UI/process or a provider was switched, rebuild
                // enough conversational context in one user prompt instead of pretending
                // the native KV cache still contains it.
                resetIfNeeded(e)
                awaitInitialized(e)
                e.loadModel(modelPath)
                e.setSystemPrompt(systemPrompt)
                activeModelPath = modelPath
                activeSystemPrompt = systemPrompt
                syncedHistorySize = 1
                renderTranscript(history)
            }

            val output = StringBuilder()
            e.sendUserPrompt(prompt, predictLength = 1024).collect { token ->
                output.append(token)
            }
            val text = output.toString().trim()
            require(text.isNotBlank()) { "Embedded llama.cpp model returned an empty response" }

            syncedHistorySize = history.size + 1
            text
        }
    }

    private suspend fun awaitInitialized(e: InferenceEngine) {
        val state = e.state.value
        if (state is InferenceEngine.State.Initialized || state.isModelLoaded) return
        if (state is InferenceEngine.State.Error) {
            e.cleanUp()
            return
        }
        val settled = e.state.first {
            it is InferenceEngine.State.Initialized ||
                it is InferenceEngine.State.Error ||
                it.isModelLoaded
        }
        if (settled is InferenceEngine.State.Error) {
            e.cleanUp()
        }
    }

    private fun resetIfNeeded(e: InferenceEngine) {
        when (e.state.value) {
            is InferenceEngine.State.ModelReady,
            is InferenceEngine.State.Error -> e.cleanUp()
            is InferenceEngine.State.Initialized -> Unit
            else -> {
                // All calls are serialized by mutex. If another transient state still
                // appears here it is safer to fail than unload native state mid-operation.
                error("llama.cpp engine is busy: ${e.state.value::class.simpleName}")
            }
        }
        activeModelPath = null
        activeSystemPrompt = null
        syncedHistorySize = 1
    }

    private fun renderTranscript(history: List<OllamaMessage>): String = buildString {
        appendLine("Conversation transcript follows. Continue from the final USER message.")
        appendLine("Do not repeat the transcript.")
        appendLine()
        history.filter { it.role != "system" }.forEach { message ->
            append(if (message.role == "assistant") "ASSISTANT: " else "USER: ")
            appendLine(message.content)
            appendLine()
        }
        append("Respond now to the final USER message only.")
    }
}
