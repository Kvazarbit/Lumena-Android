package com.lumena.android.ollama

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OllamaMessage(
    val role: String,
    val content: String = "",
    val tool_calls: List<OllamaToolCall>? = null,
    val tool_name: String? = null
)
data class OllamaOptions(val num_ctx: Int = 4096, val num_predict: Int = 1024, val temperature: Double = 0.15)
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val keep_alive: String = "5m",
    val options: OllamaOptions = OllamaOptions(),
    val tools: List<OllamaToolSchema>? = null
)
data class OllamaChatResponse(
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    val done_reason: String? = null,
    val error: String? = null
)
data class OllamaModel(val name: String, val remote_host: String? = null)
data class OllamaTagsResponse(val models: List<OllamaModel> = emptyList())
data class OllamaShowResponse(val capabilities: List<String> = emptyList())

class OllamaClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf {
        it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1", "[::1]") &&
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    } ?: throw IllegalArgumentException("Ollama URL must use loopback HTTP")
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(11, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val metadataClient = client.newBuilder()
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(OllamaChatRequest::class.java)
    private val responseAdapter = moshi.adapter(OllamaChatResponse::class.java)
    private val tagsAdapter = moshi.adapter(OllamaTagsResponse::class.java)
    private val showAdapter = moshi.adapter(OllamaShowResponse::class.java)
    private val mapAdapter = moshi.adapter(Map::class.java)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(): Result<List<String>> = resultOnIo {
        val request = Request.Builder().url(base.newBuilder().addPathSegments("api/tags").build()).get().build()
        tagsAdapter.fromJson(metadataClient.newCall(request).awaitText())?.models?.map { it.name }.orEmpty()
    }

    suspend fun supportsNativeTools(model: String): Result<Boolean> = resultOnIo {
        val payload = mapAdapter.toJson(mapOf("model" to model))
        val request = Request.Builder().url(base.newBuilder().addPathSegments("api/show").build())
            .post(payload.toRequestBody(jsonType)).build()
        showAdapter.fromJson(metadataClient.newCall(request).awaitText())?.capabilities?.contains("tools") == true
    }

    suspend fun chatTurn(model: String, messages: List<OllamaMessage>, native: Boolean): Result<OllamaTurn> = resultOnIo {
        require(model.isNotBlank()) { "Choose an Ollama model first" }
        // The full history stays on disk. Only the inference window is compacted.
        val compact = OllamaContextWindow.compact(messages)
        val wireMessages = if (native) compact else OllamaContextWindow.asJsonProtocol(compact)
        val payload = requestAdapter.toJson(OllamaChatRequest(
            model, wireMessages, tools = if (native) NativeTools.schemas() else null
        ))
        val request = Request.Builder().url(base.newBuilder().addPathSegments("api/chat").build())
            .post(payload.toRequestBody(jsonType)).build()
        val parsed = responseAdapter.fromJson(client.newCall(request).awaitText())
            ?: throw IOException("Ollama returned an unreadable response")
        parsed.error?.let { throw IOException(it) }
        if (!parsed.done || parsed.done_reason == "length") {
            throw IOException("Model output is incomplete/token-limited. No tool from this response was executed.")
        }
        val message = parsed.message ?: throw IOException("Ollama returned no message")
        require(message.role == "assistant") { "Ollama returned an unexpected message role" }
        if (message.content.isBlank() && message.tool_calls.isNullOrEmpty()) throw IOException("Ollama returned an empty answer")
        OllamaTurn(message, parsed.done_reason)
    }

    // Compatibility for callers outside the workflow.
    suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> =
        chatTurn(model, messages, native = false).map { it.message.content }

    private suspend fun <T> resultOnIo(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { Result.failure(e) }
    }
}
