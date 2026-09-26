package com.lumena.android.settings

import com.lumena.android.agent.core.ReflexOption
import com.lumena.android.agent.core.TinyJevCandidateScore
import com.lumena.android.agent.core.TinyJevDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TinyJevCalibrationPolicyTest {
    private fun decision(
        retry: Double,
        alternative: Double
    ) = TinyJevDecision(
        scores = listOf(
            TinyJevCandidateScore(
                id = ReflexOption.RETRY_VARIANT.name,
                logit = 1.0,
                probability = retry
            ),
            TinyJevCandidateScore(
                id = ReflexOption.TRY_ALTERNATIVE.name,
                logit = 0.0,
                probability = alternative
            )
        ),
        confidence = maxOf(retry, alternative),
        normalizedCertainty = 0.5,
        modelVersion = "tinyjev-test",
        calibrated = false
    )

    private fun recovery(
        id: String,
        tools: List<String>,
        updatedAt: Long,
        evidence: List<String> = listOf("ev-1")
    ) = CoordinatorExecutionExample(
        id = id,
        kind = CoordinatorExampleKind.RECOVERY,
        sourceSessionHash = "session",
        tools = tools,
        targets = tools.map { "target=x" },
        evidenceIds = evidence,
        updatedAt = updatedAt,
        surprise = 0.8,
        text = "verified recovery"
    )

    @Test
    fun verifiedRetryResolvesPendingPrediction() {
        val predicted = TinyJevCalibrationPolicy.recordPrediction(
            state = TinyJevCalibrationState(),
            taskKey = "task-key",
            family = "web.read",
            attempt = 1,
            decision = decision(0.8, 0.2),
            selectedOption = ReflexOption.RETRY_VARIANT,
            decisionLatencyMs = 4,
            now = 100
        )

        val resolved = TinyJevCalibrationPolicy.resolveVerified(
            state = predicted,
            taskKey = "task-key",
            examples = listOf(
                recovery(
                    id = "r1",
                    tools = listOf("web.read", "web.read"),
                    updatedAt = 150
                )
            ),
            now = 160
        )

        assertTrue(resolved.pending.isEmpty())
        assertEquals(1, resolved.resolved.size)
        assertTrue(resolved.resolved.single().correct)
        assertEquals(
            ReflexOption.RETRY_VARIANT.name,
            resolved.resolved.single().expectedOption
        )

        val metrics = TinyJevCalibrationPolicy.metrics(resolved)
        assertEquals(1, metrics.samples)
        assertEquals(1.0, metrics.accuracy, 1e-9)
        assertEquals(0.04, metrics.brierScore, 1e-9)
        assertEquals(0.20, metrics.ece, 1e-9)
        assertEquals(1.0, metrics.selectiveCoverage, 1e-9)
        assertEquals(1.0, metrics.selectiveAccuracy ?: 0.0, 1e-9)
        assertEquals(4L, metrics.latencyP50Ms)
        assertEquals(4L, metrics.latencyP95Ms)
    }

    @Test
    fun unverifiedRecoveryNeverBecomesCalibrationLabel() {
        val predicted = TinyJevCalibrationPolicy.recordPrediction(
            state = TinyJevCalibrationState(),
            taskKey = "task-key",
            family = "web.read",
            attempt = 1,
            decision = decision(0.8, 0.2),
            selectedOption = ReflexOption.RETRY_VARIANT,
            decisionLatencyMs = 3,
            now = 100
        )

        val unresolved = TinyJevCalibrationPolicy.resolveVerified(
            state = predicted,
            taskKey = "task-key",
            examples = listOf(
                recovery(
                    id = "r1",
                    tools = listOf("web.read", "web.read"),
                    updatedAt = 150,
                    evidence = emptyList()
                )
            ),
            now = 160
        )

        assertEquals(1, unresolved.pending.size)
        assertTrue(unresolved.resolved.isEmpty())
    }

    @Test
    fun olderRecoveryCannotLabelNewPrediction() {
        val predicted = TinyJevCalibrationPolicy.recordPrediction(
            state = TinyJevCalibrationState(),
            taskKey = "task-key",
            family = "web.read",
            attempt = 2,
            decision = decision(0.7, 0.3),
            selectedOption = ReflexOption.RETRY_VARIANT,
            decisionLatencyMs = 2,
            now = 200
        )

        val unresolved = TinyJevCalibrationPolicy.resolveVerified(
            state = predicted,
            taskKey = "task-key",
            examples = listOf(
                recovery(
                    id = "old",
                    tools = listOf("web.read", "web.read"),
                    updatedAt = 150
                )
            ),
            now = 250
        )

        assertEquals(1, unresolved.pending.size)
        assertTrue(unresolved.resolved.isEmpty())
    }

    @Test
    fun multiStepVerifiedRecoveryLabelsAlternative() {
        val predicted = TinyJevCalibrationPolicy.recordPrediction(
            state = TinyJevCalibrationState(),
            taskKey = "task-key",
            family = "web.read",
            attempt = 1,
            decision = decision(0.7, 0.3),
            selectedOption = ReflexOption.RETRY_VARIANT,
            decisionLatencyMs = 5,
            now = 100
        )

        val resolved = TinyJevCalibrationPolicy.resolveVerified(
            state = predicted,
            taskKey = "task-key",
            examples = listOf(
                recovery(
                    id = "r2",
                    tools = listOf(
                        "web.read",
                        "web.search",
                        "web.read"
                    ),
                    updatedAt = 170
                )
            ),
            now = 180
        )

        assertEquals(1, resolved.resolved.size)
        assertFalse(resolved.resolved.single().correct)
        assertEquals(
            ReflexOption.TRY_ALTERNATIVE.name,
            resolved.resolved.single().expectedOption
        )
    }

    @Test
    fun estimateStaysUncalibratedBelowEvidenceGate() {
        var state = TinyJevCalibrationState()
        state = TinyJevCalibrationPolicy.recordPrediction(
            state,
            "task-key",
            "web.read",
            1,
            decision(0.8, 0.2),
            ReflexOption.RETRY_VARIANT,
            4,
            100
        )
        state = TinyJevCalibrationPolicy.resolveVerified(
            state,
            "task-key",
            listOf(
                recovery(
                    "r1",
                    listOf("web.read", "web.read"),
                    150
                )
            ),
            160
        )

        val estimate = TinyJevCalibrationPolicy.estimate(
            state = state,
            family = "web.read",
            rawConfidence = 0.8
        )

        assertFalse(estimate.calibrated)
        assertEquals(0.8, estimate.confidence, 1e-9)
        assertEquals("insufficient", estimate.scope)
    }

    @Test
    fun estimateUsesSmoothedEmpiricalReliabilityWhenEnoughEvidenceExists() {
        var state = TinyJevCalibrationState()
        repeat(4) { i ->
            val taskKey = "task-$i"
            state = TinyJevCalibrationPolicy.recordPrediction(
                state = state,
                taskKey = taskKey,
                family = "web.read",
                attempt = 1,
                decision = decision(0.8, 0.2),
                selectedOption = ReflexOption.RETRY_VARIANT,
                decisionLatencyMs = 2 + i.toLong(),
                now = 100L + i * 10L
            )
            val tools =
                if (i < 3) {
                    listOf("web.read", "web.read")
                } else {
                    listOf(
                        "web.read",
                        "web.search",
                        "web.read"
                    )
                }
            state = TinyJevCalibrationPolicy.resolveVerified(
                state = state,
                taskKey = taskKey,
                examples = listOf(
                    recovery(
                        id = "r-$i",
                        tools = tools,
                        updatedAt = 200L + i * 10L
                    )
                ),
                now = 220L + i * 10L
            )
        }

        val estimate = TinyJevCalibrationPolicy.estimate(
            state = state,
            family = "web.read",
            rawConfidence = 0.8,
            minSamples = 4,
            minBinSamples = 3
        )

        assertTrue(estimate.calibrated)
        assertEquals("family", estimate.scope)
        assertEquals(4, estimate.sampleCount)
        // 3 correct / 4 with Beta(1,1) smoothing -> 4/6 ~= 0.6667.
        assertEquals(4.0 / 6.0, estimate.confidence, 1e-9)
        assertTrue(estimate.brierScore >= 0.0)
        assertTrue(estimate.ece >= 0.0)
    }

    @Test
    fun emptyMetricsExposeNoSelectiveAccuracy() {
        val metrics = TinyJevCalibrationPolicy.metrics(
            TinyJevCalibrationState()
        )
        assertEquals(0, metrics.samples)
        assertNull(metrics.selectiveAccuracy)
    }
}
