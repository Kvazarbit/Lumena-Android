package com.lumena.android.ollama

import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRunnerTest {
    @Test
    fun failedMandatoryWebPreflightStopsBeforeAnyModelTurn() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls.incrementAndGet()
                return Result.success("""{"done":true,"summary":"should never run"}""")
            }
        }

        val requests = mutableListOf<ToolRequest>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                requests += toolRequest
                return ToolResult(
                    ok = false,
                    tool = toolRequest.tool,
                    exitCode = 1,
                    stdout = """{"query":"latest news","results":[],"attempts":[{"provider":"duckduckgo","error":"Upstream HTTP 202"}]}""",
                    error = "Search unavailable. Do not invent current facts."
                )
            }
        }

        val task = TaskState(
            id = "web-loop-regression",
            projectId = null,
            goal = "Знайди останні новини в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = bridge,
            model = "fixture"
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Failed)
        outcome as WorkflowOutcome.Failed
        assertEquals(0, modelCalls.get())
        assertEquals(1, requests.size)
        assertEquals("web.search", requests.single().tool)
        assertTrue(outcome.message.contains("automatic retry stopped"))
        assertEquals(TaskStatus.FAILED, outcome.control.task.status)
    }

    @Test
    fun successfulWebPreflightStillAllowsExactlyOneModelTurn() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls.incrementAndGet()
                return Result.success("""{"partial":true,"summary":"Search evidence received; source reading omitted in fixture."}""")
            }
        }

        val requests = mutableListOf<ToolRequest>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                requests += toolRequest
                return ToolResult(
                    ok = true,
                    tool = toolRequest.tool,
                    exitCode = 0,
                    stdout = """{"results":[{"title":"Example","url":"https://example.org"}]}"""
                )
            }
        }

        val task = TaskState(
            id = "web-success-regression",
            projectId = null,
            goal = "Знайди новини в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(modelClient, bridge, "fixture").run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        assertEquals(1, modelCalls.get())
        assertEquals(1, requests.size)
        assertEquals("web.search", requests.single().tool)
    }

    @Test
    fun exhaustedContextFailureStopsAfterOneModelAttempt() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls.incrementAndGet()
                return Result.failure(
                    IllegalStateException("Ollama stream error: context length exceeded; too many tokens")
                )
            }
        }

        val task = TaskState(
            id = "context-stop-regression",
            projectId = null,
            goal = "Поясни коротко стан системи",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(modelClient, null, "fixture").run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Failed)
        assertEquals(1, modelCalls.get())
        outcome as WorkflowOutcome.Failed
        assertEquals(TaskStatus.FAILED, outcome.control.task.status)
    }
}
