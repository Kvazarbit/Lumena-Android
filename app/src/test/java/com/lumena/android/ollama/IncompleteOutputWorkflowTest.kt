package com.lumena.android.ollama

import com.lumena.android.agent.core.*
import com.lumena.android.agent.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncompleteOutputWorkflowTest {
    @Test fun truncatedGenerationRegeneratesShortCallButStillRequiresWriteApproval() = runBlocking {
        val calls = mutableListOf<String>()
        val states = mutableListOf<AgentControlState>()
        var modelCalls = 0
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                calls += toolRequest.tool
                return ToolResult(true, stdout = "workspace ready")
            }
        }
        val model = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls++
                if (modelCalls == 1) return Result.failure(ModelOutputIncompleteException("length", 768))
                assertTrue(messages.any { it.content.contains("Regenerate exactly ONE short complete JSON") })
                return Result.success("""{"tool":"file.write","args":{"path":"aquarium.html","content":"<!doctype html><title>Aquarium</title>"}}""")
            }
        }
        val outcome = WorkflowRunner(model, bridge, "fixture").run(
            history = listOf(OllamaMessage("user", "Create HTML aquarium")),
            task = TaskState("html", null, "Create HTML aquarium"),
            onState = { states += it }
        ) as WorkflowOutcome.NeedsConfirmation
        assertEquals(2, modelCalls)
        assertEquals(listOf("context.snapshot"), calls)
        assertEquals("file.write", outcome.pending.plan.request.tool)
        assertTrue(states.any { it.protocolRetries == 1 && it.task.status == TaskStatus.WAITING_MODEL })
        // Consecutive protocol retries reset after a valid tool envelope, while
        // the observed truncation remains recorded for diagnostics.
        assertEquals(0, outcome.pending.control.protocolRetries)
        assertTrue(outcome.pending.control.task.errors.any { it.contains("MODEL_OUTPUT_INCOMPLETE") })
    }

    @Test fun repeatedTruncationHasBoundedRecoveryAndExecutesNothing() = runBlocking {
        var modelCalls = 0
        val model = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls++
                return Result.failure(ModelOutputIncompleteException("length"))
            }
        }
        val outcome = WorkflowRunner(model, null, "fixture").run(
            history = emptyList(), task = TaskState("small", null, "hello")
        ) as WorkflowOutcome.Failed
        assertTrue(modelCalls in 2..4)
        assertEquals(0, outcome.control.task.kernel.observed)
        assertEquals(TaskStatus.FAILED, outcome.control.task.status)
    }
}
