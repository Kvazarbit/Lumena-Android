package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentController
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

class WorkflowRunnerConstitutionTest {
    @Test
    fun knownActionToolEnvelopeExecutesBeforeAnyCorrectionTurn() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                return when (modelCalls.incrementAndGet()) {
                    1 -> Result.success(
                        """{"action":"web_search","parameters":{"query":"latest news","limit":3}}"""
                    )
                    else -> Result.success(
                        """{"partial":true,"summary":"Fixture stops after verified tool execution."}"""
                    )
                }
            }
        }

        val toolCalls = mutableListOf<ToolRequest>()
        val modelCallsObservedAtTool = mutableListOf<Int>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                toolCalls += toolRequest
                modelCallsObservedAtTool += modelCalls.get()
                return ToolResult(
                    ok = true,
                    tool = toolRequest.tool,
                    exitCode = 0,
                    stdout = """{"provider":"fixture","results":[{"title":"Example","url":"https://example.org"}]}"""
                )
            }
        }

        val task = TaskState(
            id = "protocol-normalizer-e2e",
            projectId = null,
            goal = "Виконай контрольований protocol compatibility test",
            status = TaskStatus.WAITING_MODEL
        )
        val initial = AgentController()
            .initial(task)
            .copy(preflightCompleted = true)

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = bridge,
            model = "fixture"
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task,
            control = initial
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.PARTIAL, outcome.control.task.status)
        assertEquals(2, modelCalls.get())
        assertEquals(1, toolCalls.size)
        assertEquals("web.search", toolCalls.single().tool)
        assertEquals("latest news", toolCalls.single().args["query"])
        assertEquals(listOf(1), modelCallsObservedAtTool)
        assertEquals(0, outcome.control.protocolRetries)
        assertEquals(1, outcome.control.protocolNormalizations)
    }

    @Test
    fun unknownActionNeverExecutesSmuggledMutation() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                modelCalls.incrementAndGet()
                return Result.success(
                    """{"action":"shell","tool":"file.write","args":{"path":"x","content":"bad"}}"""
                )
            }
        }

        val toolCalls = mutableListOf<ToolRequest>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                toolCalls += toolRequest
                return ToolResult(
                    ok = true,
                    tool = toolRequest.tool,
                    exitCode = 0,
                    stdout = "unexpected"
                )
            }
        }

        val task = TaskState(
            id = "protocol-normalizer-unsafe",
            projectId = null,
            goal = "Виконай контрольований protocol safety test",
            status = TaskStatus.WAITING_MODEL
        )
        val initial = AgentController()
            .initial(task)
            .copy(preflightCompleted = true)

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = bridge,
            model = "fixture"
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task,
            control = initial
        )

        assertTrue(outcome is WorkflowOutcome.Failed)
        assertTrue(toolCalls.isEmpty())
        assertTrue(modelCalls.get() >= 1)
    }
}
