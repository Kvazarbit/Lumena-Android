package com.lumena.android.llama

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.lumena.android.ollama.LocalEmbeddingIdentity
import com.lumena.android.ollama.LocalEmbeddingProvider
import com.lumena.android.settings.SemanticMemoryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A real Android native LiteRT-LM EmbeddingEngine, pinned to Google's v0.18.0
 * Kotlin API and bundled AAR. CPU-only, text-only and SHADOW-only.
 *
 * This is not Ollama, and does not execute the model from Google Gallery or
 * automatically fetch model files. Open once per owner UI operation and
 * close in a finally block to release native RAM.
 */
class LiteRtLmEmbeddingProvider(
    context: Context
) : LocalEmbeddingProvider, AutoCloseable {
    private val app = context.applicationContext
    private val lock = Any()
    private var engine: EmbeddingEngine? = null
    private var identity: LocalEmbeddingIdentity? = null
    private var file: File? = null
    private var embeddingDim: Int? = null
    private var closed = false

    companion object {
        const val MODEL_ID = "litertlm-0.18.0-cpu-text-embedding-v1"
        private const val SMOKE_INPUT = "Lumena local embedding readiness check."
    }

    override suspend fun inspectModel(name: String): LocalEmbeddingIdentity =
        withContext(Dispatchers.IO) {
            require(name == MODEL_ID) { "Unrecognized LiteRT embedding backend identity" }
            synchronized(lock) {
                check(!closed) { "Native embedding provider is closed" }
                identity?.let { return@synchronized it }
                val selected = SemanticLiteRtModelStore.selected(app)
                    ?: throw IllegalStateException("NOT_INSTALLED: import a text 270M .litertlm file first")
                val (dim, _) = SemanticLiteRtModelStore.inspect(selected.absolutePath)
                val sha = SemanticLiteRtModelStore.sha256(selected)
                val next = LocalEmbeddingIdentity(MODEL_ID, sha, selected.length())
                identity = next
                file = selected
                embeddingDim = dim
                next
            }
        }

    private fun getEngine(): EmbeddingEngine = synchronized(lock) {
        check(!closed) { "Native embedding provider is closed" }
        engine?.let { return@synchronized it }
        val model = requireNotNull(file) { "Inspect model before inference" }
        val config = EmbeddingEngineConfig(
            modelPath = model.absolutePath,
            backend = Backend.CPU(threadCount = 2),
            cacheDir = File(app.cacheDir, "semantic-litert-0.18.0-cpu").apply {
                if (!exists()) check(mkdirs())
            }.absolutePath
        )
        val created = EmbeddingEngine(config)
        try {
            created.initialize()
            engine = created
            created
        } catch (failure: Throwable) {
            runCatching { if (created.isInitialized()) created.close() }
            throw failure
        }
    }

    override suspend fun embed(
        identity: LocalEmbeddingIdentity,
        texts: List<String>
    ): List<FloatArray> = withContext(Dispatchers.Default) {
        require(texts.size in 1..SemanticMemoryPolicy.MAX_BATCH &&
            texts.all { it.isNotBlank() && it.length <= SemanticMemoryPolicy.MAX_TEXT }) {
            "Embedding input batch exceeds limits"
        }
        require(identity == inspectModel(MODEL_ID)) { "Embedding model identity mismatch" }
        val n = requireNotNull(embeddingDim)
        val active = getEngine()
        // Explicitly L2-normalized outputs; same task options for query and
        // documents. No prefix/task modes are assumed without model-card proof.
        val opts = EmbeddingOptions(normalize = true)
        texts.map { value ->
            val native = active.computeEmbedding(listOf(InputData.Text(value)), opts).embedding
            require(native.size == n) {
                "Embedding dimension differs from the model's declared output dimension"
            }
            SemanticMemoryPolicy.normalized(native.map(Float::toDouble))
        }
    }

    /** Probe MUST execute one real embedding, not just inspect metadata. */
    suspend fun smoke(): Pair<LocalEmbeddingIdentity, Int> {
        val model = inspectModel(MODEL_ID)
        val vector = embed(model, listOf(SMOKE_INPUT)).single()
        return model to vector.size
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            val active = engine
            engine = null
            if (active != null && active.isInitialized()) active.close()
        }
    }
}
