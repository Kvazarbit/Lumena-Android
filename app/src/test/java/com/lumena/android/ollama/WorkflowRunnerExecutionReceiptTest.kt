package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentController
import com.lumena.android.agent.core.PreviousTaskOutcomeContext
import com.lumena.android.agent.core.ShortTurnCue
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRunnerExecutionReceiptTest {
    @Test
    fun answersPriorBitcoinSearchQuestionWithoutInvokingModelOrTool() = runBlocking {
        val prior = TaskState(
            id = "bitcoin-old",
            projectId = null,
            goal = "Знайди графік Bitcoin",
            status = TaskStatus.DONE,
            lastResult = "Я отримала дані з інтернету",
            lastTool = "web.search"
        )
        val current = TaskState(
            id = "bitcoin-explain",
            projectId = null,
            goal = ShortTurnCue.EXPLAIN_GOAL,
            currentInstruction = "що це за результат?",
            status = TaskStatus.WAITING_MODEL,
            previousExecutionReceipt =
                PreviousTaskOutcomeContext.executionReceipt(prior)
        )
        var modelCalls = 0
        var toolCalls = 0
        val fakeModel = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                modelCalls++
                return Result.success("""{"done":true,"summary":"Я знайшла ціну BTC."}""")
            }
        }
        val fakeBridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                toolCalls++
                return ToolResult(
                    ok = true,
                    tool = toolRequest.tool,
                    exitCode = 0,
                    stdout = "fake output"
                )
            }
        }

        val outcome = WorkflowRunner(
            modelClient = fakeModel,
            bridge = fakeBridge,
            model = "fake-model",
            controller = AgentController()
        ).run(
            history = listOf(OllamaMessage("user", current.currentInstruction)),
            task = current
        ) as WorkflowOutcome.Finished

        assertEquals(TaskStatus.DONE, outcome.control.task.status)
        assertEquals(0, modelCalls)
        assertEquals(0, toolCalls)
        assertTrue(outcome.text.contains("0 TOOL_RESULT"))
        assertTrue(outcome.text.contains("прогнози"))
        assertTrue(outcome.history.last().content.contains("0 TOOL_RESULT"))
    }
}
