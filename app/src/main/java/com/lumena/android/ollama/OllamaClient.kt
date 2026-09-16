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
import java.util.concurrent.TimeUnit

data class OllamaMessage(
    val role: String,
    val content: String
)

data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false
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
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

    suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(model.isNotBlank()) { "Choose an Ollama model first" }
            val payload = requestAdapter.toJson(
                OllamaChatRequest(model = model, messages = messages, stream = false)
            )
            val request = Request.Builder()
                .url(base.newBuilder().addPathSegments("api/chat").build())
                .post(payload.toRequestBody(jsonType))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Ollama HTTP ${response.code}: $body")
                responseAdapter.fromJson(body)?.message?.content
                    ?.takeIf { it.isNotBlank() }
                    ?: error("Ollama returned no message")
            }
        }
    }

    private fun normalizeLoopbackBaseUrl(raw: String) = raw.trim().trimEnd('/')
        .toHttpUrlOrNull()
        ?.takeIf { url ->
            url.scheme == "http" &&
                (url.host == "127.0.0.1" || url.host == "localhost" || url.host == "[::1]" || url.host == "::1")
        }
}
