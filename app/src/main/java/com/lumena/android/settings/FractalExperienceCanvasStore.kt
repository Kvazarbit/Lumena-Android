package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

data class FractalExperienceCanvasStats(
    val records: Int,
    val nodes: Int,
    val best: Int,
    val worst: Int,
    val unknown: Int,
    val contested: Int,
    val transferredShadow: Int,
    val languageCues: Int,
    val languageTransferred: Int,
    val contributorModels: Int
)

object FractalExperienceCanvasCodec {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(FractalExperienceCanvasState::class.java)

    fun encode(state: FractalExperienceCanvasState): String =
        adapter.toJson(
            FractalExperienceCanvasPolicy.normalize(state)
        )

    fun decode(json: String): FractalExperienceCanvasState {
        val parsed = requireNotNull(adapter.fromJson(json)) {
            "Fractal experience canvas is empty."
        }
        require(parsed.version == 1) {
            "Unsupported fractal experience canvas version: ${parsed.version}"
        }
        require(parsed.records.size <= FractalExperienceCanvasPolicy.MAX_RECORDS)
        require(parsed.nodes.size <= FractalExperienceCanvasPolicy.MAX_NODES)
        require(
            parsed.languageObservations.size <=
                FractalExperienceCanvasPolicy.MAX_LANGUAGE_OBSERVATIONS
        )

        parsed.records.forEach { record ->
            require(record.id.length in 1..128)
            require(record.sourceTaskHash.length in 1..64)
            require(record.tools.isNotEmpty() && record.tools.size <= 16)
            require(record.targets.size <= 16)
            require(record.targets.all { it.length <= 240 })
            require(record.outcomes.size == record.tools.size)
            require(record.evidenceIds.size <= 64)
            require(record.contributorModelIds.size <= 16)
            require(record.updatedAt > 0)
            require(record.summary.length <= 900)
            require(
                record.tools.all {
                    com.lumena.android.agent.core.ToolRegistry.get(it) != null
                }
            )
        }

        parsed.nodes.forEach { node ->
            require(node.id.length in 1..128)
            require(node.key.length in 1..900)
            require(node.summary.length <= 900)
            require(node.supportCount >= 0)
            require(node.failureCount >= 0)
            require(node.distinctTasks >= 0)
            require(node.contributorModelIds.size <= 16)
            require(node.childIds.size <= 64)
            require(node.evidenceIds.size <= 64)
            require(node.counterexampleIds.size <= 64)
            require(node.updatedAt > 0)
            require(node.confidence in 0.0..1.0)
        }

        parsed.languageObservations.forEach { observation ->
            require(observation.id.length in 1..128)
            require(
                observation.phrase.length in
                    1..FractalExperienceCanvasPolicy.MAX_SHORT_CUE_CHARS
            )
            require("://" !in observation.phrase)
            require(observation.canonicalIntent.length in 1..80)
            require(observation.sourceTaskHash.length in 1..64)
            require(observation.at > 0)
        }

        // Nodes are a deterministic cache. Never trust a restored/imported
        // projection over its bounded source records.
        return FractalExperienceCanvasPolicy.normalize(
            parsed.copy(nodes = emptyList())
        )
    }
}

/**
 * App-private AtomicFile store for Fractal Experience Canvas v1.
 *
 * It stores only bounded derived coordinator records and short normalized
 * language observations. Raw tool stdout/stderr, user long-form messages,
 * bridge tokens and permissions are never stored here.
 */
object FractalExperienceCanvasStore {
    const val FILE_NAME = "lumena_fractal_experience_canvas_v1.json"
    private val lock = StateVaultLock.monitor

    fun load(context: Context): FractalExperienceCanvasState =
        synchronized(lock) {
            val file = atomicFile(context)
            if (
                !file.baseFile.exists() &&
                !File(file.baseFile.path + ".bak").exists()
            ) {
                return@synchronized FractalExperienceCanvasState()
            }

            val json = try {
                file.openRead()
                    .bufferedReader()
                    .use { it.readText() }
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Fractal experience canvas is unreadable; refusing to replace local experience.",
                    failure
                )
            }

            try {
                FractalExperienceCanvasCodec.decode(json)
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Fractal experience canvas is corrupt; refusing silent reset.",
                    failure
                )
            }
        }

    fun ingest(
        context: Context,
        examples: List<CoordinatorExecutionExample>
    ): FractalExperienceCanvasState =
        synchronized(lock) {
            val current = load(context)
            val next = FractalExperienceCanvasPolicy.ingest(
                current,
                examples
            )
            if (next != current) {
                save(context, next)
                StateVault.requestSave(context)
            }
            next
        }

    fun observeLanguage(
        context: Context,
        phrase: String,
        canonicalIntent: String,
        sourceTaskId: String,
        at: Long = System.currentTimeMillis()
    ): FractalExperienceCanvasState =
        synchronized(lock) {
            val current = load(context)
            val next = FractalExperienceCanvasPolicy.observeLanguage(
                state = current,
                phrase = phrase,
                canonicalIntent = canonicalIntent,
                sourceTaskId = sourceTaskId,
                at = at
            )
            if (next != current) {
                save(context, next)
                StateVault.requestSave(context)
            }
            next
        }

    fun relevant(
        context: Context,
        query: String,
        scopeId: String?,
        limit: Int = 6
    ): List<String> =
        synchronized(lock) {
            val scopeHash = scopeId
                ?.takeIf { it.isNotBlank() }
                ?.let(CoordinatorExperiencePolicy::hash)
            FractalExperienceCanvasPolicy.relevant(
                state = load(context),
                query = query,
                scopeHash = scopeHash,
                limit = limit
            )
                .map(FractalExperienceCanvasPolicy::formatForPrompt)
        }

    fun nodes(
        context: Context,
        scopeId: String? = null,
        limit: Int = 64
    ): List<FractalExperienceNode> =
        synchronized(lock) {
            val scopeHash = scopeId
                ?.takeIf { it.isNotBlank() }
                ?.let(CoordinatorExperiencePolicy::hash)
            load(context).nodes
                .asSequence()
                .filter {
                    scopeHash == null ||
                        it.scopeHash == scopeHash
                }
                .sortedWith(
                    compareByDescending<FractalExperienceNode> {
                        it.level.ordinal
                    }.thenByDescending { it.updatedAt }
                )
                .take(limit.coerceIn(1, 256))
                .toList()
        }

    fun languageCues(
        context: Context,
        limit: Int = 32
    ): List<FractalLanguageCue> =
        synchronized(lock) {
            FractalExperienceCanvasPolicy.languageCues(
                load(context),
                limit
            )
        }

    fun stats(context: Context): FractalExperienceCanvasStats =
        synchronized(lock) {
            val state = load(context)
            val cues =
                FractalExperienceCanvasPolicy.languageCues(
                    state,
                    limit = 128
                )
            val models = state.nodes
                .flatMap { it.contributorModelIds }
                .distinct()
            FractalExperienceCanvasStats(
                records = state.records.size,
                nodes = state.nodes.size,
                best = state.nodes.count {
                    it.peak == FractalExperiencePeak.BEST
                },
                worst = state.nodes.count {
                    it.peak == FractalExperiencePeak.WORST
                },
                unknown = state.nodes.count {
                    it.peak == FractalExperiencePeak.UNKNOWN
                },
                contested = state.nodes.count {
                    it.peak == FractalExperiencePeak.CONTESTED
                },
                transferredShadow = state.nodes.count {
                    it.stage ==
                        FractalExperienceStage.TRANSFERRED_SHADOW
                },
                languageCues = cues.size,
                languageTransferred = cues.count {
                    it.stage ==
                        FractalExperienceStage.TRANSFERRED_SHADOW
                },
                contributorModels = models.size
            )
        }

    fun clear(context: Context) =
        synchronized(lock) {
            val file = atomicFile(context).baseFile
            if (file.exists()) file.delete()
            File(file.path + ".bak")
                .takeIf(File::exists)
                ?.delete()
        }

    private fun save(
        context: Context,
        state: FractalExperienceCanvasState
    ) {
        val file = atomicFile(context)
        val output = file.startWrite()
        try {
            output.write(
                FractalExperienceCanvasCodec
                    .encode(state)
                    .toByteArray(Charsets.UTF_8)
            )
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun atomicFile(context: Context): AtomicFile =
        AtomicFile(
            File(
                context.applicationContext.filesDir,
                FILE_NAME
            )
        )
}
