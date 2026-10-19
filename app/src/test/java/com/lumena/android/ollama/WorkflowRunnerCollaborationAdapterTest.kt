package com.lumena.android.ollama

import com.lumena.android.agent.core.ProjectWorkspacePolicy
import com.lumena.android.agent.core.RequiredProjectContext
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.agent.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkflowRunnerCollaborationAdapterTest {
    @Test fun continuationCardRefreshesAfterAnObservedTool() = runBlocking {
        val revisions = mutableListOf<Int>()
        val seen = mutableListOf<String>()
        var turns = 0
        val model = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                seen += messages.first().content
                return Result.success(if (turns++ == 0)
                    """{"tool":"file.read","args":{"path":"notes.txt"},"reason":"inspect"}"""
                    else """{"partial":true,"summary":"checked"}""")
            }
        }
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest) = ToolResult(true, toolRequest.tool, 0, "source")
        }
        val p = WorkspaceProject("p", "Demo", "demo", "Read project notes", updatedAt = 1)
        val task = TaskState("refresh", "p", "Прочитай notes.txt", projectRoot = "demo", projectContext = ProjectWorkspacePolicy.capsule(p))
        WorkflowRunner(model, bridge, "fixture", collaborationCardProvider = {
            revisions += it.kernel.observed; "LUMENA STATE CARD v1 REVISION_${it.kernel.observed}"
        }).run(listOf(OllamaMessage("user", task.goal)), task)
        assertTrue(revisions.toString(), revisions.distinct().size >= 2)
        assertTrue(seen.first().contains("REVISION_${revisions.first()}"))
        assertTrue(seen.last().contains("REVISION_${revisions.last()}"))
    }
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
    @Test fun localStateCardEntersProtectedProjectContextOnlyWhenProvided() = runBlocking {
        val seen = mutableListOf<List<OllamaMessage>>()
        val model = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                seen += messages
                return Result.success("""{"partial":true,"summary":"fixture"}""")
            }
        }
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest) = ToolResult(true, toolRequest.tool, 0,
                """{"schema":"lumena.project.v1","cwd":"demo","exists":true,"git":{},"source_digest":"${"a".repeat(64)}","files_count":1,"truncated":false}""")
        }
        val p = WorkspaceProject("p", "Demo", "demo", "Maintain demo", updatedAt = 1)
        val task = TaskState("t", "p", "Продовж роботу", currentInstruction = "Продовж роботу", projectRoot = "demo",
            projectContext = ProjectWorkspacePolicy.capsule(p))
        var asked = 0
        WorkflowRunner(model, bridge, "fixture", collaborationCardProvider = { asked++; "LUMENA STATE CARD v1\nCARD_RECEIPT_CANARY" })
            .run(listOf(OllamaMessage("user", task.goal)), task)
        assertEquals(1, asked)
        assertTrue(seen.isNotEmpty())
        // Inside the protected block, so backend compaction for a small local model cannot drop it.
        assertTrue(seen.all { batch -> RequiredProjectContext.block(batch.first().content)!!.contains("CARD_RECEIPT_CANARY") })

        // Cloud or unattested routes get no provider output; ordinary chats are never asked.
        seen.clear()
        val baseline = WorkflowRunner(model, bridge, "fixture", collaborationCardProvider = { null }).run(listOf(OllamaMessage("user", task.goal)), task)
        assertTrue(seen.isNotEmpty() && seen.flatten().none { it.content.contains("CARD_RECEIPT_CANARY") })
        var general = 0
        WorkflowRunner(model, bridge, "fixture", collaborationCardProvider = { general++; "CARD_RECEIPT_CANARY" })
            .run(listOf(OllamaMessage("user", "Привіт")), TaskState("g", null, "Привіт"))
        assertEquals(0, general)
        // A failing journal read never stops the task.
        seen.clear()
        val outcome = WorkflowRunner(model, bridge, "fixture", collaborationCardProvider = { error("synthetic unreadable journal") })
            .run(listOf(OllamaMessage("user", task.goal)), task)
        assertTrue(seen.isNotEmpty())
        assertEquals(outcome.toString(), baseline::class, outcome::class)
    }
}
