package com.lumena.android.agent.core

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Advisory layers that may be withheld in an experiment.
 *
 * Hard invariants, ToolRegistry/ToolGate, user constraints, Core DNA,
 * verification gates and the Goal Contract are deliberately NOT listed:
 * they are never part of an experiment and can never be disabled here.
 */
enum class AdvisoryLayer(val key: String) {
    LANDSCAPE("landscape"),
    MEMORY("memory"),
    COORDINATOR("coordinator"),
    FRACTAL("fractal"),
    EVIDENCE("evidence"),
    CONSTITUTION("constitution"),
    REFLEX("reflex");

    companion object {
        fun fromKey(key: String): AdvisoryLayer? = entries.firstOrNull { it.key == key }
    }
}

/** One eligible layer in one task: was its advice shown to the model? */
data class GovernorExposure(
    val layer: String,
    val exposed: Boolean,
    /** True when the arm was not randomized (layer disabled); excluded from estimates. */
    val forced: Boolean = false
)

data class GovernorPending(
    val taskHash: String,
    val modelId: String,
    val family: String,
    val exposures: List<GovernorExposure>,
    val startedAt: Long
)

/** Full configuration vector x and outcome f(x) of one finished task. */
data class GovernorTrial(
    val taskHash: String,
    val modelId: String,
    val family: String,
    val exposures: List<GovernorExposure>,
    val status: String,
    val success: Boolean,
    val at: Long
)

data class LayerGovernorState(
    val version: Int = 1,
    val pending: List<GovernorPending> = emptyList(),
    val trials: List<GovernorTrial> = emptyList(),
    /** "modelId|layer" -> time the layer was disabled for that model. */
    val disabled: Map<String, Long> = emptyMap()
)

enum class LayerVerdict {
    /** Lower 95% bound of the effect is above zero. */
    KEEP,
    /** Upper 95% bound is below zero with enough data: the layer hurts. */
    DISABLE,
    /** Both arms are large and the whole interval lies inside +-NEGLIGIBLE. */
    NEGLIGIBLE,
    /** The layer almost never had advice to give. */
    NOT_TRIGGERED,
    /** Not enough randomized tasks, or the interval still crosses zero. */
    INSUFFICIENT_DATA
}

data class LayerEffect(
    val layer: String,
    val exposedTasks: Int,
    val withheldTasks: Int,
    val exposedSuccess: Int,
    val withheldSuccess: Int,
    val delta: Double,
    val low: Double,
    val high: Double,
    val eligibleShare: Double,
    val verdict: LayerVerdict,
    /** Tasks that never reached a terminal status, per arm. */
    val exposedUnresolved: Int = 0,
    val withheldUnresolved: Int = 0,
    /** Arms lose tasks at clearly different rates; estimates are not trusted. */
    val attritionImbalance: Boolean = false
)

data class LayerInteraction(
    val first: String,
    val second: String,
    val tasks: Int,
    val estimate: Double,
    val low: Double,
    val high: Double
) {
    /** Both layers together do worse than their separate effects predict. */
    val conflict: Boolean get() = high < 0.0
}

data class LayerGovernorReport(
    val modelId: String,
    val trials: Int,
    /** Trials used for verdicts: the last completed checkpoint. */
    val checkpointTrials: Int = 0,
    val effects: List<LayerEffect>,
    val interactions: List<LayerInteraction>,
    val disabled: List<String>
)

/**
 * Layer Governor: a factorial ON/OFF experiment over advisory layers.
 *
 * Every eligible layer gets an independent arm per task (stable hash of
 * task id and layer key, known propensity). Each finished task is a sample
 * of f(x), where x is the vector of exposed layers. In the Walsh-Fourier
 * expansion f(x) = sum_S f^(S) chi_S(x), the first-order coefficient of a
 * layer is its main effect (half the EXPOSED-WITHHELD difference) and the
 * second-order coefficient of a pair is its interaction. With unequal arm
 * sizes the Walsh basis is not orthogonal, so main effects are estimated as
 * stratified differences of adjusted proportions instead, which is the
 * equivalent quantity.
 *
 * Correlation with success is not used anywhere: only randomized arms are.
 * The outcome label (terminal task status after Goal Contract gates) does
 * not depend on the layer being judged.
 */
object LayerGovernorPolicy {
    const val DEFAULT_WITHHELD_PERCENT = 20
    const val MIN_ARM_TASKS = 30
    const val MIN_HARM_ARM_TASKS = 50
    const val MIN_INTERACTION_CELL = 10
    const val NEGLIGIBLE = 0.05
    const val NOT_TRIGGERED_SHARE = 0.05
    const val NOT_TRIGGERED_MIN_TRIALS = 200
    const val MAX_TRIALS = 4_000
    const val MAX_PENDING = 64
    /** Pending tasks older than this are closed as UNRESOLVED. */
    const val PENDING_MAX_AGE_MS = 6L * 60 * 60 * 1000
    const val UNRESOLVED = "UNRESOLVED"
    /** Verdicts are computed only at multiples of this many finished tasks. */
    const val CHECKPOINT_TRIALS = 100
    /** Unresolved-rate gap between arms above which estimates are not trusted. */
    const val MAX_ATTRITION_GAP = 0.10
    /** Bonferroni over 7 layers at 5% two-sided. */
    const val Z_LAYER = 2.69
    /** Bonferroni over 21 layer pairs at 5% two-sided. */
    const val Z_PAIR = 3.04

    fun taskHash(taskId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("governor|" + taskId.trim()).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)

    fun disabledKey(modelId: String, layer: String): String = "$modelId|$layer"

    /** Arm for one eligible layer; a disabled layer is forced off. */
    fun decide(
        state: LayerGovernorState,
        taskId: String,
        modelId: String,
        layer: AdvisoryLayer,
        withheldPercent: Int = DEFAULT_WITHHELD_PERCENT
    ): GovernorExposure {
        if (disabledKey(modelId, layer.key) in state.disabled) {
            return GovernorExposure(layer.key, exposed = false, forced = true)
        }
        val arm = AdvisoryHoldout.arm(taskId, layer.key, withheldPercent)
        return GovernorExposure(layer.key, exposed = arm == AdvisoryArm.EXPOSED)
    }

    /** Adds the layer to the task's configuration vector (first decision wins). */
    fun begin(
        state: LayerGovernorState,
        taskId: String,
        modelId: String,
        family: String,
        exposure: GovernorExposure,
        now: Long
    ): LayerGovernorState {
        val hash = taskHash(taskId)
        val stale = state.pending.filter {
            it.taskHash != hash && now - it.startedAt > PENDING_MAX_AGE_MS
        }
        if (stale.isNotEmpty()) {
            return begin(closeUnresolved(state, stale, now), taskId, modelId, family, exposure, now)
        }
        val existing = state.pending.firstOrNull { it.taskHash == hash }
        if (existing != null) {
            if (existing.exposures.any { it.layer == exposure.layer }) return state
            val updated = existing.copy(exposures = existing.exposures + exposure)
            return state.copy(pending = state.pending.map { if (it.taskHash == hash) updated else it })
        }
        val pending = GovernorPending(hash, modelId, family, listOf(exposure), now)
        val all = state.pending + pending
        val overflow = all.dropLast(MAX_PENDING)
        return closeUnresolved(state.copy(pending = all), overflow, now)
    }

    /** Closes a task that will never get a terminal status (cancelled, abandoned). */
    fun abandon(
        state: LayerGovernorState,
        taskId: String,
        now: Long
    ): LayerGovernorState {
        val hash = taskHash(taskId)
        return closeUnresolved(state, state.pending.filter { it.taskHash == hash }, now)
    }

    /**
     * Tasks that were cancelled, abandoned or evicted are kept as UNRESOLVED
     * trials: excluded from success estimates but counted per arm, so an arm
     * that makes users give up cannot look better by losing its failures.
     */
    private fun closeUnresolved(
        state: LayerGovernorState,
        closing: List<GovernorPending>,
        now: Long
    ): LayerGovernorState {
        if (closing.isEmpty()) return state
        val hashes = closing.map { it.taskHash }.toSet()
        val trials = closing.map {
            GovernorTrial(it.taskHash, it.modelId, it.family, it.exposures, UNRESOLVED, false, now)
        }
        return state.copy(
            pending = state.pending.filterNot { it.taskHash in hashes },
            trials = (state.trials + trials).takeLast(MAX_TRIALS)
        )
    }

    /** Moves a task with a terminal status into the trial set. */
    fun resolve(
        state: LayerGovernorState,
        taskId: String,
        status: String,
        now: Long
    ): LayerGovernorState {
        val hash = taskHash(taskId)
        val pending = state.pending.firstOrNull { it.taskHash == hash } ?: return state
        val trial = GovernorTrial(
            taskHash = hash,
            modelId = pending.modelId,
            family = pending.family,
            exposures = pending.exposures,
            status = status,
            success = status == "DONE",
            at = now
        )
        return state.copy(
            pending = state.pending.filterNot { it.taskHash == hash },
            trials = (state.trials + trial).takeLast(MAX_TRIALS)
        )
    }

    /**
     * Verdicts are evaluated only at fixed checkpoints with a Bonferroni
     * correction over layers, and DISABLE must hold at two consecutive
     * checkpoints. Re-testing after every task would turn noise into
     * significant-looking harm (optional stopping).
     */
    fun report(state: LayerGovernorState, modelId: String): LayerGovernorReport {
        val trials = state.trials.filter { it.modelId == modelId }
        val checkpoint = (trials.size / CHECKPOINT_TRIALS) * CHECKPOINT_TRIALS
        val current = trials.take(checkpoint)
        val previous = trials.take((checkpoint - CHECKPOINT_TRIALS).coerceAtLeast(0))
        val effects = AdvisoryLayer.entries.map { layer ->
            val atCheckpoint = effect(current, layer.key)
            if (atCheckpoint.verdict != LayerVerdict.DISABLE) {
                atCheckpoint
            } else if (effect(previous, layer.key).verdict == LayerVerdict.DISABLE) {
                atCheckpoint
            } else {
                atCheckpoint.copy(verdict = LayerVerdict.INSUFFICIENT_DATA)
            }
        }
        return LayerGovernorReport(
            modelId = modelId,
            trials = trials.size,
            checkpointTrials = checkpoint,
            effects = effects,
            interactions = interactions(current),
            disabled = state.disabled.keys
                .filter { it.startsWith("$modelId|") }
                .map { it.removePrefix("$modelId|") }
                .sorted()
        )
    }

    /** Disables layers whose randomized effect is clearly negative. Never re-enables. */
    fun applyVerdicts(
        state: LayerGovernorState,
        modelId: String,
        now: Long
    ): LayerGovernorState {
        val harmful = report(state, modelId).effects
            .filter { it.verdict == LayerVerdict.DISABLE }
            .map { disabledKey(modelId, it.layer) }
            .filterNot { it in state.disabled }
        if (harmful.isEmpty()) return state
        return state.copy(disabled = state.disabled + harmful.associateWith { now })
    }

    /** Owner action: start a fresh experiment for one layer of one model. */
    fun reset(state: LayerGovernorState, modelId: String, layer: String): LayerGovernorState =
        state.copy(
            disabled = state.disabled - disabledKey(modelId, layer),
            trials = state.trials.map { trial ->
                if (trial.modelId != modelId) trial
                else trial.copy(exposures = trial.exposures.filterNot { it.layer == layer })
            }
        )

    fun effect(
        trials: List<GovernorTrial>,
        layer: String,
        z: Double = Z_LAYER
    ): LayerEffect {
        val eligible = trials.filter { t -> t.exposures.any { it.layer == layer } }
        val randomizedAll = eligible.filter { t -> t.exposures.first { it.layer == layer }.forced.not() }
        fun exposedOf(t: GovernorTrial) = t.exposures.first { it.layer == layer }.exposed
        val totalOn = randomizedAll.count { exposedOf(it) }
        val totalOff = randomizedAll.size - totalOn
        val unresolvedOn = randomizedAll.count { exposedOf(it) && it.status == UNRESOLVED }
        val unresolvedOff = randomizedAll.count { !exposedOf(it) && it.status == UNRESOLVED }
        val attritionImbalance =
            totalOn >= MIN_ARM_TASKS && totalOff >= MIN_ARM_TASKS &&
                abs(unresolvedOn.toDouble() / totalOn - unresolvedOff.toDouble() / totalOff) >
                MAX_ATTRITION_GAP
        val randomized = randomizedAll.filter { it.status != UNRESOLVED }

        var weightSum = 0.0
        var weightedDelta = 0.0
        var weightedVariance = 0.0
        randomized.groupBy { it.family }.values.forEach { stratum ->
            val on = stratum.filter(::exposedOf)
            val off = stratum.filterNot(::exposedOf)
            if (on.isEmpty() || off.isEmpty()) return@forEach
            // Agresti-Caffo adjusted proportions keep small strata honest.
            val p1 = (on.count { it.success } + 1.0) / (on.size + 2.0)
            val p0 = (off.count { it.success } + 1.0) / (off.size + 2.0)
            val w = on.size.toDouble() * off.size / (on.size + off.size)
            weightSum += w
            weightedDelta += w * (p1 - p0)
            weightedVariance += w * w * (p1 * (1 - p1) / (on.size + 2.0) + p0 * (1 - p0) / (off.size + 2.0))
        }

        val exposed = randomized.filter(::exposedOf)
        val withheld = randomized.filterNot(::exposedOf)
        val delta = if (weightSum > 0) weightedDelta / weightSum else 0.0
        val se = if (weightSum > 0) sqrt(weightedVariance) / weightSum else 1.0
        val low = if (weightSum > 0) delta - z * se else -1.0
        val high = if (weightSum > 0) delta + z * se else 1.0
        val share = if (trials.isEmpty()) 0.0 else eligible.size.toDouble() / trials.size
        val minArm = minOf(exposed.size, withheld.size)

        val verdict = when {
            trials.size >= NOT_TRIGGERED_MIN_TRIALS && share < NOT_TRIGGERED_SHARE ->
                LayerVerdict.NOT_TRIGGERED
            minArm < MIN_ARM_TASKS || weightSum == 0.0 || attritionImbalance ->
                LayerVerdict.INSUFFICIENT_DATA
            low > 0.0 ->
                LayerVerdict.KEEP
            high < 0.0 && minArm >= MIN_HARM_ARM_TASKS ->
                LayerVerdict.DISABLE
            low > -NEGLIGIBLE && high < NEGLIGIBLE ->
                LayerVerdict.NEGLIGIBLE
            else ->
                LayerVerdict.INSUFFICIENT_DATA
        }

        return LayerEffect(
            layer = layer,
            exposedTasks = exposed.size,
            withheldTasks = withheld.size,
            exposedSuccess = exposed.count { it.success },
            withheldSuccess = withheld.count { it.success },
            delta = delta,
            low = low,
            high = high,
            eligibleShare = share,
            verdict = verdict,
            exposedUnresolved = unresolvedOn,
            withheldUnresolved = unresolvedOff,
            attritionImbalance = attritionImbalance
        )
    }

    /**
     * Second-order Walsh coefficients for pairs of layers that were both
     * randomized in the same tasks: (p11 - p10) - (p01 - p00).
     */
    fun interactions(trials: List<GovernorTrial>): List<LayerInteraction> {
        val keys = AdvisoryLayer.entries.map { it.key }
        val out = mutableListOf<LayerInteraction>()
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val a = keys[i]
            val b = keys[j]
            val both = trials.mapNotNull { t ->
                if (t.status == UNRESOLVED) return@mapNotNull null
                val ea = t.exposures.firstOrNull { it.layer == a && !it.forced } ?: return@mapNotNull null
                val eb = t.exposures.firstOrNull { it.layer == b && !it.forced } ?: return@mapNotNull null
                Triple(ea.exposed, eb.exposed, t.success)
            }
            val cells = listOf(true to true, true to false, false to true, false to false).map { (xa, xb) ->
                both.filter { it.first == xa && it.second == xb }
            }
            if (cells.any { it.size < MIN_INTERACTION_CELL }) continue
            val p = cells.map { c -> (c.count { it.third } + 1.0) / (c.size + 2.0) }
            val variance = cells.indices.sumOf { k -> p[k] * (1 - p[k]) / (cells[k].size + 2.0) }
            val estimate = (p[0] - p[1]) - (p[2] - p[3])
            val se = sqrt(variance)
            out += LayerInteraction(a, b, both.size, estimate, estimate - Z_PAIR * se, estimate + Z_PAIR * se)
        }
        return out.sortedByDescending { abs(it.estimate) }
    }
}
