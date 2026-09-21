package com.lumena.android.ollama

import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WorkflowRunnerWebFailureTest {
    private class CountingModelClient : ChatModelClient {
        val calls = AtomicInteger(0)

        override suspend fun chat(
            model: String,
            messages: List<OllamaMessage>
        ): Result<String> {
            calls.incrementAndGet()
            return Result.success("""{"done":true,"summary":"should not be reached"}""")
        }
    }

    private class FailingSearchExecutor : ToolExecutor {
        val calls = AtomicInteger(0)

        override suspend fun execute(toolRequest: ToolRequest): ToolResult {
            calls.incrementAndGet()
            return ToolResult(
                ok = false,
                tool = toolRequest.tool,
                exitCode = 1,
                error = "Search unavailable: Upstream HTTP 202"
            )
        }
    }

    @Test
    fun failedMandatoryWebPreflightStopsBeforeAnyModelGeneration() {
        val model = CountingModelClient()
        val bridge = FailingSearchExecutor()
        val task = TaskState(
            id = "web-fail",
            projectId = null,
            goal = "Find latest world news on the internet",
            status = TaskStatus.WAITING_MODEL
        )
        val runner = WorkflowRunner(
            modelClient = model,
            bridge = bridge,
            model = "test-model"
        )

        val outcome = runBlocking {
            runner.run(
                history = listOf(
                    OllamaMessage("system", "test system"),
                    OllamaMessage("user", task.goal)
                ),
                task = task
            )
        }

        assertTrue(outcome is WorkflowOutcome.Failed)
        outcome as WorkflowOutcome.Failed
        assertTrue(
            "unexpected failure: ${outcome.message}",
            outcome.message.contains("automatic model retry suppressed")
        )
        assertEquals(TaskStatus.FAILED, outcome.control.task.status)
        assertEquals("web search should execute exactly once", 1, bridge.calls.get())
        assertEquals("failed search must consume zero model calls", 0, model.calls.get())
    }
}
