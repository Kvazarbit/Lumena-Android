package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The least-action formula must wait until U and E are real: enough stated
 * predictions, calibrated out of sample. Before that it computes nothing.
 */
class LeastActionPolicyTest {
    private val model = "m"

    private fun task(instruction: String = "Онови проєкт demo і перевір його") =
        TaskState(
            id = "t1",
            projectId = null,
            goal = instruction,
            currentInstruction = instruction,
            effectivePolicy = EffectiveTaskPolicyCompiler.compile(
                rootGoal = instruction,
                currentInstruction = instruction
            )
        )

    /** Honest forecaster: high p when the call succeeds, low p when it fails. */
    private fun informative(n: Int, tool: String = "file.read", elapsed: Long = 200): LeastActionState =
        (0 until n).fold(LeastActionState()) { s, i ->
            val ok = i % 2 == 0
            LeastActionPolicy.record(
                s,
                LeastActionOutcome(model, tool, ok, elapsed, if (ok) 0.9 else 0.1, i.toLong() + 1)
            )
        }

    @Test fun waitsForDataAndComputesNothingBeforeIt() {
        val state = informative(LeastActionPolicy.MIN_PREDICTIONS - 1)
        val readiness = LeastActionPolicy.readiness(state, model)
        assertEquals(LeastActionStatus.WAITING_FOR_DATA, readiness.status)
        assertNull(LeastActionPolicy.terms(state, model, "file.read", task()))
        assertNull(LeastActionPolicy.shadow(state, model, "file.read", task(), ok = true, now = 1))
    }

    @Test fun outcomesWithoutStatedPredictionDoNotCountAsCalibrationData() {
        val state = (0 until 200).fold(LeastActionState()) { s, i ->
            LeastActionPolicy.record(s, LeastActionOutcome(model, "file.read", i % 2 == 0, 100, null, i + 1L))
        }
        val readiness = LeastActionPolicy.readiness(state, model)
        assertEquals(LeastActionStatus.WAITING_FOR_DATA, readiness.status)
        assertEquals(0, readiness.predictions)
    }

    @Test fun overconfidentPredictionsAreNotInformative() {
        // Always "99% sure it works", but only half the calls succeed.
        val state = (0 until 120).fold(LeastActionState()) { s, i ->
            LeastActionPolicy.record(s, LeastActionOutcome(model, "file.read", i % 2 == 0, 100, 0.99, i + 1L))
        }
        val readiness = LeastActionPolicy.readiness(state, model)
        assertEquals(LeastActionStatus.NOT_INFORMATIVE, readiness.status)
        assertNull(LeastActionPolicy.terms(state, model, "file.read", task()))
    }

    @Test fun calibratedPredictionsUnlockTheFormula() {
        val state = informative(90)
        val readiness = LeastActionPolicy.readiness(state, model)
        assertEquals(readiness.toString(), LeastActionStatus.READY, readiness.status)
        assertTrue(readiness.skill!! >= LeastActionPolicy.MIN_SKILL)
        assertTrue(readiness.ece!! <= LeastActionPolicy.MAX_ECE)

        val terms = LeastActionPolicy.terms(state, model, "file.read", task())
        assertNotNull(terms)
        terms!!
        listOf(terms.cost, terms.risk, terms.uncertainty, terms.predictionError, terms.gain)
            .forEach { assertTrue(it.toString(), it in 0.0..1.0) }
        assertEquals(0.0, terms.risk, 0.0)
        // 50% observed success rate: maximal outcome uncertainty.
        assertEquals(1.0, terms.uncertainty, 0.01)
        assertTrue(terms.j.isFinite())
    }

    @Test fun toolWithTooLittleHistoryHasNoTermsEvenWhenReady() {
        var state = informative(90)
        repeat(LeastActionPolicy.MIN_TOOL_OUTCOMES - 1) { i ->
            state = LeastActionPolicy.record(state, LeastActionOutcome(model, "git.status", true, 50, 0.9, 1000L + i))
        }
        assertNull(LeastActionPolicy.terms(state, model, "git.status", task()))
    }

    @Test fun shadowNeverConsidersToolsTheTaskPolicyForbids() {
        var state = informative(90)
        repeat(20) { i ->
            state = LeastActionPolicy.record(state, LeastActionOutcome(model, "python.run", true, 100, 0.9, 2000L + i))
        }
        val readOnly = task("Тільки прочитай aquarium.html")
        assertTrue(!LeastActionPolicy.policyPermits(readOnly.effectivePolicy, "python.run"))
        val shadow = LeastActionPolicy.shadow(state, model, "file.read", readOnly, ok = true, now = 5)
        assertNotNull(shadow)
        shadow!!
        assertEquals(1, shadow.candidates)
        assertEquals("file.read", shadow.bestTool)
        assertTrue(shadow.agreed())
    }

    @Test fun otherModelsDataNeverUnlocksThisModel() {
        val state = informative(90)
        assertEquals(
            LeastActionStatus.WAITING_FOR_DATA,
            LeastActionPolicy.readiness(state, "other-model").status
        )
    }

    @Test fun predictionConversionAndBounds() {
        assertEquals(0.8, LeastActionPolicy.predictedOk(true, 0.8)!!, 1e-9)
        assertEquals(0.2, LeastActionPolicy.predictedOk(false, 0.8)!!, 1e-9)
        assertNull(LeastActionPolicy.predictedOk(true, null))
        assertNull(LeastActionPolicy.predictedOk(null, 0.8))
        assertEquals(1.0, LeastActionPolicy.binaryEntropy(0.5), 1e-9)
        assertEquals(0.0, LeastActionPolicy.binaryEntropy(1.0), 1e-9)
    }

    @Test fun ledgerIsBounded() {
        val state = informative(LeastActionPolicy.MAX_OUTCOMES + 10)
        assertEquals(LeastActionPolicy.MAX_OUTCOMES, state.outcomes.size)
    }

    @Test fun protocolCarriesPredictionAndDropsMalformedOnes() {
        val parser = AgentResponseParser()
        val normalizer = ProtocolNormalizer()
        fun parse(raw: String): AgentDecision {
            val normalized = normalizer.normalize(raw)
            assertTrue(normalized.toString(), normalized is NormalizationResult.Canonical)
            return parser.parse((normalized as NormalizationResult.Canonical).json)
        }
        val good = parse("""{"tool":"file.read","args":{"path":"a.py"},"expect_ok":true,"confidence":0.7}""")
            as AgentDecision.ToolCall
        assertEquals(true, good.expectOk)
        assertEquals(0.7, good.confidence!!, 1e-9)

        // A bad prediction must never cost a protocol retry.
        val bad = parse("""{"tool":"file.read","args":{"path":"a.py"},"expect_ok":"maybe","confidence":7}""")
            as AgentDecision.ToolCall
        assertEquals("file.read", bad.tool)
        assertNull(bad.expectOk)
        assertNull(bad.confidence)
    }
}
