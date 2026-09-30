package com.lumena.android.settings

import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceOutcomeDeltaPolicyTest {
    @Test
    fun deterministicExpectationIsBoundedAndAdvisoryMetadataOnly() {
        val expected = requireNotNull(
            ExperienceOutcomeDeltaPolicy.expected(
                tool = "file.read",
                target = "path=README.md"
            )
        )

        assertEquals(
            ExpectationSource.DETERMINISTIC_TOOL_CONTRACT,
            expected.source
        )
        assertEquals(ExpectedOutcomeStatus.SUCCESS, expected.expectedStatus)
        assertEquals("READ_ONLY_OBSERVATION", expected.expectedEffectClass)
        assertEquals(
            "KNOWN_SUCCESSFUL_TOOL_OUTCOME",
            expected.expectedPostcondition
        )
        assertNull(expected.confidence)
        assertNull(expected.acceptanceCriterionId)
    }

    @Test
    fun verifiedSuccessProducesMatch() {
        val expected = requireNotNull(
            ExperienceOutcomeDeltaPolicy.expected(
                "python.syntax_check",
                "script=demo.py"
            )
        )
        val observed = ExperienceOutcomeDeltaPolicy.observed(
            tool = "python.syntax_check",
            target = "script=demo.py",
            result = ToolResult(
                ok = true,
                tool = "python.syntax_check",
                exitCode = 0,
                stdout = "Syntax OK"
            ),
            verifiedOk = true
        )

        val delta = ExperienceOutcomeDeltaPolicy.compare(
            expected,
            observed
        )

        assertEquals(ObservedOutcomeStatus.SUCCESS, observed.status)
        assertEquals(OutcomeDeltaKind.MATCH, delta.kind)
    }

    @Test
    fun verifiedFailureProducesUnexpectedFailure() {
        val expected = requireNotNull(
            ExperienceOutcomeDeltaPolicy.expected(
                "web.read",
                "url=https://example.com"
            )
        )
        val observed = ExperienceOutcomeDeltaPolicy.observed(
            tool = "web.read",
            target = "url=https://example.com",
            result = ToolResult(
                ok = false,
                errorCode = "SEARCH_EXHAUSTED",
                failureClass = "DEPENDENCY_EXHAUSTED",
                retryable = false,
                dependency = "web.read"
            ),
            verifiedOk = false
        )

        val delta = ExperienceOutcomeDeltaPolicy.compare(
            expected,
            observed
        )

        assertEquals(ObservedOutcomeStatus.FAILURE, observed.status)
        assertEquals(OutcomeDeltaKind.UNEXPECTED_FAILURE, delta.kind)
    }

    @Test
    fun verifiedSchemaFailureIsClassifiedWithoutReadingErrorProse() {
        val expected = requireNotNull(
            ExperienceOutcomeDeltaPolicy.expected(
                "python.run",
                "script=missing.py"
            )
        )
        val observed = ExperienceOutcomeDeltaPolicy.observed(
            tool = "python.run",
            target = "script=missing.py",
            result = ToolResult(
                ok = false,
                error = "arbitrary prose that must not define the class",
                errorCode = "PYTHON_SCRIPT_REQUIRED",
                failureClass = "INVALID_INPUT",
                retryable = true,
                dependency = "tool-schema"
            ),
            verifiedOk = false
        )

        val delta = ExperienceOutcomeDeltaPolicy.compare(
            expected,
            observed
        )

        assertEquals(OutcomeDeltaKind.SCHEMA_MISMATCH, delta.kind)
        assertEquals(
            "VERIFIED_INPUT_OR_SCHEMA_FAILURE",
            delta.reasonCode
        )
    }

    @Test
    fun unknownOutcomeNeverBecomesSuccessOrFailureLabel() {
        val expected = requireNotNull(
            ExperienceOutcomeDeltaPolicy.expected(
                "file.write",
                "path=demo.txt"
            )
        )
        val observed = ExperienceOutcomeDeltaPolicy.observed(
            tool = "file.write",
            target = "path=demo.txt",
            result = ToolResult(
                ok = false,
                outcomeUnknown = true,
                errorCode = "BRIDGE_TRANSPORT",
                failureClass = "UNKNOWN_EFFECT",
                retryable = false,
                dependency = "termux_bridge"
            ),
            verifiedOk = false
        )

        val delta = ExperienceOutcomeDeltaPolicy.compare(
            expected,
            observed
        )

        assertEquals(ObservedOutcomeStatus.UNKNOWN, observed.status)
        assertEquals(OutcomeDeltaKind.OUTCOME_UNKNOWN, delta.kind)
    }

    @Test
    fun unknownToolDoesNotInventExpectation() {
        assertNull(
            ExperienceOutcomeDeltaPolicy.expected(
                "not.a.real.tool",
                "x"
            )
        )
    }

    @Test
    fun statsIgnoreLegacyEventsWithoutPredictionLedger() {
        val legacy = CoordinatorEpisodeEvent(
            id = "legacy",
            sessionId = "s",
            taskId = "t",
            tool = "file.read",
            target = "path=a",
            ok = true,
            experienceId = "e",
            at = 1,
            surprise = 0.8
        )
        val expected = requireNotNull(
            ExperienceOutcomeDeltaPolicy.expected(
                "file.read",
                "path=b"
            )
        )
        val observed = ExperienceOutcomeDeltaPolicy.observed(
            "file.read",
            "path=b",
            ToolResult(ok = false),
            verifiedOk = false
        )
        val current = legacy.copy(
            id = "current",
            target = "path=b",
            ok = false,
            expectedOutcome = expected,
            observedOutcome = observed,
            outcomeDelta = ExperienceOutcomeDeltaPolicy.compare(
                expected,
                observed
            )
        )

        val stats = ExperienceOutcomeDeltaPolicy.stats(
            CoordinatorEpisodeState(
                events = listOf(legacy, current)
            )
        )

        assertEquals(1, stats.expectations)
        assertEquals(1, stats.deltas)
        assertEquals(1, stats.unexpectedFailures)
        assertTrue(stats.matches == 0)
    }
}
