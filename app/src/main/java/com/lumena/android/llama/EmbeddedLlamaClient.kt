package com.lumena.android.llama

import android.content.Context
import android.net.Uri
import com.lumena.android.ollama.ChatModelClient
import com.lumena.android.ollama.OllamaMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

class EmbeddedLlamaClient(
    context: Context,
    private val modelRef: String,
    private val contextSize: Int = 4096,
    private val maxTokens: Int = 768,
    private val temperature: Float = 0.15f
) : Closeable, ChatModelClient {
    private val appContext = context.applicationContext
    @Volatile private var handle: Long = 0

    private fun loadFromContentUri(uri: Uri): Long {
        val descriptor = appContext.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Android could not open the selected GGUF file")
        return descriptor.use { pfd ->
            check(pfd.fd >= 0) { "Android returned an invalid file descriptor for the GGUF model" }
            LlamaNative.nativeLoadModel("/proc/self/fd/${pfd.fd}", 0)
        }
    }

    suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(modelRef.isNotBlank()) { "Choose a GGUF model first" }
            if (handle == 0L) {
                handle = if (modelRef.startsWith("content://")) {
                    loadFromContentUri(Uri.parse(modelRef))
                } else {
                    require(File(modelRef).isFile) { "GGUF model not found: $modelRef" }
                    LlamaNative.nativeLoadModel(modelRef, 0)
                }
                check(handle != 0L) {
                    "llama.cpp could not load this GGUF model. Re-select the file if Android storage access changed."
                }
            }
        }
    }

    suspend fun chat(messages: List<OllamaMessage>): Result<String> = withContext(Dispatchers.IO) {
        try {
            load().getOrThrow()
            val prompt = messages.joinToString("\n") { message ->
                val role = when (message.role) {
                    "system" -> "System"
                    "assistant" -> "Assistant"
                    else -> "User"
                }
                "$role: ${message.content}"
            } + "\nAssistant:"
            val text = LlamaNative.nativeGenerate(
                handle = handle,
                prompt = prompt,
                contextSize = contextSize,
                maxTokens = maxTokens,
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

    override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> = chat(messages)

    override fun close() {
        val current = handle
        handle = 0
        if (current != 0L) LlamaNative.nativeFreeModel(current)
    }
}
