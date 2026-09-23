package com.lumena.android.settings

import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.core.WebQueryClass
import com.lumena.android.agent.core.WebStrategyKind
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveWebObservationFactoryTest {
    private fun task(goal: String) = TaskState(
        id = "adaptive-web-test",
        projectId = null,
        goal = goal,
        status = TaskStatus.WAITING_MODEL
    )

    @Test
    fun relevantEnglishSearchProducesHighRelevanceObservation() {
        val request = ToolRequest(
            tool = "web.search",
            args = mapOf(
                "query" to "Python programming language latest release"
            ),
            requestId = "r1"
        )
        val result = ToolResult(
            ok = true,
            stdout =
                """{"query":"Python programming language latest release","results":[""" +
                    """{"title":"Python 3.15 release","url":"https://python.org/","snippet":"Latest Python programming language release notes"}""" +
                    """]}"""
        )

        val observation = AdaptiveWebObservationFactory.from(
            task = task("Знайди актуальний реліз Python в інтернеті"),
            request = request,
            result = result,
            elapsedMs = 700,
            evidenceId = "ev-1",
            now = 1_800_000_000_000L
        )

        assertNotNull(observation)
        observation!!
        assertEquals(
            WebStrategyKind.SEARCH_ENGLISH_TECHNICAL,
            observation.kind
        )
        assertEquals(WebQueryClass.ENGLISH, observation.queryClass)
        assertTrue((observation.relevance ?: 0.0) >= 0.75)
        assertTrue(observation.calibrationEligible)
    }

    @Test
    fun irrelevantSuccessfulSearchDoesNotLookRelevantJustBecauseQueryIsEchoed() {
        val request = ToolRequest(
            tool = "web.search",
            args = mapOf(
                "query" to "Python programming language latest release"
            ),
            requestId = "r2"
        )
        val result = ToolResult(
            ok = true,
            stdout =
                """{"query":"Python programming language latest release","results":[""" +
                    """{"title":"Football scores","url":"https://sports.example/","snippet":"League results and match reports"}""" +
                    """]}"""
        )

        val observation = AdaptiveWebObservationFactory.from(
            task = task("Find Python release news online"),
            request = request,
            result = result,
            elapsedMs = 800,
            evidenceId = "ev-2",
            now = 1_800_000_000_100L
        )!!

        assertTrue((observation.relevance ?: 1.0) <= 0.25)
    }

    @Test
    fun bridgeAuthenticationFailureIsRecordedButExcludedFromCalibration() {
        val observation = AdaptiveWebObservationFactory.from(
            task = task("Знайди документацію в інтернеті"),
            request = ToolRequest(
                tool = "web.read",
                args = mapOf("url" to "https://docs.example/page"),
                requestId = "r3"
            ),
            result = ToolResult(
                ok = false,
                error = "Unauthorized",
                errorCode = "BRIDGE_START_FAILED",
                failureClass = "AUTH_OR_CONFIG",
                dependency = "termux_bridge"
            ),
            elapsedMs = 12,
            evidenceId = "ev-3",
            now = 1_800_000_000_200L
        )!!

        assertEquals("docs.example", observation.host)
        assertFalse(observation.calibrationEligible)
        assertFalse(observation.ok)
    }

    @Test
    fun challengeFailureRemainsEligibleForThatPublicRoute() {
        val observation = AdaptiveWebObservationFactory.from(
            task = task("Read public documentation"),
            request = ToolRequest(
                tool = "web.read",
                args = mapOf("url" to "https://blocked.example/docs"),
                requestId = "r4"
            ),
            result = ToolResult(
                ok = false,
                error = "Page requires human verification; use another source",
                failureClass = "DEPENDENCY_EXHAUSTED",
                dependency = "blocked.example"
            ),
            elapsedMs = 450,
            evidenceId = "ev-4",
            now = 1_800_000_000_300L
        )!!

        assertEquals(WebStrategyKind.READ_DIRECT, observation.kind)
        assertEquals("blocked.example", observation.host)
        assertTrue(observation.calibrationEligible)
    }

    @Test
    fun outcomeUnknownIsNeverLearned() {
        val observation = AdaptiveWebObservationFactory.from(
            task = task("Read public documentation"),
            request = ToolRequest(
                tool = "web.read",
                args = mapOf("url" to "https://example.org/docs"),
                requestId = "r5"
            ),
            result = ToolResult(
                ok = false,
                outcomeUnknown = true,
                failureClass = "UNKNOWN_EFFECT"
            ),
            elapsedMs = 100,
            evidenceId = "ev-5",
            now = 1_800_000_000_400L
        )

        assertEquals(null, observation)
    }
}
