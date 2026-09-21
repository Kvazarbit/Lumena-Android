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
    fun failedMandatoryWebPreflightAllowsOneVariantThenDegradesPartial() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls.incrementAndGet()
                return Result.success(
                    """{"tool":"web.search","args":{"query":"latest world headlines"},"reason":"one meaningful variant"}"""
                )
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
                    error = "Search unavailable. Do not invent current facts.",
                    errorCode = "SEARCH_EXHAUSTED",
                    failureClass = "DEPENDENCY_EXHAUSTED",
                    retryable = false,
                    dependency = "web.search"
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

        assertTrue(outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.PARTIAL, outcome.control.task.status)
        assertEquals(1, modelCalls.get())
        assertEquals(2, requests.size)
        assertEquals("web.search", requests[0].tool)
        assertEquals("web.search", requests[1].tool)
        assertEquals(2, outcome.control.semanticRecoverySpent)
        assertEquals(2, outcome.control.actionFamilyFailures["web.search"])
    }

    @Test
    fun failedSearchCanSwitchToAlternativeEvidenceTool() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                return when (modelCalls.incrementAndGet()) {
                    1 -> Result.success(
                        """{"tool":"http.get","args":{"url":"https://example.org"},"reason":"alternate evidence path"}"""
                    )
                    else -> Result.success(
                        """{"partial":true,"summary":"Alternative source was reachable; broader live search remained unavailable."}"""
                    )
                }
            }
        }

        val requests = mutableListOf<ToolRequest>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                requests += toolRequest
                return if (toolRequest.tool == "web.search") {
                    ToolResult(
                        ok = false,
                        tool = toolRequest.tool,
                        exitCode = 1,
                        error = "Search unavailable",
                        errorCode = "SEARCH_EXHAUSTED",
                        failureClass = "DEPENDENCY_EXHAUSTED",
                        retryable = false,
                        dependency = "web.search"
                    )
                } else {
                    ToolResult(
                        ok = true,
                        tool = toolRequest.tool,
                        exitCode = 0,
                        stdout = "Example source body"
                    )
                }
            }
        }

        val task = TaskState(
            id = "web-alt",
            projectId = null,
            goal = "Знайди актуальну інформацію в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(modelClient, bridge, "fixture").run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.PARTIAL, outcome.control.task.status)
        assertEquals(2, modelCalls.get())
        assertEquals(listOf("web.search", "http.get"), requests.map { it.tool })
        assertEquals(1, outcome.control.semanticRecoverySpent)
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
    @Test
    fun modelRuntimeRecoveryAndToolRecoveryUseIndependentBudgets() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                return when (modelCalls.incrementAndGet()) {
                    1 -> Result.failure(IllegalStateException("temporary model transport failure"))
                    2 -> Result.success(
                        """{"tool":"file.read","args":{"path":"missing.txt"},"reason":"inspect requested file"}"""
                    )
                    else -> Result.success(
                        """{"partial":true,"summary":"The file could not be verified after bounded recovery."}"""
                    )
                }
            }
        }

        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult =
                ToolResult(
                    ok = false,
                    tool = toolRequest.tool,
                    exitCode = 1,
                    error = "FileNotFoundError: No such file",
                    failureClass = "STATE_DRIFT",
                    retryable = false,
                    dependency = "filesystem"
                )
        }

        val task = TaskState(
            id = "budget-separation",
            projectId = null,
            goal = "Виконай контрольовану перевірку інструментом",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(modelClient, bridge, "fixture").run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.PARTIAL, outcome.control.task.status)
        assertEquals(3, modelCalls.get())
        assertEquals(0, outcome.control.modelFailures)
        assertEquals(1, outcome.control.semanticRecoverySpent)
        assertEquals(1, outcome.control.actionFamilyFailures["file.read"])
    }

}
