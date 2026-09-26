package com.lumena.android.settings

import com.lumena.android.agent.core.ReflexCandidateSet
import com.lumena.android.agent.core.ReflexOption
import com.lumena.android.agent.local.LayaSystem1Decision
import com.lumena.android.agent.local.LayaSystem1Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayaShadowPolicyTest {
    private val candidates = ReflexCandidateSet(
        allowed = linkedSetOf(
            ReflexOption.TRY_ALTERNATIVE,
            ReflexOption.ASK_PLANNER,
            ReflexOption.STOP
        ),
        constitutionalAnchor =
            ReflexOption.TRY_ALTERNATIVE,
        reason = "fixture"
    )

    @Test
    fun shadowMetricsMeasureAgreementNotAuthority() {
        var state = LayaShadowState()

        val decision = LayaSystem1Decision(
            option = ReflexOption.TRY_ALTERNATIVE,
            probabilities = mapOf(
                ReflexOption.TRY_ALTERNATIVE to 0.8,
                ReflexOption.ASK_PLANNER to 0.15,
                ReflexOption.STOP to 0.05
            ),
            confidence = 0.55,
            latencyMs = 31,
            model = "laya-rl-agent"
        )

        state = LayaShadowPolicy.record(
            state = state,
            taskKey = "hashed-task",
            family = "file.read",
            attempt = 1,
            candidates = candidates,
            referenceOption =
                ReflexOption.TRY_ALTERNATIVE,
            result = LayaSystem1Result(
                ok = true,
                decision = decision,
                latencyMs = 31
            ),
            now = 1000L
        )

        val metrics = LayaShadowPolicy.metrics(state)
        assertEquals(1, metrics.samples)
        assertEquals(1, metrics.successful)
        assertEquals(0, metrics.unavailable)
        assertEquals(
            1.0,
            metrics.agreementWithReference ?: -1.0,
            0.0001
        )
        assertEquals(31L, metrics.latencyP50Ms)
        assertEquals(
            "TRY_ALTERNATIVE",
            metrics.lastChoice
        )
    }

    @Test
    fun unavailableRuntimeIsRecordedWithoutFakeDecision() {
        val state = LayaShadowPolicy.record(
            state = LayaShadowState(),
            taskKey = "hashed-task",
            family = "web.read",
            attempt = 2,
            candidates = candidates,
            referenceOption = ReflexOption.STOP,
            result = LayaSystem1Result(
                ok = false,
                latencyMs = 2,
                error = "not running",
                errorCode = "LAYA_NOT_RUNNING"
            ),
            now = 2000L
        )

        val sample = state.samples.single()
        assertTrue(!sample.ok)
        assertNull(sample.layaOption)
        assertTrue(sample.probabilities.isEmpty())

        val metrics = LayaShadowPolicy.metrics(state)
        assertEquals(1, metrics.samples)
        assertEquals(0, metrics.successful)
        assertEquals(1, metrics.unavailable)
        assertNull(metrics.agreementWithReference)
        assertEquals(
            "LAYA_NOT_RUNNING",
            metrics.lastErrorCode
        )
    }
}
