package com.lumena.android.ollama

import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkflowRunnerCollaborationAdapterTest {
    @Test fun ordinaryReadReachesAdapterAfterOwnerTaskBinding() = runBlocking {
        var turns = 0
        val model = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>) = Result.success(
                if (turns++ == 0) """{"tool":"file.read","args":{"path":"notes.txt"},"reason":"read"}"""
                else """{"partial":true,"summary":"read fixture"}""")
        }
        val calls = mutableListOf<String>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult { calls += toolRequest.tool; return ToolResult(true, stdout = "fixture") }
        }
        val adapted = mutableListOf<String>()
        WorkflowRunner(model, bridge, "fixture", executionAdapter = { task, delegate ->
            assertEquals("task-owned", task.id)
            object : ToolExecutor {
                override suspend fun execute(toolRequest: ToolRequest): ToolResult { adapted += toolRequest.tool; return delegate.execute(toolRequest) }
            }
        }).run(listOf(OllamaMessage("user", "Прочитай notes.txt")), TaskState("task-owned", "project-a", "Прочитай notes.txt"))
        assertTrue(calls.contains("file.read"))
        assertEquals(calls, adapted)
    }
    @Test fun mutationAdapterCannotBypassConfirmationAndCoversApprovedExecution() = runBlocking {
        var turns = 0
        val model = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>) = Result.success(
                if (turns++ == 0) """{"tool":"file.write","args":{"path":"notes.txt","content":"fixture"},"reason":"write"}"""
                else """{"partial":true,"summary":"write receipt only"}""")
        }
        val calls = mutableListOf<String>()
        val delegate = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult { calls += toolRequest.tool; return ToolResult(true, stdout = "fixture") }
        }
        val runner = WorkflowRunner(model, delegate, "fixture", executionAdapter = { _, original ->
            object : ToolExecutor {
                override suspend fun execute(toolRequest: ToolRequest): ToolResult = original.execute(toolRequest)
            }
        })
        val outcome = runner.run(listOf(OllamaMessage("user", "Запиши notes.txt")), TaskState("approval", null, "Запиши notes.txt"))
        assertTrue(outcome is WorkflowOutcome.NeedsConfirmation)
        assertFalse(calls.contains("file.write"))
        runner.approve((outcome as WorkflowOutcome.NeedsConfirmation).pending)
        assertEquals(1, calls.count { it == "file.write" })
    }
}
