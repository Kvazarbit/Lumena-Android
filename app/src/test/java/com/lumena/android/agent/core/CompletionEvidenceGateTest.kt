package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionEvidenceGateTest {
    private val controller = AgentController()

    private fun initial(goal: String, instruction: String = goal) =
        controller.initial(
            TaskState(
                id = "evidence-gate",
                projectId = null,
                goal = goal,
                currentInstruction = instruction,
                status = TaskStatus.WAITING_MODEL
            )
        )

    @Test fun standaloneShowDoesNotFinishOnInventedDone() {
        val state = initial("покажи!")
        assertEquals(TaskIntent.GENERAL, state.intent)
        assertEquals(GoalContractCoverage.NONE, state.task.goalContract.coverage)
        val outcome = controller.interpret(
            """{"done":true,"summary":"Ось фотографія — усе готово."}""",
            state
        ) as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, outcome.state.task.status)
        assertTrue(outcome.text.contains("що саме показати"))
        assertEquals(0, outcome.state.task.kernel.observed)
        assertFalse(outcome.text.contains("усе готово"))
    }

    @Test fun standaloneShowCannotCompleteThroughPlainReply() {
        val outcome = controller.interpret(
            """{"reply":"Я вже все показала."}""",
            initial("покажи!")
        ) as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, outcome.state.task.status)
        assertTrue(outcome.text.contains("що саме показати"))
        assertFalse(outcome.text.contains("все показала"))
    }

    @Test fun unrelatedSuccessfulToolCannotResolveMissingShowTarget() {
        val task = initial("покажи!")
        val after = controller.afterTool(
            state = task,
            call = AgentDecision.ToolCall(
                tool = "system.time", args = emptyMap(), reason = "probe"
            ),
            ok = true,
            stdout = "local_time=2026-10-14T14:00:00+02:00",
            stderr = "",
            error = null
        ).state
        assertEquals(1, after.task.kernel.observed)
        assertTrue(CompletionEvidenceGate.hasSuccessfulLocalToolResult(after))

        val outcome = controller.interpret(
            """{"done":true,"summary":"Показано."}""",
            after
        ) as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, outcome.state.task.status)
        assertTrue(outcome.text.contains("що саме показати"))
    }

    @Test fun showFollowUpUsesExistingConcreteVisualGoal() {
        val state = initial(
            goal = "Знайди фото кота в інтернеті і покажи",
            instruction = "покажи!"
        )
        assertEquals(TaskIntent.VISUAL_SEARCH, state.intent)
        assertFalse(CompletionEvidenceGate.hasUnresolvedShowTarget(state))
        assertTrue(state.task.goalContract.criteria.any {
            it.id == "visual-evidence:image.search"
        })

        val tooEarly = controller.interpret(
            """{"done":true,"summary":"Ось фото кота."}""",
            state
        )
        assertTrue(tooEarly is ControllerInstruction.AskModelAgain)

        val updated = controller.afterTool(
            state = state,
            call = AgentDecision.ToolCall(
                tool = "image.search",
                args = mapOf("query" to "фото кота"),
                reason = "Get visual evidence"
            ),
            ok = true,
            stdout = """{"display_ready":true,"images":[{"title":"Cat","thumbnail_url":"https://example.org/cat.jpg"}]}""",
            stderr = "",
            error = null
        ).state
        assertTrue(updated.visualEvidenceReady)
        assertTrue(GoalContractPolicy.allMandatoryPassed(updated.task.goalContract))
        val finished = controller.interpret(
            """{"done":true,"summary":"Отримано фото кота."}""",
            updated
        ) as ControllerInstruction.Finish
        assertEquals(TaskStatus.DONE, finished.state.task.status)
    }

    @Test fun bareExecutionReplyWithoutToolIsPartialInsteadOfDone() {
        val outcome = controller.interpret(
            """{"reply":"Я реалізувала все."}""",
            initial("реалізуй")
        ) as ControllerInstruction.Finish
        assertEquals(TaskStatus.PARTIAL, outcome.state.task.status)
        assertTrue(outcome.text.contains("Задачу не виконано"))
        assertFalse(outcome.text.contains("реалізувала все"))
    }

    @Test fun modelFlagOrConfidentSummaryIsNotKernelEvidence() {
        val withFlagOnly = initial("зроби").copy(toolUsed = true)
        assertFalse(CompletionEvidenceGate.hasSuccessfulLocalToolResult(withFlagOnly))
        val outcome = controller.interpret(
            """{"done":true,"summary":"Роботу виконано й перевірено."}""",
            withFlagOnly
        ) as ControllerInstruction.AskModelAgain
        assertTrue(outcome.feedback.contains("NO_VERIFIED_TOOL_RESULT"))
        assertTrue(outcome.state.task.status != TaskStatus.DONE)
    }

    @Test fun ordinaryConversationalAnswersNeedNoTool() {
        val state = initial("скільки буде 2+2?")
        val outcome = controller.interpret(
            """{"done":true,"summary":"2+2 = 4."}""",
            state
        ) as ControllerInstruction.Finish
        assertEquals(TaskStatus.DONE, outcome.state.task.status)
    }

    @Test fun bareShowDetectionIsNarrowAndMultiLingual() {
        listOf("покажи!", "Покажи мені", "show me", "pokaż mi!").forEach {
            assertTrue(it, ShortTurnCue.isBareShowRequest(it))
            assertTrue(it, ShortTurnCue.isExecutionDirective(it))
        }
        listOf(
            "знайди фото кота і покажи",
            "покажи фото собаки",
            "поясни як показати фото",
            "покажи як змінити налаштування"
        ).forEach {
            assertFalse(it, ShortTurnCue.isBareShowRequest(it))
        }
    }
}
