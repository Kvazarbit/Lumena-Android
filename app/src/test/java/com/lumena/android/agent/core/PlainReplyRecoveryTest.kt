package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class PlainReplyRecoveryTest {
    private val controller = AgentController()

    @Test fun ambiguousToolKeyCannotFinishGeneralTask() {
        val state = controller.initial(TaskState("retry", null, "повтори"))
        for (text in listOf("""{"web.search":{},"file.read":{}}""", """{"unknown.run":{}}""")) {
            assertTrue(controller.interpret(text, state) is ControllerInstruction.AskModelAgain)
        }
    }
    private fun webState() = controller.initial(TaskState("web", null, "Знайди новини в інтернеті"))
        .copy(toolUsed = true)

    @Test fun repeatedProseBecomesExplicitPartialInsteadOfProtocolCrash() {
        val first = controller.interpret("Ось відповідь", webState()) as ControllerInstruction.AskModelAgain
        assertTrue(first.feedback.contains("\"partial\":true"))
        assertTrue(first.feedback.contains("web.read"))
        val second = controller.interpret("Ось відповідь", first.state) as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, second.state.task.status)
        assertTrue(second.text.contains("Неперевірений текст моделі"))
        assertTrue(second.text.contains("Ось відповідь"))
        assertEquals(0, second.state.task.step)
        assertEquals(first.state.task.kernel, second.state.task.kernel)
    }

    @Test
    fun repeatedUnsupportedJsonAfterSuccessfulWebSearchBecomesPartialNotFailed() {
        val task = TaskState(
            "web-json-shape",
            null,
            "Знайди останні новини Python в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val call = AgentDecision.ToolCall(
            "web.search",
            mapOf("query" to "latest Python news")
        )
        val initial = controller.initial(task)
        val executing = initial.copy(
            task = initial.task.copy(
                status = TaskStatus.EXECUTING,
                kernel = ContextKernel.before(initial.task.kernel, call)
            )
        )
        val afterSearch = controller.afterTool(
            state = executing,
            call = call,
            ok = true,
            stdout = """{"provider":"duckduckgo-lite","results":[{"url":"https://example.org/python"}]}""",
            stderr = "",
            error = null
        ).state

        val unsupported =
            """{"query":"latest Python news","provider":"duckduckgo-lite","results":[{"url":"https://example.org/python"}]}"""

        val first = controller.interpret(unsupported, afterSearch)
        assertTrue(first is ControllerInstruction.AskModelAgain)
        first as ControllerInstruction.AskModelAgain
        assertTrue(first.feedback.contains("web.read"))
        assertTrue(first.feedback.contains("Do not echo"))

        val second = controller.interpret(unsupported, first.state)
        assertTrue(second is ControllerInstruction.Finish)
        second as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, second.state.task.status)
        assertTrue(second.text.contains("Перевірений TOOL_RESULT"))
        assertTrue(second.text.contains("web.search"))
        assertTrue(second.text.contains("duckduckgo-lite"))
        assertEquals(afterSearch.task.kernel, second.state.task.kernel)
    }

    @Test fun verifiedWebReadCanCompletePlainReplyWithoutProtocolCorrection() {
        val evidence = ContextKernel.record(ContextKernelState(),
            AgentDecision.ToolCall("web.read", mapOf("url" to "https://example.org/news")), true, "Read article")
        val state = webState().let { it.copy(task = it.task.copy(kernel = evidence)) }
        val result = controller.interpret("Перевірений підсумок", state)
        assertTrue(result is ControllerInstruction.Finish)
        assertEquals(TaskStatus.DONE, result.state.task.status)
        assertEquals(0, result.state.protocolRetries)
    }

    @Test fun missingExecutionCannotTurnProseIntoSuccess() {
        val state = webState().copy(toolUsed = false, protocolRetries = 1)
        val result = controller.interpret("Я все перевірила", state) as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, result.state.task.status)
        assertFalse(result.state.toolUsed)
        assertEquals(0, result.state.task.kernel.observed)
    }

    @Test fun failedFetchRemainsFailedEvidenceInPartialReport() {
        val evidence = ContextKernel.record(ContextKernelState(),
            AgentDecision.ToolCall("web.search", mapOf("query" to "news")), false, "No network")
        val state = webState().let { it.copy(protocolRetries = 1, task = it.task.copy(kernel = evidence)) }
        val result = controller.interpret("Мережа недоступна", state) as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, result.state.task.status)
        assertEquals(evidence, result.state.task.kernel)
        assertTrue(result.text.contains("latest tool failed"))
    }

    @Test fun visualReplyCannotBypassUnknownMutationOrPendingTargets() {
        val kernel = ContextKernelState(inFlight = ActionFlight("file.write", "test.py", "sig"),
            pendingVerification = setOf("test.py"))
        val state = controller.initial(TaskState("image", null, "Знайди фото в інтернеті", kernel = kernel))
            .copy(toolUsed = true, visualEvidenceReady = true, protocolRetries = 1)
        val result = controller.interpret("Ось фото, все готово", state) as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, result.state.task.status)
        assertEquals(kernel, result.state.task.kernel)
        assertTrue(result.state.verificationRequired)
    }

    @Test fun malformedCommandsRemainNonExecutableProtocolFailures() {
        for (raw in listOf("Run this {\"tool\":\"file.write\",\"args\":{}}",
            "Run this {\"name\":\"file.write\",\"arguments\":{}}", "Oops {\"partial\":true")) {
            val result = controller.interpret(raw, webState().copy(protocolRetries = 2))
            assertTrue(result is ControllerInstruction.Stop)
            assertEquals(TaskStatus.FAILED, result.state.task.status)
            assertEquals(0, result.state.task.step)
        }
    }

    @Test fun emptyOutputNeverCompletesConversation() {
        val result = controller.interpret(" ", controller.initial(TaskState("chat", null, "Привіт")))
        assertTrue(result is ControllerInstruction.AskModelAgain)
        assertNotEquals(TaskStatus.DONE, result.state.task.status)
    }
}
