package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Simulated tasks with known ground truth: the governor must recover the
 * true effect of each advisory layer from randomized arms alone.
 */
class LayerGovernorPolicyTest {
    private val model = "ollama:gemma-test"

    private fun simulate(
        tasks: Int,
        layers: List<AdvisoryLayer>,
        base: Double,
        effects: Map<AdvisoryLayer, Double>,
        interaction: Pair<Pair<AdvisoryLayer, AdvisoryLayer>, Double>? = null,
        seed: Long = 7L,
        start: LayerGovernorState = LayerGovernorState(),
        idPrefix: String = "t"
    ): LayerGovernorState {
        val random = Random(seed)
        var state = start
        for (i in 0 until tasks) {
            val id = "$idPrefix$i"
            val shown = mutableSetOf<AdvisoryLayer>()
            layers.forEach { layer ->
                val exposure = LayerGovernorPolicy.decide(state, id, model, layer)
                state = LayerGovernorPolicy.begin(state, id, model, "CODE_WORK", exposure, i + 1L)
                if (exposure.exposed) shown += layer
            }
            var p = base + shown.sumOf { effects[it] ?: 0.0 }
            interaction?.let { (pair, value) ->
                if (pair.first in shown && pair.second in shown) p += value
            }
            val success = random.nextDouble() < p.coerceIn(0.0, 1.0)
            state = LayerGovernorPolicy.resolve(state, id, if (success) "DONE" else "PARTIAL", i + 1L)
        }
        return state
    }

    private fun effectOf(state: LayerGovernorState, layer: AdvisoryLayer) =
        LayerGovernorPolicy.report(state, model).effects.first { it.layer == layer.key }

    @Test fun helpfulLayerIsKept() {
        val state = simulate(600, listOf(AdvisoryLayer.FRACTAL), 0.40, mapOf(AdvisoryLayer.FRACTAL to 0.25))
        val effect = effectOf(state, AdvisoryLayer.FRACTAL)
        assertEquals(LayerVerdict.KEEP, effect.verdict)
        assertTrue("delta=${effect.delta}", effect.delta in 0.10..0.40)
        assertTrue(effect.withheldTasks in 80..160)
    }

    @Test fun harmfulLayerIsDisabledThenForcedOffAndExcluded() {
        var state = simulate(600, listOf(AdvisoryLayer.REFLEX), 0.60, mapOf(AdvisoryLayer.REFLEX to -0.25))
        val before = effectOf(state, AdvisoryLayer.REFLEX)
        assertEquals(LayerVerdict.DISABLE, before.verdict)

        state = LayerGovernorPolicy.applyVerdicts(state, model, 10_000)
        assertEquals(listOf("reflex"), LayerGovernorPolicy.report(state, model).disabled)

        val forced = LayerGovernorPolicy.decide(state, "after-disable", model, AdvisoryLayer.REFLEX)
        assertFalse(forced.exposed)
        assertTrue(forced.forced)

        // Forced-off tasks carry no randomized information and must not move the estimate.
        state = simulate(100, listOf(AdvisoryLayer.REFLEX), 0.60, emptyMap(), seed = 3, start = state, idPrefix = "late")
        val after = effectOf(state, AdvisoryLayer.REFLEX)
        assertEquals(before.exposedTasks, after.exposedTasks)
        assertEquals(before.withheldTasks, after.withheldTasks)

        // Disabling is for this model only.
        val other = LayerGovernorPolicy.decide(state, "x", "ollama:other-model", AdvisoryLayer.REFLEX)
        assertFalse(other.forced)
    }

    @Test fun nullLayerIsNeverKeptOrDisabled() {
        val small = simulate(80, listOf(AdvisoryLayer.EVIDENCE), 0.5, emptyMap())
        assertEquals(LayerVerdict.INSUFFICIENT_DATA, effectOf(small, AdvisoryLayer.EVIDENCE).verdict)

        val large = simulate(4_000, listOf(AdvisoryLayer.EVIDENCE), 0.5, emptyMap(), seed = 11)
        val effect = effectOf(large, AdvisoryLayer.EVIDENCE)
        assertTrue(effect.verdict in setOf(LayerVerdict.NEGLIGIBLE, LayerVerdict.INSUFFICIENT_DATA))
        assertTrue("ci=[${effect.low},${effect.high}]", effect.low < 0.0 && effect.high > 0.0)
    }

    @Test fun factorialDesignEstimatesSeveralLayersFromTheSameTasks() {
        val state = simulate(
            tasks = 1_500,
            layers = listOf(AdvisoryLayer.FRACTAL, AdvisoryLayer.COORDINATOR, AdvisoryLayer.MEMORY),
            base = 0.45,
            effects = mapOf(AdvisoryLayer.FRACTAL to 0.20, AdvisoryLayer.COORDINATOR to -0.20),
            seed = 21
        )
        assertEquals(LayerVerdict.KEEP, effectOf(state, AdvisoryLayer.FRACTAL).verdict)
        assertEquals(LayerVerdict.DISABLE, effectOf(state, AdvisoryLayer.COORDINATOR).verdict)
        val memory = effectOf(state, AdvisoryLayer.MEMORY)
        assertTrue(memory.verdict != LayerVerdict.KEEP && memory.verdict != LayerVerdict.DISABLE)
    }

    @Test fun pairwiseWalshCoefficientRevealsConflictingLayers() {
        val state = simulate(
            tasks = 3_000,
            layers = listOf(AdvisoryLayer.CONSTITUTION, AdvisoryLayer.FRACTAL),
            base = 0.50,
            effects = mapOf(AdvisoryLayer.CONSTITUTION to 0.15, AdvisoryLayer.FRACTAL to 0.15),
            interaction = (AdvisoryLayer.CONSTITUTION to AdvisoryLayer.FRACTAL) to -0.40,
            seed = 5
        )
        val pair = LayerGovernorPolicy.report(state, model).interactions
            .first { setOf(it.first, it.second) == setOf("constitution", "fractal") }
        assertTrue("estimate=${pair.estimate}", pair.estimate < -0.2)
        assertTrue(pair.conflict)
    }

    @Test fun stratificationRemovesSimpsonParadox() {
        // Within each task family the layer has no effect, but EXPOSED tasks
        // are mostly from the easy family. A pooled difference would credit it.
        fun trial(i: Int, family: String, exposed: Boolean, success: Boolean) = GovernorTrial(
            taskHash = "h$i", modelId = model, family = family,
            exposures = listOf(GovernorExposure("fractal", exposed)),
            status = if (success) "DONE" else "PARTIAL", success = success, at = i + 1L
        )
        val trials = mutableListOf<GovernorTrial>()
        var i = 0
        repeat(160) { trials += trial(i++, "EASY", true, it % 10 < 9) }   // 90%
        repeat(40) { trials += trial(i++, "EASY", false, it % 10 < 9) }   // 90%
        repeat(40) { trials += trial(i++, "HARD", true, it % 10 < 3) }    // 30%
        repeat(160) { trials += trial(i++, "HARD", false, it % 10 < 3) }  // 30%

        val effect = LayerGovernorPolicy.effect(trials, "fractal")
        val pooled = trials.filter { it.exposures[0].exposed }.count { it.success } / 200.0 -
            trials.filterNot { it.exposures[0].exposed }.count { it.success } / 200.0
        assertTrue("pooled=$pooled", pooled > 0.3)
        assertTrue("stratified=${effect.delta}", kotlin.math.abs(effect.delta) < 0.05)
        assertTrue(effect.verdict != LayerVerdict.KEEP)
    }

    @Test fun rarelyEligibleLayerIsReportedAsNotTriggered() {
        var state = simulate(300, listOf(AdvisoryLayer.MEMORY), 0.5, emptyMap())
        // A second layer that had advice in only 5 of 305 tasks.
        state = simulate(5, listOf(AdvisoryLayer.LANDSCAPE), 0.5, emptyMap(), start = state, idPrefix = "rare")
        assertEquals(LayerVerdict.NOT_TRIGGERED, effectOf(state, AdvisoryLayer.LANDSCAPE).verdict)
    }

    @Test fun firstDecisionPerTaskWinsAndResetStartsAFreshExperiment() {
        var state = LayerGovernorState()
        state = LayerGovernorPolicy.begin(state, "a", model, "GENERAL", GovernorExposure("fractal", true), 1)
        state = LayerGovernorPolicy.begin(state, "a", model, "GENERAL", GovernorExposure("fractal", false), 2)
        assertTrue(state.pending.single().exposures.single().exposed)

        state = state.copy(disabled = mapOf(LayerGovernorPolicy.disabledKey(model, "fractal") to 5L))
        state = LayerGovernorPolicy.resolve(state, "a", "DONE", 3)
        state = LayerGovernorPolicy.reset(state, model, "fractal")
        assertTrue(state.disabled.isEmpty())
        assertTrue(state.trials.single().exposures.isEmpty())
    }

    @Test fun armsOfDifferentLayersAreIndependent() {
        val ids = (1..4_000).map { "task-$it" }
        val both = ids.count {
            AdvisoryHoldout.arm(it, "fractal") == AdvisoryArm.WITHHELD &&
                AdvisoryHoldout.arm(it, "reflex") == AdvisoryArm.WITHHELD
        } / ids.size.toDouble()
        // Independent 20% arms overlap in ~4% of tasks.
        assertTrue("overlap=$both", both in 0.02..0.06)
    }

    @Test fun verdictsOnlyChangeAtCheckpoints() {
        val base = simulate(1_250, listOf(AdvisoryLayer.FRACTAL), 0.4, mapOf(AdvisoryLayer.FRACTAL to 0.1))
        val more = simulate(49, listOf(AdvisoryLayer.FRACTAL), 0.4, mapOf(AdvisoryLayer.FRACTAL to 0.1),
            seed = 99, start = base, idPrefix = "extra")
        val a = LayerGovernorPolicy.report(base, model)
        val b = LayerGovernorPolicy.report(more, model)
        assertEquals(1_200, a.checkpointTrials)
        assertEquals(1_200, b.checkpointTrials)
        assertEquals(a.effects, b.effects)
    }

    @Test fun nullLayersAreNotDisabledByRepeatedLooks() {
        var falseDisables = 0
        for (seed in 1L..20L) {
            var state = LayerGovernorState()
            for (chunk in 0 until 10) {
                state = simulate(100, listOf(AdvisoryLayer.MEMORY, AdvisoryLayer.EVIDENCE), 0.5, emptyMap(),
                    seed = seed * 1_000 + chunk, start = state, idPrefix = "s${seed}c${chunk}-")
                state = LayerGovernorPolicy.applyVerdicts(state, model, chunk + 1L)
            }
            if (state.disabled.isNotEmpty()) falseDisables++
        }
        assertTrue("false disables in 20 null experiments: $falseDisables", falseDisables <= 1)
    }

    @Test fun abandonedAndStaleTasksBecomeUnresolvedNotLost() {
        var state = LayerGovernorState()
        state = LayerGovernorPolicy.begin(state, "cancelled", model, "GENERAL", GovernorExposure("fractal", false), 1)
        state = LayerGovernorPolicy.abandon(state, "cancelled", 2)
        assertEquals(LayerGovernorPolicy.UNRESOLVED, state.trials.single().status)
        assertTrue(state.pending.isEmpty())

        state = LayerGovernorPolicy.begin(state, "old", model, "GENERAL", GovernorExposure("fractal", true), 10)
        val later = 10 + LayerGovernorPolicy.PENDING_MAX_AGE_MS + 1
        state = LayerGovernorPolicy.begin(state, "new", model, "GENERAL", GovernorExposure("fractal", true), later)
        assertEquals(2, state.trials.count { it.status == LayerGovernorPolicy.UNRESOLVED })
        assertEquals(1, state.pending.size)

        repeat(LayerGovernorPolicy.MAX_PENDING + 5) {
            state = LayerGovernorPolicy.begin(state, "flood-$it", model, "GENERAL", GovernorExposure("memory", true), later + it)
        }
        assertEquals(LayerGovernorPolicy.MAX_PENDING, state.pending.size)
        assertEquals(2 + 6, state.trials.count { it.status == LayerGovernorPolicy.UNRESOLVED })
    }

    @Test fun armThatLosesMoreTasksCannotGetAVerdict() {
        // Withheld tasks succeed less, but 30% of exposed tasks were abandoned:
        // the apparent benefit may just be that failures left the exposed arm.
        fun trial(i: Int, exposed: Boolean, status: String) = GovernorTrial(
            taskHash = "a$i", modelId = model, family = "CODE_WORK",
            exposures = listOf(GovernorExposure("reflex", exposed)),
            status = status, success = status == "DONE", at = i + 1L
        )
        val trials = mutableListOf<GovernorTrial>()
        var i = 0
        repeat(140) { trials += trial(i++, true, "DONE") }
        repeat(60) { trials += trial(i++, true, LayerGovernorPolicy.UNRESOLVED) }
        repeat(100) { trials += trial(i++, false, if (it < 50) "DONE" else "PARTIAL") }
        val effect = LayerGovernorPolicy.effect(trials, "reflex")
        assertTrue(effect.attritionImbalance)
        assertEquals(60, effect.exposedUnresolved)
        assertEquals(LayerVerdict.INSUFFICIENT_DATA, effect.verdict)
        assertTrue("delta=${effect.delta}", effect.delta > 0.3)
    }
}
