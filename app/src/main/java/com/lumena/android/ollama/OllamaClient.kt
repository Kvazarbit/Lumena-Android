package com.lumena.android.ollama

import com.lumena.android.network.awaitBody
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OllamaMessage(val role: String = "assistant", val content: String = "", val thinking: String? = null)
data class OllamaOptions(val num_ctx: Int = 4096, val num_predict: Int = 1024, val temperature: Double = 0.15)
data class OllamaChatRequest(
    val model: String, val messages: List<OllamaMessage>, val stream: Boolean = true,
    val keep_alive: String = "10m", val options: OllamaOptions = OllamaOptions()
)
data class OllamaChatResponse(
    val message: OllamaMessage? = null, val done: Boolean = false,
    val done_reason: String? = null, val error: String? = null
)
data class OllamaModel(val name: String)
data class OllamaTagsResponse(val models: List<OllamaModel> = emptyList())
/** Counts are received fragments/characters, NOT a fabricated completion percentage. */
data class ModelPulse(val chunks: Int = 0, val contentChars: Int = 0, val thinkingChars: Int = 0)

class OllamaClient(baseUrl: String, suppliedClient: OkHttpClient? = null) {
    private val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf {
        it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1", "[::1]") &&
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    } ?: throw IllegalArgumentException("Ollama must use an HTTP loopback URL")
    private val client = suppliedClient ?: OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(11, TimeUnit.MINUTES)
        .followRedirects(false).retryOnConnectionFailure(false).build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(OllamaChatRequest::class.java)
    private val responseAdapter = moshi.adapter(OllamaChatResponse::class.java)
    private val tagsAdapter = moshi.adapter(OllamaTagsResponse::class.java)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun listModels(): Result<List<String>> = try {
        val request = Request.Builder().url(base.newBuilder().addPathSegments("api/tags").build()).get().build()
        Result.success(client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build().awaitBody(request) { response ->
            if (!response.isSuccessful) throw IOException("Ollama HTTP ${response.code}")
            tagsAdapter.fromJson(response.body?.string().orEmpty())?.models?.map { it.name }.orEmpty()
        })
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { Result.failure(e) }

    suspend fun chat(model: String, messages: List<OllamaMessage>, onPulse: (ModelPulse) -> Unit = {}): Result<String> = try {
        require(model.isNotBlank()) { "Choose a model first" }
        val payload = requestAdapter.toJson(OllamaChatRequest(model, compactMessages(messages)))
        val request = Request.Builder().url(base.newBuilder().addPathSegments("api/chat").build())
            .post(payload.toRequestBody(jsonType)).build()
        onPulse(ModelPulse())
        Result.success(client.awaitBody(request) { response ->
            if (!response.isSuccessful) throw IOException("Ollama HTTP ${response.code}: ${response.body?.string()?.take(500)}")
            val source = response.body?.source() ?: throw IOException("Empty Ollama response")
            val content = StringBuilder()
            var chunks = 0
            var thinkingChars = 0
            var finished = false
            var lastPulse = 0L
            while (!source.exhausted()) {
                val line = source.readUtf8LineStrict(512L * 1024) // bound each NDJSON frame
                if (line.isBlank()) continue
                val frame = responseAdapter.fromJson(line) ?: throw IOException("Invalid Ollama frame")
                frame.error?.let { throw IOException(it.take(500)) }
                chunks++
                content.append(frame.message?.content.orEmpty())
                thinkingChars += frame.message?.thinking?.length ?: 0
                if (content.length + thinkingChars > 512_000) throw IOException("Model output limit exceeded")
                val now = System.nanoTime()
                if (now - lastPulse > 150_000_000L || frame.done) {
                    onPulse(ModelPulse(chunks, content.length, thinkingChars))
                    lastPulse = now
                }
                if (frame.done) {
                    if (frame.done_reason == "length") throw IOException("Model generation budget ended; no partial command was executed")
                    finished = true
                    break
                }
            }
            if (!finished) throw IOException("Model stream ended before done; no partial command was executed")
            content.toString().takeIf { it.isNotBlank() } ?: throw IOException("Model returned no final content")
        })
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) { Result.failure(e) }

    internal fun compactMessages(messages: List<OllamaMessage>): List<OllamaMessage> {
        val system = messages.firstOrNull { it.role == "system" }
        require((system?.content?.length ?: 0) <= 12_000) { "Pinned task context is too large; split the task" }
        var used = system?.content?.length ?: 0
        val recent = mutableListOf<OllamaMessage>()
        for (message in messages.filterNot { it.role == "system" }.asReversed()) {
            // Preserve whole turns, especially JSON calls and their result headers.
            if (used + message.content.length > 14_000) break
            recent += message.copy(thinking = null)
            used += message.content.length
        }
        if (recent.isEmpty() && messages.any { it.role != "system" })
            throw IllegalArgumentException("Latest message does not fit the context budget; shorten it")
        return listOfNotNull(system?.copy(thinking = null)) + recent.asReversed()
    }
}
