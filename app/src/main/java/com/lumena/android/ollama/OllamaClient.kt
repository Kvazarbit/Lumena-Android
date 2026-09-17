package com.lumena.android.ollama

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

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
    val done: Boolean? = null
)

data class OllamaModel(
    val name: String
)

data class OllamaTagsResponse(
    val models: List<OllamaModel> = emptyList()
)

class OllamaClient(baseUrl: String) {
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
     * Agent chat is deliberately bounded. Small local models become slower and less reliable
     * when every persisted chat message is replayed. We keep the system instruction plus the
     * newest relevant turns, then retry once with a smaller context if the first generation
     * times out.
     */
    suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(model.isNotBlank()) { "Choose an Ollama model first" }
            try {
                executeChat(
                    model = model,
                    messages = compactMessages(messages, maxChars = 14_000, maxPerMessage = 5_000),
                    options = OllamaOptions(num_ctx = 4096, num_predict = 768, temperature = 0.15)
                )
            } catch (timeout: SocketTimeoutException) {
                executeChat(
                    model = model,
                    messages = compactMessages(messages, maxChars = 8_000, maxPerMessage = 3_000),
                    options = OllamaOptions(num_ctx = 3072, num_predict = 512, temperature = 0.1)
                )
            }
        }
    }

    private fun executeChat(
        model: String,
        messages: List<OllamaMessage>,
        options: OllamaOptions
    ): String {
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
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Ollama HTTP ${response.code}: $body")
            return responseAdapter.fromJson(body)?.message?.content
                ?.takeIf { it.isNotBlank() }
                ?: error("Ollama returned no message")
        }
    }

    private fun compactMessages(
        messages: List<OllamaMessage>,
        maxChars: Int,
        maxPerMessage: Int
    ): List<OllamaMessage> {
        if (messages.isEmpty()) return messages
        val system = messages.firstOrNull { it.role == "system" }
        val nonSystem = messages.filterNot { it.role == "system" }

        var used = system?.content?.length?.coerceAtMost(maxPerMessage) ?: 0
        val recent = ArrayList<OllamaMessage>()
        for (message in nonSystem.asReversed()) {
            val clipped = message.content.takeLast(maxPerMessage)
            if (recent.isNotEmpty() && used + clipped.length > maxChars) break
            recent += message.copy(content = clipped)
            used += clipped.length
        }
        recent.reverse()

        return buildList {
            system?.let { add(it.copy(content = it.content.take(maxPerMessage))) }
            addAll(recent)
        }
    }

    private fun normalizeLoopbackBaseUrl(raw: String) = raw.trim().trimEnd('/')
        .toHttpUrlOrNull()
        ?.takeIf { url ->
            url.scheme == "http" &&
                (url.host == "127.0.0.1" || url.host == "localhost" || url.host == "[::1]" || url.host == "::1")
        }
}
