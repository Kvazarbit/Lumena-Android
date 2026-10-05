package com.lumena.android.settings

import kotlin.math.sqrt

/**
 * One finished task as an evaluated trace on the fractal canvas.
 *
 * Unlike tool-level examples, this records the outcome of the whole task
 * after the Goal Contract gates, which model produced it, what it cost and
 * which advisory layers were shown. No prompt, advice text or tool output.
 */
data class FractalTaskEpisode(
    val id: String,
    val modelId: String,
    val family: String,
    val status: String,
    val success: Boolean,
    val toolSteps: Int = 0,
    val modelCalls: Int = 0,
    val tokens: Long = 0,
    val durationMs: Long = 0,
    val exposedLayers: List<String> = emptyList(),
    val withheldLayers: List<String> = emptyList(),
    val at: Long
)

enum class FractalTrailLevel {
    /** One family for one model. */
    PATTERN,
    /** One family across all models. */
    STRATEGY,
    /** Everything. */
    META_RULE
}

/**
 * Aggregated, observational view of a slice of the trail.
 *
 * Peaks compare a slice with its parent and are labelled observational:
 * users choose the model and the task, so a better rate here is a
 * difference worth testing, not proof that the model or memory caused it.
 * Causal claims come only from the Layer Governor.
 */
data class FractalTrailSummary(
    val level: FractalTrailLevel,
    val family: String?,
    val modelId: String?,
    val tasks: Int,
    val resolved: Int,
    val successRate: Double,
    val low: Double,
    val high: Double,
    val meanTokens: Double?,
    val meanSteps: Double?,
    val peak: FractalExperiencePeak
)

object FractalTaskTrailPolicy {
    const val MAX_EPISODES = 1_024
    const val MIN_PEAK_TASKS = 10
    private const val Z = 1.96

    fun record(
        state: FractalExperienceCanvasState,
        episode: FractalTaskEpisode
    ): FractalExperienceCanvasState {
        if (state.taskEpisodes.any { it.id == episode.id }) return state
        return state.copy(
            taskEpisodes = (state.taskEpisodes + episode)
                .sortedBy { it.at }
                .takeLast(MAX_EPISODES)
        )
    }

    fun summaries(state: FractalExperienceCanvasState): List<FractalTrailSummary> {
        val episodes = state.taskEpisodes
        if (episodes.isEmpty()) return emptyList()
        val meta = summarize(FractalTrailLevel.META_RULE, null, null, episodes, parent = null)
        val out = mutableListOf(meta)
        episodes.groupBy { it.family }.toSortedMap().forEach { (family, inFamily) ->
            val strategy = summarize(FractalTrailLevel.STRATEGY, family, null, inFamily, parent = meta)
            out += strategy
            inFamily.groupBy { it.modelId }.toSortedMap().forEach { (model, slice) ->
                out += summarize(FractalTrailLevel.PATTERN, family, model, slice, parent = strategy)
            }
        }
        return out
    }

    private fun summarize(
        level: FractalTrailLevel,
        family: String?,
        modelId: String?,
        episodes: List<FractalTaskEpisode>,
        parent: FractalTrailSummary?
    ): FractalTrailSummary {
        val resolved = episodes.filter { it.status != "UNRESOLVED" }
        val n = resolved.size
        val successes = resolved.count { it.success }
        val (low, high) = wilson(successes, n)
        val rate = if (n == 0) 0.0 else successes.toDouble() / n
        val tokens = resolved.filter { it.tokens > 0 }.map { it.tokens.toDouble() }
        val steps = resolved.filter { it.modelCalls > 0 || it.toolSteps > 0 }.map { it.toolSteps.toDouble() }
        val peak = when {
            n < MIN_PEAK_TASKS || parent == null -> FractalExperiencePeak.UNKNOWN
            low > parent.successRate -> FractalExperiencePeak.BEST
            high < parent.successRate -> FractalExperiencePeak.WORST
            else -> FractalExperiencePeak.UNKNOWN
        }
        return FractalTrailSummary(
            level = level,
            family = family,
            modelId = modelId,
            tasks = episodes.size,
            resolved = n,
            successRate = rate,
            low = low,
            high = high,
            meanTokens = tokens.takeIf { it.isNotEmpty() }?.average(),
            meanSteps = steps.takeIf { it.isNotEmpty() }?.average(),
            peak = peak
        )
    }

    /** Wilson score interval; [0,1] when there is no data. */
    fun wilson(successes: Int, n: Int): Pair<Double, Double> {
        if (n <= 0) return 0.0 to 1.0
        val p = successes.toDouble() / n
        val z2 = Z * Z
        val centre = (p + z2 / (2 * n)) / (1 + z2 / n)
        val margin = Z * sqrt((p * (1 - p) + z2 / (4 * n)) / n) / (1 + z2 / n)
        return (centre - margin).coerceAtLeast(0.0) to (centre + margin).coerceAtMost(1.0)
    }
}
