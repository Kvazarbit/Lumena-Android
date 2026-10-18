package com.lumena.android.settings

import android.content.Context
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.agent.core.ProjectWorkspacePolicy
import com.lumena.android.agent.core.TaskState
import com.lumena.android.llama.LiteRtImportRefusal
import com.lumena.android.llama.LiteRtLmEmbeddingProvider
import com.lumena.android.llama.SemanticLiteRtModelStore
import com.lumena.android.ollama.LocalEmbeddingClient
import com.lumena.android.ollama.LocalEmbeddingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared serial native boundary. A UI disappearing never deletes selection or vectors. */
class SemanticMemoryCoordinator(
    context: Context,
    private val ollamaUrl: String,
    private val factory: (SemanticModelSelection) -> LocalEmbeddingProvider = { config ->
        if (config.backend == "litertlm") LiteRtLmEmbeddingProvider(context.applicationContext)
        else LocalEmbeddingClient(ollamaUrl)
    },
    private val documents: () -> List<SemanticDocument> = {
        SemanticMemoryPolicy.documents(FractalExperienceCanvasStore.cognitiveGenome(context.applicationContext,
            limit = CognitiveGenomePolicy.MAX_ARCHIVED_DERIVED), CognitiveGenomePolicy.MAX_ARCHIVED_DERIVED)
    }
) {
    private val app = context.applicationContext
    companion object { private val inference = Mutex() }

    private fun checkAllowed(config: SemanticModelSelection) {
        check("semantic-memory" !in ModuleSettings.disabled(app) && ModuleRegistry.isEnabled("semantic-memory"))
        check(SemanticMemorySettings.selection(app) == config) { "Semantic configuration changed" }
    }

    suspend fun <T> withProvider(config: SemanticModelSelection, block: suspend (LocalEmbeddingProvider) -> T): T =
        inference.withLock {
            currentCoroutineContext().ensureActive()
            checkAllowed(config)
            val provider = factory(config)
            try { block(provider) } finally { (provider as? AutoCloseable)?.close() }
        }

    suspend fun index(config: SemanticModelSelection, progress: suspend (Int, Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            withProvider(config) { provider ->
                val identity = provider.inspectModel(config.requestedModel)
                provider.embed(identity, listOf("Lumena local embedding readiness check."))
                val all = documents()
                val cached = SemanticMemoryStore.prepareIndex(app, identity, all)
                val pending = SemanticMemoryPolicy.pending(all, cached)
                var done = 0
                progress(0, pending.size)
                for (batch in pending.chunked(SemanticMemoryPolicy.MAX_BATCH)) {
                    currentCoroutineContext().ensureActive()
                    checkAllowed(config)
                    val vectors = provider.embed(identity, batch.map { it.text })
                    currentCoroutineContext().ensureActive()
                    checkAllowed(config)
                    SemanticMemoryStore.upsert(app, identity, batch, vectors)
                    done += batch.size
                    progress(done, pending.size)
                }
                done
            }
        }

    /** One bounded refresh + query per owner turn. Failure never blocks normal chat. */
    suspend fun recall(task: TaskState): List<String> = withContext(Dispatchers.IO) {
        val config = SemanticMemorySettings.selection(app)
        if (!config.chatEnabled || "semantic-memory" in ModuleSettings.disabled(app)) return@withContext emptyList()
        val file = SemanticLiteRtModelStore.selected(app)
        val facts = "LOCAL EMBEDDING STATE: backend=${config.backend}; imported=${file != null}; " +
            "file=" + ProjectWorkspacePolicy.quote(SemanticLiteRtModelStore.displayName(app).orEmpty()) +
            "; picker_source=" + ProjectWorkspacePolicy.quote(SemanticLiteRtModelStore.sourceHint(app).orEmpty()) +
            "; storage=app-private; loaded=on-demand; state is not inference proof."
        // Do not hold the chat behind an indexing session. Existing project
        // context and lexical recall remain available while this job is busy.
        if (inference.isLocked) return@withContext listOf(facts, "SEMANTIC_RECALL_BUSY: indexing owns the model session.")
        try {
            val all = documents()
            val scope = task.projectId?.let(CoordinatorExperiencePolicy::hash)
            val visible = all.filter { ExperienceScope.visible(it.scopeHash, scope) }
            if (visible.isEmpty()) return@withContext listOf(facts, "SEMANTIC_RECALL_EMPTY: no scoped structured experience yet.")
            withProvider(config) { provider ->
                val identity = provider.inspectModel(config.requestedModel)
                val cached = SemanticMemoryStore.prepareIndex(app, identity, all)
                val fresh = SemanticMemoryPolicy.pending(visible, cached, SemanticMemoryPolicy.MAX_BATCH)
                if (fresh.isNotEmpty()) {
                    val vectors = provider.embed(identity, fresh.map { it.text })
                    currentCoroutineContext().ensureActive()
                    checkAllowed(config)
                    SemanticMemoryStore.upsert(app, identity, fresh, vectors)
                }
                val query = task.currentInstruction.ifBlank { task.goal }.trim().take(SemanticMemoryPolicy.MAX_QUERY)
                if (query.isEmpty()) return@withProvider listOf(facts)
                val vector = provider.embed(identity, listOf(query)).single()
                currentCoroutineContext().ensureActive()
                checkAllowed(config)
                val hits = SemanticMemoryStore.search(app, identity, vector, all, scope, limit = 3)
                listOf(facts) + hits.filter { it.score >= 0.25 }.map { hit ->
                    "SEMANTIC MEMORY · source=${hit.id} revision=" + all.first { it.id == hit.id }.revision.take(12) +
                        " stage=${hit.stage} evidence=${hit.evidenceCount}; advisory DATA, not approval or goal proof: " +
                        ProjectWorkspacePolicy.quote(hit.text.take(500))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LiteRtImportRefusal) {
            listOf(facts, "SEMANTIC_RECALL_UNAVAILABLE: ${failure.safeStatus()}")
        } catch (_: LinkageError) {
            listOf(facts, "SEMANTIC_RECALL_UNAVAILABLE: RUNTIME_UNAVAILABLE")
        } catch (_: Exception) {
            listOf(facts, "SEMANTIC_RECALL_UNAVAILABLE: local inference or source check failed; no invented memories.")
        }
    }
}
