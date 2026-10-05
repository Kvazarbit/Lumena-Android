package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the aquarium incident: the assistant announced
 * "Починаю оновлення коду aquarium.html", the user wrote "реалізуй",
 * and the runtime showed "Task complete." with zero executed tools.
 */
class NoEvidenceCompletionTest {
    private val controller = AgentController()

    private fun bareTask(instruction: String, goal: String = instruction) =
        controller.initial(
            TaskState(
                id = "bare-$instruction",
                projectId = null,
                goal = goal,
                currentInstruction = instruction,
                status = TaskStatus.WAITING_MODEL
            )
        )

    @Test fun bareExecutionDirectiveCannotCompleteWithoutTools() {
        val state = bareTask("реалізуй")
        val result = controller.interpret(
            """{"done":true,"summary":"Task complete."}""",
            state
        )
        assertTrue(result is ControllerInstruction.AskModelAgain)
        result as ControllerInstruction.AskModelAgain
        assertTrue(result.feedback.contains("NO_EXECUTION_EVIDENCE"))
        assertEquals(TaskStatus.WAITING_MODEL, result.state.task.status)
    }

    @Test fun parserDefaultSummaryWithoutToolsIsNotACompletion() {
        val state = bareTask("розкажи про рибок")
        val result = controller.interpret("""{"done":true}""", state)
        assertTrue(result is ControllerInstruction.AskModelAgain)
        assertTrue(
            (result as ControllerInstruction.AskModelAgain)
                .feedback.contains("NO_EXECUTION_EVIDENCE")
        )
    }

    @Test fun conversationalDoneWithRealAnswerStillFinishes() {
        val state = bareTask("скільки буде 2+2")
        val result = controller.interpret(
            """{"done":true,"summary":"2+2 = 4."}""",
            state
        )
        assertTrue(result is ControllerInstruction.Finish)
    }

    @Test fun honestReplyToBareDirectiveIsAllowed() {
        val state = bareTask("реалізуй")
        val result = controller.interpret(
            """{"reply":"Немає активного плану. Що саме реалізувати?"}""",
            state
        )
        assertTrue(result is ControllerInstruction.Finish)
        result as ControllerInstruction.Finish
        assertFalse(result.text.contains("Task complete"))
    }

    @Test fun cuesAreClassifiedNarrowly() {
        listOf("реалізуй", "Реалізуй це", "зроби", "do it", "go ahead", "zrób to", "давай!")
            .forEach { assertTrue(it, ShortTurnCue.isExecutionDirective(it)) }
        listOf("реалізуй пошук новин про Python і порівняй", "зробити звіт", "так")
            .forEach { assertFalse(it, ShortTurnCue.isExecutionDirective(it)) }

        listOf("?", "??", "що?", "шо", "а?", "і що?", "what?", "co?", "що далі?")
            .forEach { assertTrue(it, ShortTurnCue.isExplain(it)) }
        listOf("що таке fly hunter?", "так", "реалізуй", "?!? чому ти зупинився на кроці 3")
            .forEach { assertFalse(it, ShortTurnCue.isExplain(it)) }
    }

    @Test fun explainGoalStaysGeneralWithoutWebPreflight() {
        val profile = TaskIntentRouter.route(ShortTurnCue.EXPLAIN_GOAL)
        assertEquals(TaskIntent.GENERAL, profile.intent)
        assertEquals(null, profile.preflight)
    }

    @Test fun previousTaskSnapshotCarriesGoalStatusAndEvidenceCount() {
        val task = TaskState(
            id = "aquarium-1",
            projectId = "aquarium",
            goal = "Онови aquarium.html: Fly hunter",
            status = TaskStatus.PARTIAL,
            lastResult = "patched aquarium.html"
        )
        val text = PreviousTaskOutcomeContext.snapshot(task)
        assertTrue(text.contains("status=PARTIAL"))
        assertTrue(text.contains("aquarium.html: Fly hunter"))
        assertTrue(text.contains("tool_steps=0 recorded"))
        assertTrue(text.contains("HISTORICAL_ONLY"))
    }
}
