package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class CompletionEvidenceTest {
    private val controller = AgentController()
    private fun executed(): AgentControlState {
        var state = controller.initial(TaskState("phone-recap", null,
            "Через system.time отримай час, через workspace.list покажи каталоги, через web.read прочитай https://example.com"))
        for ((tool, output) in listOf(
            "system.time" to "local_time=2026-09-26T21:57:04+02:00",
            "workspace.list" to "demo_project/\nbridge_inspect/",
            "web.read" to "Example Domain"
        )) {
            val call = AgentDecision.ToolCall(tool, if (tool == "web.read") mapOf("url" to "https://example.com") else emptyMap())
            state = controller.afterTool(state, call, true, output, "", null).state
        }
        return state
    }

    @Test fun doneCannotDenyRecordedToolExecution() {
        val result = controller.interpret("""{"done":true,"summary":"Не виконано: інструмент workspace.list не був викликаний у попередніх кроках."}""", executed())
        assertTrue(result is ControllerInstruction.AskModelAgain)
    }

    @Test fun publicWebPlainReplyCannotBypassExecutionConsistency() {
        val state = executed().copy(intent = TaskIntent.PUBLIC_WEB)
        val result = controller.interpret("system.time не було викликано окремо. workspace.list was not called.", state)
        assertTrue(result is ControllerInstruction.AskModelAgain)
    }

    @Test fun protocolRepairRestoresAllResultsNotOnlyLastTool() {
        val result = controller.interpret("""{"unexpected":"shape"}""", executed()) as ControllerInstruction.AskModelAgain
        assertTrue(result.feedback.contains("local_time=2026-09-26T21:57:04"))
        assertTrue(result.feedback.contains("demo_project/"))
        assertTrue(result.feedback.contains("Example Domain"))
    }
    @Test fun repeatedContradictionEndsPartialWithoutSpendingToolOrProtocolBudget() {
        val before = executed()
        val first = controller.interpret("workspace.list was not called", before) as ControllerInstruction.AskModelAgain
        assertEquals(before.task.step, first.state.task.step)
        assertEquals(before.protocolRetries, first.state.protocolRetries)
        assertEquals(before.task.kernel, first.state.task.kernel)
        val second = controller.interpret("workspace.list was not called", first.state) as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, second.state.task.status)
        assertFalse(second.text.contains("was not called"))
        assertTrue(second.text.contains("workspace.list executed=true"))
    }

    @Test fun correctedReportFinishesFromExistingEvidence() {
        val first = controller.interpret("workspace.list was not called", executed()) as ControllerInstruction.AskModelAgain
        val corrected = controller.interpret("""{"done":true,"summary":"system.time: 21:57:04; workspace.list: demo_project, bridge_inspect; web.read: Example Domain"}""", first.state) as ControllerInstruction.Finish
        assertEquals(TaskStatus.DONE, corrected.state.task.status)
        assertEquals(first.state.task.step, corrected.state.task.step)
    }

    @Test fun correctCaveatsAndOtherMissingToolsAreNotContradictions() {
        val state = executed()
        for (text in listOf("Do not repeat workspace.list", "file.read was not called", "system.time було викликано", "Не потрібно повторно викликати workspace.list")) {
            assertTrue(TaskExecutionRecap.contradictions(text, state).isEmpty())
        }
    }

    @Test fun partialCannotSilentlyDenyRecordedExecutionEither() {
        assertTrue(controller.interpret("""{"partial":true,"summary":"workspace.list was not executed"}""", executed()) is ControllerInstruction.AskModelAgain)
    }

    @Test fun supportedDenialsAreLocalToTheNamedTool() {
        val state = executed()
        for (text in listOf("workspace.list не вызывался", "workspace.list nie został wywołany", "I did not call workspace.list")) {
            assertEquals(listOf("workspace.list"), TaskExecutionRecap.contradictions(text, state))
        }
    }

}
