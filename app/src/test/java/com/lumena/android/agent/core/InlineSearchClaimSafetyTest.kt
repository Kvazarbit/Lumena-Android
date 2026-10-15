package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression from OnePlus ci2152 phone diagnostic:
 *
 *   status=DONE, step=0/4, kernel_observed=0, tool_result_count=0
 *   "...Ось реальний запит прямо зараз.
 *    expect_ok: true confidence: 1.0 web.search(query=\"current bitcoin price usd\")"
 *
 * This is NOT a search result and cannot be executed from prose.
 */
class InlineSearchClaimSafetyTest {
    private val controller = AgentController()

    private fun state(
        goal: String = "якого зрена ти мене дуриш???? ляєш текстову воду????"
    ) = controller.initial(
        TaskState(
            id = "real-btc-phone-claim",
            projectId = null,
            goal = goal,
            status = TaskStatus.WAITING_MODEL
        )
    )

    private val realPhoneShape =
        "Ви маєте повне право злитися. Я не перевірив дані в реальному часі. " +
        "**Більше жодних виправдань. Ось реальний запит прямо зараз.** " +
        "expect_ok: true confidence: 1.0 web.search(query=\"current bitcoin price usd\")"

    @Test fun diagnosticMessageIsPlainTextNotExecutableFunction() {
        val normalized = ProtocolNormalizer().normalize(realPhoneShape)
        assertTrue(normalized is NormalizationResult.PlainText)
        assertFalse(normalized is NormalizationResult.Canonical)
    }

    @Test fun realPhoneMixedProseDoesNotBecomeFalseDone() {
        val initial = state()
        assertEquals(TaskIntent.GENERAL, initial.intent)
        assertTrue(TaskExecutionRecap.unverifiedInlineSearchClaim(realPhoneShape, initial))

        val result = controller.interpret(realPhoneShape, initial)
        assertTrue("Expected deterministic partial, got $result",
            result is ControllerInstruction.Finish)
        result as ControllerInstruction.Finish

        assertEquals(TaskStatus.PARTIAL, result.state.task.status)
        assertEquals(0, result.state.task.kernel.observed)
        assertEquals(0, result.state.task.step)
        assertTrue(result.text.contains("Пошук не підтверджено"))
        assertFalse(result.text.contains("Ось реальний запит прямо зараз"))
        assertTrue(result.state.task.errors.contains("UNVERIFIED_INLINE_WEB_SEARCH"))
    }

    @Test fun evenStructuredDoneCannotClaimSearchWithoutReceipt() {
        val raw = """{"done":true,"summary":"Ось реальний запит прямо зараз. web.search(query=\"current bitcoin price usd\")"}"""
        val response = controller.interpret(raw, state())
        assertTrue(response is ControllerInstruction.Finish)
        assertEquals(TaskStatus.PARTIAL, response.state.task.status)
        assertEquals(0, response.state.task.kernel.observed)
    }

    @Test fun unrelatedSuccessfulObservationIsNotSearchEvidence() {
        val initial = state()
        val unrelated = controller.afterTool(
            state = initial,
            call = AgentDecision.ToolCall("system.time"),
            ok = true,
            stdout = "2026-10-15",
            stderr = "",
            error = null
        ).state
        assertEquals(1, unrelated.task.kernel.observed)
        assertTrue(TaskExecutionRecap.unverifiedInlineSearchClaim(realPhoneShape, unrelated))
        val finished = controller.interpret(realPhoneShape, unrelated)
        assertEquals(TaskStatus.PARTIAL, finished.state.task.status)
    }

    @Test fun failedSearchDoesNotAuthorizeAnExecutionSuccessClaim() {
        val initial = state()
        val failed = controller.afterTool(
            state = initial,
            call = AgentDecision.ToolCall(
                "web.search", mapOf("query" to "current bitcoin price usd")
            ),
            ok = false,
            stdout = "",
            stderr = "",
            error = "UPSTREAM_UNAVAILABLE"
        ).state
        assertTrue(TaskExecutionRecap.unverifiedInlineSearchClaim(realPhoneShape, failed))
        assertTrue(controller.interpret(realPhoneShape, failed)
            .state.task.status != TaskStatus.DONE)
    }

    @Test fun realRecordedSuccessfulSearchAvoidsFalseAlarm() {
        val initial = state()
        val observed = controller.afterTool(
            state = initial,
            call = AgentDecision.ToolCall(
                "web.search", mapOf("query" to "current bitcoin price usd")
            ),
            ok = true,
            stdout = """{"results":[{"url":"https://example.org/btc"}]}""",
            stderr = "",
            error = null
        ).state
        assertFalse(TaskExecutionRecap.unverifiedInlineSearchClaim(realPhoneShape, observed))
        assertEquals(1, observed.task.kernel.observed)
    }

    @Test fun legitimateSyntaxExplanationsStayNonExecutableConversation() {
        val explanation = "Приклад синтаксису: web.search(query=\"btc\"). Це лише текст."
        val ordinary = state("Поясни коротко функціональний синтаксис")
        assertFalse(TaskExecutionRecap.unverifiedInlineSearchClaim(explanation, ordinary))
        val result = controller.interpret(explanation, ordinary)
        assertEquals(TaskStatus.DONE, result.state.task.status)
        assertEquals(0, result.state.task.kernel.observed)
    }

    @Test fun activeWebTaskGetsOneRepairChanceNotUntrustedDone() {
        val web = state("Знайди поточну ціну Bitcoin в інтернеті")
        assertEquals(TaskIntent.PUBLIC_WEB, web.intent)
        val first = controller.interpret(realPhoneShape, web)
        assertTrue(first is ControllerInstruction.AskModelAgain)
        assertEquals(TaskStatus.WAITING_MODEL, first.state.task.status)
        assertTrue((first as ControllerInstruction.AskModelAgain)
            .feedback.contains("UNVERIFIED_INLINE_WEB_SEARCH"))
        assertEquals(0, first.state.task.kernel.observed)
        // Repeating the same fake action claim must degrade honestly.
        val second = controller.interpret(realPhoneShape, first.state)
        assertTrue(second is ControllerInstruction.Finish)
        assertEquals(TaskStatus.PARTIAL, second.state.task.status)
    }
}
