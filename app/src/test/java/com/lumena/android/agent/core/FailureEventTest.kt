package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureEventTest {
    @Test
    fun protocolFailureBecomesTypedInvalidInputWithoutAuthority() {
        val event = FailureEvents.fromProtocol(
            NormalizationResult.Failure(
                kind = ProtocolFailureKind.UNKNOWN_ACTION,
                reason = "Unknown model action: shell"
            ),
            attempt = 1
        )

        assertEquals(FailureSource.PROTOCOL, event.source)
        assertEquals(FailureClass.INVALID_INPUT, event.failureClass)
        assertEquals(EffectClass.NONE, event.effectClass)
        assertEquals("model-protocol", event.dependency)
        assertEquals(null, event.actionFamily)
        assertTrue(event.retryable == true)
        assertFalse(event.outcomeUnknown)
    }

    @Test
    fun contextPressureIsTypedBeforeControllerPolicy() {
        val event = FailureEvents.fromModel(
            "Ollama stream error: context length exceeded; prompt has too many tokens",
            attempt = 1
        )

        assertEquals(FailureSource.CONTEXT, event.source)
        assertEquals(FailureClass.CONTEXT_PRESSURE, event.failureClass)
        assertEquals(EffectClass.NONE, event.effectClass)
        assertTrue(event.retryable == false)
    }

    @Test
    fun temporaryModelTransportFailureRemainsRetryable() {
        val event = FailureEvents.fromModel(
            "temporary model transport failure: connection reset",
            attempt = 2
        )

        assertEquals(FailureSource.TRANSPORT, event.source)
        assertEquals(FailureClass.TRANSIENT_TRANSPORT, event.failureClass)
        assertTrue(event.retryable == true)
        assertEquals(2, event.attempt)
    }

    @Test
    fun structuredToolFailureWinsOverLocalizedText() {
        val event = FailureEvents.fromToolOutcome(
            call = AgentDecision.ToolCall(
                tool = "web.search",
                args = mapOf("query" to "news")
            ),
            errorCode = "SEARCH_EXHAUSTED",
            suppliedClass = "DEPENDENCY_EXHAUSTED",
            error = "будь-який локалізований текст",
            retryable = false,
            dependency = "web.search",
            attempt = 1
        )

        assertEquals(FailureSource.TOOL, event.source)
        assertEquals(FailureClass.DEPENDENCY_EXHAUSTED, event.failureClass)
        assertEquals(EffectClass.READ_ONLY, event.effectClass)
        assertEquals("web.search", event.actionFamily)
        assertEquals("web.search", event.dependency)
        assertEquals("SEARCH_EXHAUSTED", event.code)
        assertTrue(event.retryable == false)
    }

    @Test
    fun unknownMutationOutcomeIsExplicitAndNeverMarkedRetryable() {
        val event = FailureEvents.fromToolOutcome(
            call = AgentDecision.ToolCall(
                tool = "file.write",
                args = mapOf("path" to "x.txt", "content" to "x")
            ),
            error = "bridge connection dropped",
            retryable = true,
            dependency = "bridge",
            outcomeUnknown = true,
            attempt = 1
        )

        assertEquals(FailureClass.UNKNOWN_EFFECT, event.failureClass)
        assertEquals(EffectClass.MUTATING_OR_EXECUTABLE, event.effectClass)
        assertTrue(event.outcomeUnknown)
        assertTrue(event.retryable == false)
    }

    @Test
    fun policyDenialIsNotARecoverableToolFailure() {
        val event = FailureEvents.policyDenied(
            reason = "Unknown tool: shell.exec",
            actionFamily = "shell.exec",
            effectClass = EffectClass.NONE,
            attempt = 1,
            dependency = "tool-registry"
        )

        assertEquals(FailureSource.POLICY, event.source)
        assertEquals(FailureClass.POLICY_DENIED, event.failureClass)
        assertTrue(event.retryable == false)
        assertEquals("tool-registry", event.dependency)
    }

    @Test
    fun eventEvidenceIsBoundedAndNullCharactersAreRemoved() {
        val event = FailureEvents.fromModel(
            "temporary model transport failure\u0000" + "x".repeat(10_000),
            attempt = 1
        )

        assertTrue(event.evidence.length <= 8_000)
        assertFalse(event.evidence.contains('\u0000'))
    }

    @Test(expected = IllegalArgumentException::class)
    fun attemptMustBePositive() {
        FailureEvent(
            source = FailureSource.PROTOCOL,
            failureClass = FailureClass.INVALID_INPUT,
            retryable = true,
            effectClass = EffectClass.NONE,
            dependency = null,
            evidence = "bad",
            actionFamily = null,
            attempt = 0
        )
    }
}
