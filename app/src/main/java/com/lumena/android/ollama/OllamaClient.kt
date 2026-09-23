package com.lumena.android.ollama

import com.lumena.android.llama.LlamaRuntimeProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OllamaMessage(
    val role: String,
    val content: String
)

data class OllamaOptions(
    val num_ctx: Int = 4096,
    val num_predict: Int = 768,
    val temperature: Double = 0.15
)

data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val keep_alive: String = "10m",
    val options: OllamaOptions = OllamaOptions()
)

data class OllamaChatResponse(
    val message: OllamaMessage? = null,
    val done: Boolean? = null,
    val error: String? = null
)

private class EmptyOllamaStreamException :
    IllegalStateException("Ollama stream completed without assistant message content")

internal fun ollamaChunkError(chunk: OllamaChatResponse): String? =
    chunk.error?.trim()?.takeIf { it.isNotEmpty() }

internal fun shouldRetryOllamaWithSmallerContext(error: Throwable): Boolean {
    if (error is SocketTimeoutException) return false
    val lower = error.message.orEmpty().lowercase()
    return listOf(
        "context length",
        "context window",
        "prompt too long",
        "too many tokens",
        "input is too long",
        "maximum context",
        "requested tokens exceed",
        "exceeds the context",
        "num_ctx"
    ).any(lower::contains)
}

internal fun isOllamaTransportTimeout(error: Throwable): Boolean =
    error is SocketTimeoutException ||
        error.message.orEmpty().contains("timeout", ignoreCase = true) ||
        error.message.orEmpty().contains("timed out", ignoreCase = true)

data class OllamaModel(
    val name: String
)

data class OllamaTagsResponse(
    val models: List<OllamaModel> = emptyList()
)

interface ChatModelClient {
    suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String>

    suspend fun chatStreaming(
        model: String,
        messages: List<OllamaMessage>,
        onPartial: (String) -> Unit
    ): Result<String> {
        val result = chat(model, messages)
        result.getOrNull()?.let(onPartial)
        return result
    }
}

class OllamaClient(
    baseUrl: String,
    private val runtimeProfile: LlamaRuntimeProfile? = null
) : ChatModelClient {
    private val base = normalizeLoopbackBaseUrl(baseUrl)
        ?: throw IllegalArgumentException("Ollama URL must use localhost/127.0.0.1 over http")

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(11, TimeUnit.MINUTES)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val requestAdapter = moshi.adapter(OllamaChatRequest::class.java)
    private val responseAdapter = moshi.adapter(OllamaChatResponse::class.java)
    private val tagsAdapter = moshi.adapter(OllamaTagsResponse::class.java)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(base.newBuilder().addPathSegments("api/tags").build())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Ollama HTTP ${response.code}: $body")
                tagsAdapter.fromJson(body)?.models?.map { it.name }.orEmpty()
            }
        }
    }

    /**
     * Agent chat is bounded and cancellable. Cancelling the owning coroutine immediately
     * cancels the active OkHttp/Ollama request, which is what the Local STOP button uses.
     */
    override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> =
        chatStreaming(model, messages) { }

    override suspend fun chatStreaming(
        model: String,
        messages: List<OllamaMessage>,
        onPartial: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(model.isNotBlank()) { "Choose an Ollama model first" }
            val normalBudget = OllamaContextPolicy.budget(runtimeProfile, retry = false)
            val text = try {
                executeStreamingWithEmptyFallback(
                    model = model,
                    messages = OllamaContextPolicy.compact(messages, normalBudget),
                    options = normalBudget.options,
                    onPartial = onPartial
                )
            } catch (first: Throwable) {
                if (first is CancellationException) throw first
                if (!shouldRetryOllamaWithSmallerContext(first)) throw first

                onPartial("")
                val retryBudget = OllamaContextPolicy.budget(runtimeProfile, retry = true)
                executeStreamingWithEmptyFallback(
                    model = model,
                    messages = OllamaContextPolicy.compact(messages, retryBudget),
                    options = retryBudget.options,
                    onPartial = onPartial
                )
            }
            Result.success(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun executeStreamingWithEmptyFallback(
        model: String,
        messages: List<OllamaMessage>,
        options: OllamaOptions,
        onPartial: (String) -> Unit
    ): String {
        return try {
            executeStreamingChat(
                model = model,
                messages = messages,
                options = options,
                onPartial = onPartial
            )
        } catch (empty: EmptyOllamaStreamException) {
            // Some remote/cloud-backed Ollama models can finish an NDJSON
            // stream without any assistant message content even though the
            // same /api/chat request succeeds in non-streaming mode.
            // This fallback is a model transport compatibility retry only;
            // it cannot execute tools or alter tool authority.
            onPartial("")
            executeChat(
                model = model,
                messages = messages,
                options = options
            )
        }
    }

    private suspend fun executeStreamingChat(
        model: String,
        messages: List<OllamaMessage>,
        options: OllamaOptions,
        onPartial: (String) -> Unit
    ): String {
        val payload = requestAdapter.toJson(
            OllamaChatRequest(
                model = model,
                messages = messages,
                stream = true,
                options = options
            )
        )
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("api/chat").build())
            .post(payload.toRequestBody(jsonType))
            .build()
        val call = client.newCall(request)
        val context = currentCoroutineContext()
        val cancelHandle = context[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    error("Ollama HTTP ${response.code}: $body")
                }
                val source = response.body?.source() ?: error("Ollama returned no response body")
                val accumulated = StringBuilder()
                while (true) {
                    context.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val chunk = responseAdapter.fromJson(line) ?: continue
                    ollamaChunkError(chunk)?.let { message ->
                        error("Ollama stream error: $message")
                    }
                    chunk.message?.content?.let { piece ->
                        if (piece.isNotEmpty()) {
                            accumulated.append(piece)
                            onPartial(accumulated.toString())
                        }
                    }
                    if (chunk.done == true) break
                }
                val text = accumulated.toString()
                if (text.isBlank()) throw EmptyOllamaStreamException()
                return text
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private suspend fun executeChat(
        model: String,
        messages: List<OllamaMessage>,
        options: OllamaOptions
    ): String = suspendCancellableCoroutine { continuation ->
        val payload = requestAdapter.toJson(
            OllamaChatRequest(
                model = model,
                messages = messages,
                stream = false,
                options = options
            )
        )
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("api/chat").build())
            .post(payload.toRequestBody(jsonType))
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) error("Ollama HTTP ${it.code}: $body")
                        val parsed = responseAdapter.fromJson(body)
                            ?: error("Ollama returned unreadable response")
                        ollamaChunkError(parsed)?.let { message ->
                            error("Ollama response error: $message")
                        }
                        val text = parsed.message?.content
                            ?.takeIf { value -> value.isNotBlank() }
                            ?: error("Ollama returned no message")
                        if (continuation.isActive) continuation.resume(text)
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(t)
                }
            }
        })
    }


    private fun normalizeLoopbackBaseUrl(raw: String) = raw.trim().trimEnd('/')
        .toHttpUrlOrNull()
        ?.takeIf { url ->
            url.scheme == "http" &&
                (url.host == "127.0.0.1" || url.host == "localhost" || url.host == "[::1]" || url.host == "::1")
        }
}
