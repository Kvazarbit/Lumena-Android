package com.lumena.android.settings

import com.lumena.android.agent.core.AdvisoryArm
import com.lumena.android.agent.core.AdvisoryHoldout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisoryHoldoutTelemetryTest {
    @Test fun armIsStablePerTaskAndNearTheConfiguredRate() {
        val ids = (1..2_000).map { "task-$it" }
        val first = ids.map { AdvisoryHoldout.arm(it, "fractal") }
        val second = ids.map { AdvisoryHoldout.arm(it, "fractal") }
        assertEquals("assignment must not change between turns of one task", first, second)

        val withheld = first.count { it == AdvisoryArm.WITHHELD }.toDouble() / ids.size
        assertTrue("withheld share was $withheld", withheld in 0.15..0.25)
    }

    @Test fun zeroPercentNeverWithholdsAndRateIsCapped() {
        assertTrue((1..200).all { AdvisoryHoldout.arm("t$it", "fractal", 0) == AdvisoryArm.EXPOSED })
        val capped = (1..2_000).count { AdvisoryHoldout.arm("t$it", "fractal", 95) == AdvisoryArm.WITHHELD }
        assertTrue("cap is 50%, got $capped/2000", capped in 850..1150)
    }

    @Test fun terminalStatusIsAttributedToTheAssignedArmOnce() {
        var state = CognitiveInfluenceState(epoch = "e1", since = 1, versionCode = 1)
        state = CognitiveInfluencePolicy.recordArm(state, "a", "fractal", "EXPOSED", 10)
        state = CognitiveInfluencePolicy.recordArm(state, "a", "fractal", "EXPOSED", 11) // later turn
        state = CognitiveInfluencePolicy.recordArm(state, "b", "fractal", "WITHHELD", 12)

        state = CognitiveInfluencePolicy.resolveTaskOutcome(state, "a", "DONE")
        state = CognitiveInfluencePolicy.resolveTaskOutcome(state, "b", "PARTIAL")
        state = CognitiveInfluencePolicy.resolveTaskOutcome(state, "a", "FAILED") // already resolved

        assertEquals(AdvisoryArmOutcome(tasks = 1, done = 1), state.armOutcomes["fractal:EXPOSED"])
        assertEquals(AdvisoryArmOutcome(tasks = 1, partial = 1), state.armOutcomes["fractal:WITHHELD"])
        assertTrue(state.pendingArms.isEmpty())
    }

    @Test fun overflowedAssignmentsAreCountedAsUnresolvedNotLost() {
        var state = CognitiveInfluenceState(epoch = "e1", since = 1, versionCode = 1)
        repeat(CognitiveInfluencePolicy.MAX_PENDING_ARMS + 3) {
            state = CognitiveInfluencePolicy.recordArm(state, "t$it", "fractal", "EXPOSED", it + 1L)
        }
        assertEquals(CognitiveInfluencePolicy.MAX_PENDING_ARMS, state.pendingArms.size)
        assertEquals(3L, state.armOutcomes["fractal:EXPOSED"]?.unresolved)
    }

    @Test fun epochRollKeepsBoundedHistoryInsteadOfErasingEvidence() {
        var current = CognitiveInfluenceState(epoch = "e0", since = 1, versionCode = 40)
        current = CognitiveInfluencePolicy.recordArm(current, "x", "fractal", "EXPOSED", 2)
        current = CognitiveInfluencePolicy.resolveTaskOutcome(current, "x", "DONE")
        repeat(CognitiveInfluencePolicy.MAX_PREVIOUS_EPOCHS + 2) { i ->
            current = CognitiveInfluencePolicy.rollEpoch(
                previous = current,
                fresh = CognitiveInfluenceState(epoch = "e${i + 1}", since = 100L + i, versionCode = 41L + i),
                now = 100L + i
            )
        }
        assertEquals(CognitiveInfluencePolicy.MAX_PREVIOUS_EPOCHS, current.previousEpochs.size)
        assertTrue(current.armOutcomes.isEmpty())

        val single = CognitiveInfluencePolicy.rollEpoch(
            previous = CognitiveInfluenceState(epoch = "old", since = 5, versionCode = 1,
                armOutcomes = mapOf("fractal:EXPOSED" to AdvisoryArmOutcome(tasks = 2, done = 1))),
            fresh = CognitiveInfluenceState(epoch = "new", since = 9, versionCode = 2),
            now = 9
        )
        assertEquals("old", single.previousEpochs.single().epoch)
        assertEquals(2L, single.previousEpochs.single().armOutcomes["fractal:EXPOSED"]?.tasks)
    }
}
