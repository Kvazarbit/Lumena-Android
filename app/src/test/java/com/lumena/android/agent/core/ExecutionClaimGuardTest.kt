package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionClaimGuardTest {
    private val controller = AgentController()

    private fun previous(
        goal: String = "Знайди актуальний графік ціни Bitcoin",
        status: TaskStatus = TaskStatus.DONE
    ) = TaskState(
        id = "prior-bitcoin",
        projectId = null,
        goal = goal,
        status = status,
        lastResult = "Це результат роботи мого інструменту пошуку в інтернеті."
    )

    private fun ask(
        previous: TaskState,
        prompt: String = "що це за результат?"
    ): AgentControlState = controller.initial(
        TaskState(
            id = "explain-bitcoin",
            projectId = null,
            goal = ShortTurnCue.EXPLAIN_GOAL,
            currentInstruction = prompt,
            status = TaskStatus.WAITING_MODEL,
            previousExecutionReceipt =
                PreviousTaskOutcomeContext.executionReceipt(previous)
        )
    )

    @Test fun screenshotQuestionIsRecognizedAsEvidenceQuestion() {
        assertTrue(ShortTurnCue.isExplain("що це за результат?"))
        assertTrue(ShortTurnCue.isExecutionEvidenceQuestion("що це за результат?"))
        assertTrue(ShortTurnCue.isExecutionEvidenceQuestion("Did you run the search?"))
        assertTrue(ShortTurnCue.isExecutionEvidenceQuestion("co to za wynik?"))
        assertFalse(ShortTurnCue.isExecutionEvidenceQuestion("що таке результат пошуку?"))
        assertFalse(ShortTurnCue.isExecutionEvidenceQuestion("що це за модель?"))
    }

    @Test fun realPhoneIncidentCannotTurnUnverifiedPriorSearchIntoFact() {
        val earlier = previous().copy(
            lastResult = "Виконано web.search(query=bitcoin price chart current)"
        )
        val state = ask(earlier)
        val snapshot = PreviousTaskOutcomeContext.snapshot(earlier)
        assertTrue(snapshot.contains("verified_tool_result_count=0"))
        assertTrue(snapshot.contains("model_text_is_not_execution_proof=true"))
        assertEquals(TaskIntent.GENERAL, state.intent)
        assertEquals(null, state.task.previousExecutionReceipt?.executedTools?.firstOrNull())

        val modelReply = "Це результат роботи мого інструменту пошуку в інтернеті. Я отримав дані з мережі."
        val result = controller.interpret(modelReply, state)
        assertTrue(result is ControllerInstruction.Finish)
        result as ControllerInstruction.Finish
        assertEquals(TaskStatus.DONE, result.state.task.status)
        assertTrue(result.text.contains("0 TOOL_RESULT"))
        assertTrue(result.text.contains("пропозиція виклику"))
        assertTrue(result.text.contains("expect_ok"))
        assertFalse(result.text.contains("Я отримав дані з мережі"))
        assertEquals(0, result.state.task.kernel.observed)
    }

    @Test fun fakeStructuredDoneCannotCertifyPriorToolExecution() {
        val result = controller.interpret(
            """{"done":true,"summary":"Я запустила web.search і знайшла ціну BTC."}""",
            ask(previous())
        ) as ControllerInstruction.Finish
        assertEquals(TaskStatus.DONE, result.state.task.status)
        assertTrue(result.text.contains("0 TOOL_RESULT"))
        assertFalse(result.text.contains("Я запустила"))
    }

    @Test fun proposedAnotherToolForEvidenceQuestionIsNeverExecuted() {
        val result = controller.interpret(
            """{"tool":"web.search","args":{"query":"bitcoin price chart current"},"reason":"redo"}""",
            ask(previous())
        )
        assertTrue(result is ControllerInstruction.Finish)
        assertTrue((result as ControllerInstruction.Finish).text.contains("0 TOOL_RESULT"))
        assertEquals(0, result.state.task.kernel.observed)
    }

    @Test fun actualRecordedSearchIsReportedAsExecutionButNotGoalSuccess() {
        val earlier = previous()
        val call = AgentDecision.ToolCall(
            "web.search",
            mapOf("query" to "bitcoin price chart current"),
            "Search"
        )
        val localKernel = ContextKernel.record(
            earlier.kernel, call, true,
            "ok=true stdout=results=3"
        )
        val state = ask(earlier.copy(kernel = localKernel))
        val result = controller.interpret(
            "Нічого не викликав", state
        ) as ControllerInstruction.Finish
        assertTrue(result.text.contains("web.search"))
        assertTrue(result.text.contains("1 результат"))
        assertTrue(result.text.contains("не автоматичний доказ"))
        assertFalse(result.text.contains("0 TOOL_RESULT"))
        assertEquals(0, result.state.task.kernel.observed)
    }

    @Test fun oldFailedToolMustNotBeCalledSuccessful() {
        val earlier = previous()
        val kernel = ContextKernel.record(
            earlier.kernel, AgentDecision.ToolCall("web.search"), false, "HTTP 429"
        )
        val result = controller.interpret(
            """{"reply":"Search succeeded"}""",
            ask(earlier.copy(kernel = kernel))
        ) as ControllerInstruction.Finish
        assertTrue(result.text.contains("успішних записів: 0"))
        assertTrue(result.text.contains("із 1 збережених"))
        assertFalse(result.text.contains("Search succeeded"))
    }

    @Test fun pendingMutationIsUnknownAndCannotBeImplicitlyReplayed() {
        val earlier = previous(status = TaskStatus.EXECUTING)
        val call = AgentDecision.ToolCall(
            "file.write", mapOf("path" to "data.txt", "content" to "x")
        )
        val kernel = ContextKernel.before(earlier.kernel, call)
        val result = controller.interpret(
            """{"tool":"file.write","args":{"path":"data.txt","content":"x"}}""",
            ask(earlier.copy(kernel = kernel))
        ) as ControllerInstruction.Finish
        assertTrue(result.text.contains("НЕВІДОМИМ результатом"))
        assertTrue(result.text.contains("без окремої перевірки"))
    }

    @Test fun ordinaryConversationIsNotOverridden() {
        val ordinary = controller.initial(
            TaskState(
                "ordinary", null, "Поясни різницю між RAM і SSD",
                status = TaskStatus.WAITING_MODEL
            )
        )
        val answer = controller.interpret(
            """{"reply":"RAM — оперативна пам'ять, SSD — накопичувач."}""",
            ordinary
        ) as ControllerInstruction.Finish
        assertEquals(TaskStatus.DONE, answer.state.task.status)
        assertTrue(answer.text.contains("SSD"))
        assertNull(answer.state.task.previousExecutionReceipt)
    }

    @Test fun conversationalExplanationWithNoPreviousReceiptStaysNormal() {
        val withoutPrior = controller.initial(
            TaskState(
                id = "question-no-history",
                projectId = null,
                goal = ShortTurnCue.EXPLAIN_GOAL,
                currentInstruction = "що це за результат?",
                status = TaskStatus.WAITING_MODEL
            )
        )
        assertNull(withoutPrior.task.previousExecutionReceipt)
        val reply = controller.interpret(
            """{"reply":"Не бачу попереднього результату в активній задачі."}""",
            withoutPrior
        ) as ControllerInstruction.Finish
        assertTrue(reply.text.contains("Не бачу"))
    }

    @Test fun receiptContainsOnlyAppOwnedFactsNotPreviousHallucination() {
        val earlier = previous().copy(lastTool = "web.search")
        val receipt = PreviousTaskOutcomeContext.executionReceipt(earlier)
        assertEquals(0, receipt.toolResultsObserved)
        assertTrue(receipt.executedTools.isEmpty())
        assertEquals(TaskStatus.DONE, receipt.previousStatus)
        assertNotNull(receipt.sourceTaskId)
    }
}
