package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression fixture from owner phone ci2260; model prose is not a TOOL_RESULT. */
class BtcExecutionFalseDoneRegressionTest {
    private val controller = AgentController()

    private fun initial(goal: String) = controller.initial(
        TaskState(
            id = "ci2260-btc",
            projectId = null,
            goal = goal,
            currentInstruction = goal,
            status = TaskStatus.WAITING_MODEL
        )
    )

    @Test
    fun concreteRunOfBtcFilesRequiresExecutableReceipt() {
        val state = initial("запусти нові файли BTC")
        assertEquals(TaskIntent.CODE_WORK, state.intent)
        assertEquals(GoalContractCoverage.TYPED_OPERATIONAL_V1, state.task.goalContract.coverage)
        assertTrue(state.task.goalContract.criteria.any {
            it.id == "operational-tool-evidence" && it.status == CriterionStatus.PENDING
        })
        assertTrue(state.task.goalContract.criteria.any {
            it.id == "code-action-evidence" &&
                it.subject == "executable" && it.status == CriterionStatus.PENDING
        })
        assertEquals(0, state.task.kernel.observed)
    }

    @Test
    fun exactCi2260AnnotatedPseudoWriteCannotFinishOrExecute() {
        val state = initial("запусти нові файли BTC")
        val reply = controller.interpret(
            """expect_ok: true confidence: 0.9 file.write(path="download_btc_2017_2023.py", content="print('btc')")""",
            state
        )
        assertTrue(reply is ControllerInstruction.AskModelAgain)
        assertEquals(TaskStatus.WAITING_MODEL, reply.state.task.status)
        assertFalse(reply.state.toolUsed)
        assertEquals(0, reply.state.task.step)
        assertEquals(0, reply.state.task.kernel.observed)
        assertTrue(reply.state.task.kernel.evidence.isEmpty())
        assertTrue(GoalContractPolicy.incompleteMandatory(reply.state.task.goalContract).isNotEmpty())
    }

    @Test
    fun fabricatedDoneWithoutReceiptsIsRejected() {
        val state = initial("запусти нові файли BTC")
        val result = controller.interpret(
            """{"done":true,"summary":"Запустила нові BTC файли."}""",
            state
        )
        assertTrue(result is ControllerInstruction.AskModelAgain)
        assertTrue(result.state.task.status != TaskStatus.DONE)
    }

    @Test
    fun readOnlyObservationDoesNotProveExecution() {
        val state = initial("запусти нові файли BTC")
        val observed = controller.afterTool(
            state = state,
            call = AgentDecision.ToolCall("workspace.list", emptyMap(), "Find files"),
            ok = true, stdout = "download_btc_2017_2023.py", stderr = "", error = null
        ).state
        assertTrue(GoalContractPolicy.incompleteMandatory(observed.task.goalContract)
            .any { it.id == "code-action-evidence" })
        val result = controller.interpret(
            """{"done":true,"summary":"BTC скрипт виконано."}""",
            observed
        )
        assertTrue(result is ControllerInstruction.AskModelAgain)
        assertTrue(result.state.task.status != TaskStatus.DONE)
    }

    @Test
    fun staleGeneralClassificationIsStillRejectedByExecutionGate() {
        val initial = initial("запусти нові файли BTC")
        val stale = initial.copy(
            intent = TaskIntent.GENERAL,
            task = initial.task.copy(goalContract = GoalContract())
        )
        val result = controller.interpret(
            """{"done":true,"summary":"Задачу виконано."}""",
            stale
        )
        assertTrue(result is ControllerInstruction.AskModelAgain)
        assertTrue(result.state.task.status != TaskStatus.DONE)
    }

    @Test
    fun annotatedFunctionCannotFinishEvenAnUnrelatedGeneralConversation() {
        val state = initial("акваріум")
        assertEquals(TaskIntent.GENERAL, state.intent)
        val reply = controller.interpret(
            """expect_ok: true confidence: 0.9 file.write(path="x.py", content="ok")""",
            state
        )
        assertTrue(reply is ControllerInstruction.AskModelAgain)
        assertFalse(reply.state.toolUsed)
    }

    @Test
    fun multipleLanguagesSupportedWithNarrowNegationAndExplanationBoundary() {
        listOf(
            "запусти нові файли BTC", "Запустіть нові скрипти BTC",
            "будь ласка, запусти файли BTC", "run new BTC files",
            "uruchom nowe pliki BTC"
        ).forEach { goal ->
            assertTrue(goal, TaskIntentRouter.isExplicitFileExecution(goal))
            assertEquals(goal, TaskIntent.CODE_WORK, TaskIntentRouter.route(goal).intent)
            assertEquals("executable", GoalContractPolicy.initial(
                intent = TaskIntent.CODE_WORK, requiredTools = emptySet(),
                visualRequired = false, goal = goal
            ).criteria.first { it.id == "code-action-evidence" }.subject)
        }
        listOf("Не запускай нові файли BTC",
            "Поясни, як запустити нові файли BTC",
            "розкажи що означає файл", "запусти тести"
        ).forEach { goal ->
            assertFalse(goal, TaskIntentRouter.isExplicitFileExecution(goal))
        }
    }

    @Test
    fun explanationOfSyntaxRemainsValidWithoutRealToolWork() {
        val state = initial("поясни синтаксис")
        val reply = controller.interpret(
            """Наприклад, file.write(path="x.py", content="ok") означає запис файлу.""",
            state
        )
        assertTrue(reply is ControllerInstruction.Finish)
        assertEquals(TaskStatus.DONE, reply.state.task.status)
        assertFalse(reply.state.toolUsed)
    }
}
