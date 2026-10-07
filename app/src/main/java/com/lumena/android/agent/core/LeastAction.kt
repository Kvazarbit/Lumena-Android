package com.lumena.android.agent.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Least-action ledger: the data source for the U and E terms of
 *
 *     J(a) = α·C(a) + β·R(a) + γ·U(a) + δ·E(a) − λ·G(a)
 *
 * Before every tool call the model may state what it expects
 * (expect_ok + confidence). After the call the application records what
 * really happened. Only these pairs make U and E measurable; until they are
 * calibrated, J is not computed at all ("waiting for U and E").
 *
 * Label honesty: the label is the tool's own ok flag (did the action run
 * successfully), not semantic task success. Unknown outcomes are never
 * recorded. J is computed in SHADOW only: it is logged next to the model's
 * actual choice and never selects, blocks or reorders tools.
 */
data class LeastActionOutcome(
    val modelId: String,
    val tool: String,
    val ok: Boolean,
    val elapsedMs: Long,
    /** Model's stated probability that the call succeeds; null if it gave none. */
    val predictedOk: Double? = null,
    val at: Long = 0
)

data class LeastActionShadow(
    val modelId: String,
    val chosenTool: String,
    val chosenJ: Double,
    val bestTool: String,
    val bestJ: Double,
    val candidates: Int,
    val ok: Boolean,
    val at: Long = 0
) {
    fun agreed(): Boolean = chosenTool == bestTool
}

data class LeastActionState(
    val version: Int = 1,
    val outcomes: List<LeastActionOutcome> = emptyList(),
    val shadows: List<LeastActionShadow> = emptyList()
)

enum class LeastActionStatus {
    /** Not enough stated predictions yet: U and E do not exist. */
    WAITING_FOR_DATA,

    /** Enough data, but predictions carry no out-of-sample skill. */
    NOT_INFORMATIVE,

    /** Calibrated out of sample: U and E are real, J can be computed. */
    READY
}

data class LeastActionReadiness(
    val status: LeastActionStatus,
    val predictions: Int,
    val needed: Int,
    /** Out-of-sample Brier skill vs base rate after calibration. */
    val skill: Double? = null,
    /** Out-of-sample expected calibration error. */
    val ece: Double? = null,
    val brier: Double? = null
)

data class LeastActionTerms(
    val tool: String,
    val cost: Double,
    val risk: Double,
    val uncertainty: Double,
    val predictionError: Double,
    val gain: Double
) {
    val j: Double
        get() = LeastActionPolicy.ALPHA * cost +
            LeastActionPolicy.BETA * risk +
            LeastActionPolicy.GAMMA * uncertainty +
            LeastActionPolicy.DELTA * predictionError -
            LeastActionPolicy.LAMBDA * gain
}

data class LeastActionReport(
    val modelId: String,
    val readiness: LeastActionReadiness,
    val outcomes: Int,
    val shadows: Int,
    val agreed: Int,
    val agreedOk: Int,
    val disagreedOk: Int
)

object LeastActionPolicy {
    const val MAX_OUTCOMES = 2_048
    const val MAX_SHADOWS = 512

    /** Stated predictions needed before calibration is even attempted. */
    const val MIN_PREDICTIONS = 60

    /** Per-tool outcomes needed before U/C/G of that tool are known. */
    const val MIN_TOOL_OUTCOMES = 8
    const val MIN_SKILL = 0.05
    const val MAX_ECE = 0.15
    const val BINS = 10
    const val MIN_BIN = 5
    const val LATENCY_SCALE_MS = 30_000.0

    // Weights are NOT fitted: every term is normalised to [0, 1] and weighted
    // equally until paired A/B data justifies anything else.
    const val ALPHA = 1.0
    const val BETA = 1.0
    const val GAMMA = 1.0
    const val DELTA = 1.0
    const val LAMBDA = 1.0

    fun record(state: LeastActionState, outcome: LeastActionOutcome): LeastActionState {
        val predicted = outcome.predictedOk?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val clean = outcome.copy(
            tool = ToolRegistry.canonicalize(outcome.tool),
            elapsedMs = outcome.elapsedMs.coerceAtLeast(0),
            predictedOk = predicted
        )
        return state.copy(outcomes = (state.outcomes + clean).takeLast(MAX_OUTCOMES))
    }

    fun recordShadow(state: LeastActionState, shadow: LeastActionShadow): LeastActionState =
        state.copy(shadows = (state.shadows + shadow).takeLast(MAX_SHADOWS))

    /** Converts the model's expect_ok + confidence into P(ok). */
    fun predictedOk(expectOk: Boolean?, confidence: Double?): Double? {
        if (expectOk == null || confidence == null || !confidence.isFinite()) return null
        val c = confidence.coerceIn(0.0, 1.0)
        return if (expectOk) c else 1.0 - c
    }

    private fun predictions(state: LeastActionState, modelId: String) =
        state.outcomes.filter { it.modelId == modelId && it.predictedOk != null }

    /**
     * Readiness gate. Calibration bins are fitted on the older 2/3 of the
     * stated predictions and scored on the newer 1/3, so a model cannot look
     * calibrated just by being evaluated on the data it was fitted to.
     */
    fun readiness(state: LeastActionState, modelId: String): LeastActionReadiness {
        val samples = predictions(state, modelId)
        if (samples.size < MIN_PREDICTIONS) {
            return LeastActionReadiness(LeastActionStatus.WAITING_FOR_DATA, samples.size, MIN_PREDICTIONS)
        }
        val split = samples.size * 2 / 3
        val fit = samples.take(split)
        val test = samples.drop(split)
        val baseRate = fit.count { it.ok }.toDouble() / fit.size
        val map = calibrationMap(fit)
        val calibrated = test.map { calibrate(map, it.predictedOk!!, baseRate) to it.ok }
        val brier = calibrated.map { (p, ok) -> sq(p - y(ok)) }.average()
        val climatology = test.map { sq(baseRate - y(it.ok)) }.average()
        val skill = if (climatology <= 1e-9) null else 1.0 - brier / climatology
        val ece = expectedCalibrationError(calibrated)
        val ready = skill != null && skill >= MIN_SKILL && ece <= MAX_ECE
        return LeastActionReadiness(
            status = if (ready) LeastActionStatus.READY else LeastActionStatus.NOT_INFORMATIVE,
            predictions = samples.size,
            needed = MIN_PREDICTIONS,
            skill = skill,
            ece = ece,
            brier = brier
        )
    }

    /**
     * J terms for one tool, or null while any term is still unknown.
     * Returns null for every tool until readiness is READY.
     */
    fun terms(
        state: LeastActionState,
        modelId: String,
        tool: String,
        task: TaskState,
        readiness: LeastActionReadiness = readiness(state, modelId)
    ): LeastActionTerms? {
        if (readiness.status != LeastActionStatus.READY) return null
        val canonical = ToolRegistry.canonicalize(tool)
        val spec = ToolRegistry.get(canonical) ?: return null
        val history = state.outcomes.filter { it.modelId == modelId && it.tool == canonical }
        if (history.size < MIN_TOOL_OUTCOMES) return null

        val successRate = (history.count { it.ok } + 1.0) / (history.size + 2.0)
        val latency = history.map { it.elapsedMs }.sorted()[history.size / 2]
        val cost = min(1.0, latency / LATENCY_SCALE_MS)
        val risk = when (spec.risk) {
            ToolRisk.READ_ONLY -> 0.0
            ToolRisk.MUTATING -> 0.5
            ToolRisk.EXECUTABLE -> 1.0
        }
        val uncertainty = binaryEntropy(successRate)
        val stated = history.filter { it.predictedOk != null }
        val errorSource = if (stated.size >= MIN_TOOL_OUTCOMES) stated else predictions(state, modelId)
        val predictionError = errorSource.map { sq(it.predictedOk!! - y(it.ok)) }.average()
        val gain = successRate * relevance(canonical, task)
        return LeastActionTerms(canonical, cost, risk, uncertainty, predictionError, gain)
    }

    /**
     * Shadow comparison of the model's actual choice with the J-minimal tool
     * among those the current task policy would allow. Null while waiting.
     */
    fun shadow(
        state: LeastActionState,
        modelId: String,
        chosenTool: String,
        task: TaskState,
        ok: Boolean,
        now: Long
    ): LeastActionShadow? {
        val readiness = readiness(state, modelId)
        if (readiness.status != LeastActionStatus.READY) return null
        val chosen = terms(state, modelId, chosenTool, task, readiness) ?: return null
        val candidates = state.outcomes
            .asSequence()
            .filter { it.modelId == modelId }
            .map { it.tool }
            .distinct()
            .filter { policyPermits(task.effectivePolicy, it) }
            .mapNotNull { terms(state, modelId, it, task, readiness) }
            .toList()
            .ifEmpty { listOf(chosen) }
        val best = candidates.minWith(compareBy<LeastActionTerms> { it.j }.thenBy { it.tool })
        return LeastActionShadow(
            modelId = modelId,
            chosenTool = chosen.tool,
            chosenJ = chosen.j,
            bestTool = best.tool,
            bestJ = best.j,
            candidates = candidates.size,
            ok = ok,
            at = now
        )
    }

    fun report(state: LeastActionState, modelId: String): LeastActionReport {
        val shadows = state.shadows.filter { it.modelId == modelId }
        return LeastActionReport(
            modelId = modelId,
            readiness = readiness(state, modelId),
            outcomes = state.outcomes.count { it.modelId == modelId },
            shadows = shadows.size,
            agreed = shadows.count { it.agreed() },
            agreedOk = shadows.count { it.agreed() && it.ok },
            disagreedOk = shadows.count { !it.agreed() && it.ok }
        )
    }

    /** 1 when the tool supplies evidence for an open goal criterion or required tool. */
    internal fun relevance(tool: String, task: TaskState): Double {
        val open = GoalContractPolicy.incompleteMandatory(task.goalContract)
        val namedByCriterion = open.any { criterion ->
            criterion.subject.split('|', ',').map { it.trim() }.any { it == tool }
        }
        if (namedByCriterion || tool in task.effectivePolicy.requiredTools) return 1.0
        val observes = ToolCapability.READ_STATE in ToolRegistry.capabilities(tool) &&
            ToolCapability.WRITE_WORKSPACE !in ToolRegistry.capabilities(tool)
        return if (open.isNotEmpty() && observes) 0.5 else 0.0
    }

    internal fun policyPermits(policy: EffectiveTaskPolicy, tool: String): Boolean {
        val spec = ToolRegistry.get(tool) ?: return false
        if (tool in policy.forbiddenTools) return false
        if (spec.risk !in policy.allowedRisks) return false
        return ToolRegistry.capabilities(tool).none { it in policy.deniedCapabilities }
    }

    private fun calibrationMap(fit: List<LeastActionOutcome>): Map<Int, Pair<Int, Int>> =
        fit.groupBy { bin(it.predictedOk!!) }
            .mapValues { (_, items) -> items.count { it.ok } to items.size }

    private fun calibrate(map: Map<Int, Pair<Int, Int>>, p: Double, baseRate: Double): Double {
        val (hits, count) = map[bin(p)] ?: return blend(p, baseRate)
        if (count < MIN_BIN) return blend(p, baseRate)
        return (hits + 1.0) / (count + 2.0)
    }

    private fun blend(p: Double, baseRate: Double) = (p + baseRate) / 2.0

    private fun expectedCalibrationError(pairs: List<Pair<Double, Boolean>>): Double {
        if (pairs.isEmpty()) return 1.0
        return pairs.groupBy { bin(it.first) }.values.sumOf { group ->
            val meanP = group.map { it.first }.average()
            val freq = group.count { it.second }.toDouble() / group.size
            abs(meanP - freq) * group.size
        } / pairs.size
    }

    private fun bin(p: Double) = min(BINS - 1, max(0, (p * BINS).toInt()))

    internal fun binaryEntropy(p: Double): Double {
        if (p <= 0.0 || p >= 1.0) return 0.0
        return -(p * ln(p) + (1 - p) * ln(1 - p)) / ln(2.0)
    }

    private fun y(ok: Boolean) = if (ok) 1.0 else 0.0
    private fun sq(x: Double) = x * x
}
