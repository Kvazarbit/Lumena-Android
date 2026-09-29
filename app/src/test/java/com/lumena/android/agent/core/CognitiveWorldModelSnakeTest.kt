package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveWorldModelSnakeTest {
    private fun state(
        width: Int = 5,
        height: Int = 5,
        body: List<SnakePoint> = listOf(
            SnakePoint(2, 2),
            SnakePoint(1, 2),
            SnakePoint(0, 2)
        ),
        food: SnakePoint? = SnakePoint(4, 1),
        direction: SnakeDirection = SnakeDirection.RIGHT
    ) = SnakeState(
        width = width,
        height = height,
        body = body,
        food = food,
        direction = direction
    )

    @Test
    fun codecExposesFullOrderedStateWithoutDerivedBestMove() {
        val encoded = SnakeStateCodec.encode(state())

        assertTrue(encoded.contains("width=5;height=5"))
        assertTrue(encoded.contains("food=4,1"))
        assertTrue(encoded.contains("body_head_to_tail=2,2|1,2|0,2"))
        assertTrue(encoded.contains("direction=RIGHT"))
        assertFalse(encoded.contains("best="))
    }

    @Test
    fun worldModelPredictsThreeStepTrajectoryAndFoodOutcome() {
        val initial = state()
        val prediction = WorldModelKernel.rollout(
            SnakeCognitiveEnvironment,
            initial,
            listOf(SnakeDirection.UP, SnakeDirection.RIGHT, SnakeDirection.RIGHT)
        )

        assertTrue(prediction.complete)
        assertEquals(3, prediction.states.size)
        assertEquals(SnakePoint(4, 1), prediction.finalState.head)
        assertEquals(1, prediction.finalState.score)
        assertNull(prediction.finalState.food)
        assertTrue(prediction.finalState.alive)
    }

    @Test
    fun verifierAcceptsOnlyObservedStateMatchingPrediction() {
        val prediction = WorldModelKernel.rollout(
            SnakeCognitiveEnvironment,
            state(),
            listOf(SnakeDirection.UP)
        )

        val accepted = VerifierKernel.verify(
            SnakeCognitiveEnvironment,
            prediction,
            prediction.finalState
        )
        val alteredActual = prediction.finalState.copy(score = prediction.finalState.score + 1)
        val rejected = VerifierKernel.verify(
            SnakeCognitiveEnvironment,
            prediction,
            alteredActual
        )

        assertTrue(accepted.accepted)
        assertEquals("verified_match", accepted.reason)
        assertFalse(rejected.accepted)
        assertEquals("state_mismatch", rejected.reason)
    }

    @Test
    fun snakeMayEnterVacatingTailCellButCannotReverseDirection() {
        val loop = state(
            width = 4,
            height = 4,
            body = listOf(
                SnakePoint(1, 1),
                SnakePoint(1, 2),
                SnakePoint(0, 2),
                SnakePoint(0, 1)
            ),
            food = SnakePoint(3, 3),
            direction = SnakeDirection.UP
        )

        assertFalse(SnakeDirection.DOWN in SnakeCognitiveEnvironment.legalActions(loop))
        val next = SnakeCognitiveEnvironment.predict(loop, SnakeDirection.LEFT)
        assertNotNull(next)
        assertTrue(next!!.alive)
        assertEquals(SnakePoint(0, 1), next.head)
    }

    @Test
    fun deliberationLooksAheadInsteadOfChoosingImmediateWallCollision() {
        val nearWall = state(
            body = listOf(
                SnakePoint(4, 2),
                SnakePoint(3, 2),
                SnakePoint(2, 2)
            ),
            food = SnakePoint(4, 0),
            direction = SnakeDirection.RIGHT
        )

        val result = DeliberationKernel.search(
            SnakeCognitiveEnvironment,
            nearWall,
            horizon = 1,
            maxNodes = 8
        )

        assertNotNull(result.best)
        assertEquals(listOf(SnakeDirection.UP), result.best!!.actions)
        assertTrue(result.best!!.finalState.alive)
        assertFalse(result.budgetExhausted)
    }

    @Test
    fun shadowLedgerRequiresVerifiedEvidenceAcrossDistinctContextsAndNeverActivates() {
        val ledger = CognitiveShadowLedger(SnakeCognitiveEnvironment)
        val sparse = state()
        val mid = state(
            width = 4,
            height = 4,
            body = listOf(
                SnakePoint(2, 2),
                SnakePoint(1, 2),
                SnakePoint(0, 2),
                SnakePoint(0, 1)
            ),
            food = SnakePoint(3, 1),
            direction = SnakeDirection.RIGHT
        )

        fun recordVerified(s: SnakeState, action: SnakeDirection) {
            val prediction = WorldModelKernel.rollout(
                SnakeCognitiveEnvironment,
                s,
                listOf(action)
            )
            val verification = VerifierKernel.verify(
                SnakeCognitiveEnvironment,
                prediction,
                prediction.finalState
            )
            assertTrue(ledger.record(prediction, verification))
        }

        recordVerified(sparse, SnakeDirection.UP)
        recordVerified(sparse.copy(food = SnakePoint(3, 0)), SnakeDirection.UP)

        assertNull(
            ledger.shadowCandidate(
                "snake.avoid_traps",
                "Prefer trajectories that preserve verified survivability."
            )
        )

        recordVerified(mid, SnakeDirection.UP)
        val candidate = ledger.shadowCandidate(
            "snake.avoid_traps",
            "Prefer trajectories that preserve verified survivability."
        )

        assertNotNull(candidate)
        assertEquals(3, candidate!!.verifiedEvidenceCount)
        assertEquals(2, candidate.distinctContexts)
        assertFalse(candidate.active)
        assertEquals("VERIFIED_TRAJECTORY", candidate.source)
    }

    @Test
    fun rejectedObservationCannotBecomeLearningEvidence() {
        val ledger = CognitiveShadowLedger(SnakeCognitiveEnvironment)
        val prediction = WorldModelKernel.rollout(
            SnakeCognitiveEnvironment,
            state(),
            listOf(SnakeDirection.UP)
        )
        val mismatch = VerifierKernel.verify(
            SnakeCognitiveEnvironment,
            prediction,
            prediction.finalState.copy(tick = 999)
        )

        assertFalse(ledger.record(prediction, mismatch))
        assertTrue(ledger.experiences().isEmpty())
    }
}
