package com.lumena.android.ollama

import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRunnerFailureCircuitTest {
    private class CountingModel(
        private val reply: String = """{"reply":"should not be called"}"""
    ) : ChatModelClient {
        var calls: Int = 0

        override suspend fun chat(
            model: String,
            messages: List<OllamaMessage>
        ): Result<String> {
            calls += 1
            return Result.success(reply)
        }

        override suspend fun chatStreaming(
            model: String,
            messages: List<OllamaMessage>,
            onPartial: (String) -> Unit
        ): Result<String> {
            calls += 1
            onPartial(reply)
            return Result.success(reply)
        }
    }

    @Test
    fun failedMandatoryWebPreflightNeverCallsModel() = runBlocking {
        val model = CountingModel()
        var toolCalls = 0
        val bridge = ToolExecutor { request ->
            toolCalls += 1
            ToolResult(
                ok = false,
                tool = request.tool,
                exitCode = 1,
                stdout = """{"attempts":[{"provider":"duckduckgo","error":"HTTP 202"}]}""",
                error = "Search unavailable. Do not invent current facts."
            )
        }
        val goal = "знайди останні новини в інтернеті"
        val task = TaskState(
            id = "web-preflight-fail",
            projectId = null,
            goal = goal,
            status = TaskStatus.WAITING_MODEL
        )
        val runner = WorkflowRunner(model, bridge, "fake")

        val outcome = runner.run(
            history = listOf(
                OllamaMessage("system", "system"),
                OllamaMessage("user", goal)
            ),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Failed)
        assertEquals(1, toolCalls)
        assertEquals(0, model.calls)
        val failed = outcome as WorkflowOutcome.Failed
        assertTrue(failed.message.contains("automatic model retry stopped"))
        assertEquals(TaskStatus.FAILED, failed.control.task.status)
    }

    @Test
    fun failedModelRequestedWebSearchDoesNotCallModelAgain() = runBlocking {
        val model = CountingModel(
            """{"tool":"web.search","args":{"query":"example current sources"},"reason":"find sources"}"""
        )
        var toolCalls = 0
        val bridge = ToolExecutor { request ->
            toolCalls += 1
            ToolResult(
                ok = false,
                tool = request.tool,
                exitCode = 1,
                error = "Search unavailable. Configured providers exhausted."
            )
        }
        val goal = "перевір https://example.com і знайди актуальні джерела"
        val task = TaskState(
            id = "web-model-fail",
            projectId = null,
            goal = goal,
            status = TaskStatus.WAITING_MODEL
        )
        val runner = WorkflowRunner(model, bridge, "fake")

        val outcome = runner.run(
            history = listOf(
                OllamaMessage("system", "system"),
                OllamaMessage("user", goal)
            ),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Failed)
        assertEquals(1, toolCalls)
        assertEquals(1, model.calls)
        val failed = outcome as WorkflowOutcome.Failed
        assertTrue(failed.message.contains("automatic model retry stopped"))
    }
}
