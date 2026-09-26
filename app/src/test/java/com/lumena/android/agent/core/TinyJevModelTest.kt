package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TinyJevModelTest {
    private val model = TinyJevModel(TinyJevWeights.embeddedFallback())

    @Test
    fun transientFailurePrefersRetryAmongSupportedOptions() {
        val decision = TinyJevReflexAdapter.rank(
            model = model,
            goal = "read a public page",
            event = FailureEvent(
                source = FailureSource.TRANSPORT,
                failureClass = FailureClass.TRANSIENT_TRANSPORT,
                retryable = true,
                effectClass = EffectClass.READ_ONLY,
                dependency = "web",
                evidence = "connection reset",
                actionFamily = "web.read",
                attempt = 1
            ),
            supportedOptions = setOf(
                ReflexOption.RETRY_VARIANT,
                ReflexOption.TRY_ALTERNATIVE
            )
        )

        assertEquals(
            ReflexOption.RETRY_VARIANT,
            TinyJevReflexAdapter.bestOption(decision)
        )
        assertFalse(decision!!.calibrated)
        assertTrue(decision.confidence > 0.5)
    }

    @Test
    fun unknownEffectPrefersStopWhenStopIsSupported() {
        val decision = TinyJevReflexAdapter.rank(
            model = model,
            goal = "continue task",
            event = FailureEvent(
                source = FailureSource.TOOL,
                failureClass = FailureClass.UNKNOWN_EFFECT,
                retryable = false,
                effectClass = EffectClass.MUTATING_OR_EXECUTABLE,
                dependency = null,
                evidence = "outcome unknown",
                actionFamily = "file.write",
                attempt = 2,
                outcomeUnknown = true
            ),
            supportedOptions = setOf(
                ReflexOption.ASK_PLANNER,
                ReflexOption.STOP
            )
        )

        assertEquals(
            ReflexOption.STOP,
            TinyJevReflexAdapter.bestOption(decision)
        )
    }

    @Test
    fun propertiesModelParsesAndRanks() {
        val parsed = TinyJevWeights.parseProperties(
            """
            model.version=test-model
            model.temperature=1.0
            w.bias=0.0
            w.lexical_overlap=1.0
            w.exact_id=1.0
            """.trimIndent()
        )
        val local = TinyJevModel(parsed)
        val decision = local.rank(
            TinyJevRequest(
                state = "choose alpha",
                candidates = listOf(
                    TinyJevCandidate("alpha", "alpha route"),
                    TinyJevCandidate("beta", "beta route")
                )
            )
        )

        assertEquals("test-model", decision.modelVersion)
        assertEquals("alpha", decision.best().id)
    }
}
