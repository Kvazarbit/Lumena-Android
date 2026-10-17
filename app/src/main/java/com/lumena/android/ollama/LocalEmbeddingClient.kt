package com.lumena.android.ollama

import com.lumena.android.settings.SemanticMemoryPolicy
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class LocalEmbeddingIdentity(
    val name: String,
    val digest: String,
    val sizeBytes: Long
) {
    val cacheKey: String get() = name + "@" + digest
}

/** Inject fake embeddings in JVM tests; no model download is performed. */
interface LocalEmbeddingProvider {
    suspend fun inspectModel(name: String): LocalEmbeddingIdentity
    suspend fun embed(identity: LocalEmbeddingIdentity, texts: List<String>): List<FloatArray>
}

internal data class EmbeddingModelTag(
    val name: String,
    val size: Long = 0L,
    val digest: String = ""
)
internal data class EmbeddingTags(val models: List<EmbeddingModelTag> = emptyList())
internal data class EmbedRequest(
    val model: String,
    val input: List<String>,
    val truncate: Boolean = false,
    val keep_alive: String = "0"
)
internal data class EmbedResponse(
    val model: String? = null,
    val embeddings: List<List<Double>> = emptyList(),
    val error: String? = null
)

internal object LocalEmbeddingProtocol {
    private val safeName = Regex("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,99}")
    private val digestPattern = Regex("[a-f0-9]{64}")

    fun modelName(raw: String): String {
        val model = raw.trim()
        require(safeName.matches(model)) { "Invalid Ollama embedding tag" }
        val lower = model.lowercase()
        require(!isCloudBackedOllamaModel(model) && !lower.contains("remote") &&
            !lower.startsWith("http")) { "Cloud embeddings are forbidden" }
        return model
    }

    fun select(name: String, candidates: List<EmbeddingModelTag>): LocalEmbeddingIdentity {
        val safe = modelName(name)
        val record = candidates.singleOrNull { it.name == safe }
            ?: throw IllegalStateException("Embedding model not installed under that exact Ollama tag")
        require(record.size >= 1_000_000L && digestPattern.matches(record.digest)) {
            "Embedding model has no verified local-size artifact and digest"
        }
        return LocalEmbeddingIdentity(safe, record.digest, record.size)
    }

    fun vectors(model: String, decoded: EmbedResponse, count: Int): List<FloatArray> {
        require(decoded.error.isNullOrBlank()) { "Ollama refused embedding" }
        require(decoded.model.isNullOrBlank() || decoded.model == model ||
            (':' !in model && decoded.model == model + ":latest")) {
            "Ollama replied with another model"
        }
        require(count in 1..SemanticMemoryPolicy.MAX_BATCH && decoded.embeddings.size == count) {
            "Embedding count mismatch"
        }
        return decoded.embeddings.map(SemanticMemoryPolicy::normalized).also {
            require(it.map(FloatArray::size).distinct().size == 1) {
                "Embedding dimension mismatch"
            }
        }
    }
}

/**
 * Only local Ollama /api/tags + /api/embed. Does not pull, load remote assets,
 * create agent tools, or send transcript/file content. No cloud fallbacks.
 * Actual EmbeddingGemma 2 Ollama compatibility still needs phone verification.
 */
class LocalEmbeddingClient(baseUrl: String) : LocalEmbeddingProvider {
    private val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?.takeIf {
            it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "::1", "[::1]") &&
                it.encodedPath == "/" && it.username.isBlank() && it.password.isBlank() &&
                it.query == null && it.fragment == null
        } ?: throw IllegalArgumentException("Local embedding requires a plain loopback Ollama URL")

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(80, TimeUnit.SECONDS)
        .build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val tagsAdapter = moshi.adapter(EmbeddingTags::class.java)
    private val requestAdapter = moshi.adapter(EmbedRequest::class.java)
    private val responseAdapter = moshi.adapter(EmbedResponse::class.java)
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun request(path: String, json: String? = null): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(base.newBuilder().addPathSegments(path).build())
            val req = if (json == null) builder.get().build()
                else builder.post(json.toRequestBody(mediaType)).build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    // Do not disclose response contents, which may echo private text.
                    throw IllegalStateException("Local Ollama " + path + " HTTP " + response.code)
                }
                val stream = requireNotNull(response.body) { "Ollama returned no content" }.byteStream()
                val bytes = ByteArrayOutputStream()
                stream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(bytes.size() + count <= 1_000_000) {
                            "Oversized local embedding response"
                        }
                        bytes.write(buffer, 0, count)
                    }
                }
                bytes.toString("UTF-8")
            }
        }

    override suspend fun inspectModel(name: String): LocalEmbeddingIdentity {
        val safe = LocalEmbeddingProtocol.modelName(name)
        val tags = tagsAdapter.fromJson(request("api/tags"))
            ?: throw IllegalStateException("Local Ollama tags missing")
        return LocalEmbeddingProtocol.select(safe, tags.models)
    }

    override suspend fun embed(identity: LocalEmbeddingIdentity, texts: List<String>): List<FloatArray> {
        require(texts.size in 1..SemanticMemoryPolicy.MAX_BATCH &&
            texts.all { it.isNotBlank() && it.length <= SemanticMemoryPolicy.MAX_TEXT }) {
            "Text batch exceeds limits"
        }
        val fresh = inspectModel(identity.name)
        require(fresh == identity) { "Model digest changed, vector coordinates invalidated" }
        val input = requestAdapter.toJson(EmbedRequest(identity.name, texts))
        val decoded = responseAdapter.fromJson(request("api/embed", input))
            ?: throw IllegalStateException("Local Ollama embedding response missing")
        return LocalEmbeddingProtocol.vectors(identity.name, decoded, texts.size)
    }
}
