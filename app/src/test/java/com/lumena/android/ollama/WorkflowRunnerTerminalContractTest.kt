package com.lumena.android.ollama

import com.lumena.android.agent.core.*
import com.lumena.android.agent.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Scripted models exercise the production controller; they do not claim model quality. */
class WorkflowRunnerTerminalContractTest {
    private class Model(private val replies: List<String>) : ChatModelClient {
        val contexts = mutableListOf<List<OllamaMessage>>()
        override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
            contexts += messages
            return Result.success(replies.getOrElse(contexts.lastIndex) { """{"partial":true,"summary":"Unfinished"}""" })
        }
    }
    private class Bridge : ToolExecutor {
        val calls = mutableListOf<ToolRequest>()
        var unknown = false
        override suspend fun execute(toolRequest: ToolRequest): ToolResult {
            calls += toolRequest
            if (unknown && toolRequest.tool == "file.patch") return ToolResult(false, toolRequest.tool,
                error = "transport lost", outcomeUnknown = true)
            val output = when (toolRequest.tool) {
                "file.read" -> "def total(x): return x - 1"
                "python.tests" -> """{"schema":"lumena.project.tests.v1","cwd":"demo","tests_count":2,"verified":true}"""
                else -> "ok"
            }
            return ToolResult(true, toolRequest.tool, exitCode = 0, stdout = output)
        }
    }
    private fun task(id: String): TaskState {
        val p = WorkspaceProject("p", "Demo", "demo", "Maintain a tested project", listOf("Preserve API"), updatedAt = 1)
        val goal = "Виправ main.py і перевір тести"
        return TaskState(id, p.id, goal, currentInstruction = goal, projectRoot = p.root,
            projectVerification = ProjectVerification.PYTHON_TESTS, projectContext = ProjectWorkspacePolicy.capsule(p))
    }
    private val read = """{"tool":"file.read","args":{"path":"main.py"},"reason":"Inspect"}"""
    private val patch = """{"tool":"file.patch","args":{"path":"main.py","old":"x - 1","new":"x + 1"},"reason":"Fix"}"""

    @Test fun hubModelNamesShareContractApprovalAndVerifiedTerminalCycle() = runBlocking {
        for (name in listOf("embedded:gguf-a", "ollama:model-b", "hub:model-c")) {
            val model = Model(listOf(read, patch,
                """{"tool":"python.tests","args":{"cwd":".","argv":"-q"},"reason":"Verify"}""",
                """{"done":true,"summary":"Виправлено й перевірено"}"""))
            val bridge = Bridge()
            val runner = WorkflowRunner(model, bridge, name)
            var result = runner.run(listOf(OllamaMessage("system", "A restored/custom model prompt")), task(name))
            var approvals = 0
            while (result is WorkflowOutcome.NeedsConfirmation && approvals < 4) {
                val pending = result.pending
                assertFalse("Unapproved action reached the bridge", bridge.calls.any { it.tool == pending.plan.request.tool })
                approvals++
                result = runner.approve(pending)
            }
            assertTrue(result.toString(), result is WorkflowOutcome.Finished)
            assertEquals(TaskStatus.DONE, (result as WorkflowOutcome.Finished).control.task.status)
            assertEquals(2, approvals)
            assertEquals(listOf("project.inspect", "file.read", "file.patch", "python.tests"), bridge.calls.map { it.tool })
            assertEquals("demo/main.py", bridge.calls.first { it.tool == "file.patch" }.args["path"])
            assertTrue(model.contexts.all { messages -> messages.first().content.contains(ModelOperatingContract.binding()) &&
                messages.first().content.contains(TerminalWorkflowProtocol.prompt()) })
        }
    }

    @Test fun terminalContractCannotTurnUnknownMutationIntoLearnedSuccess() = runBlocking {
        val model = Model(listOf(read, patch))
        val bridge = Bridge().apply { unknown = true }
        val learned = mutableListOf<String>()
        var unknowns = 0
        val runner = WorkflowRunner(model, bridge, "hub:fixture",
            onToolExperience = { _, request, _, _ -> learned += request.tool },
            onUnknownOutcome = { _, _, _ -> learned += "GLOBAL_UNKNOWN" },
            projectObservation = { _, request, result ->
                if (result.outcomeUnknown) { assertEquals("file.patch", request.tool); unknowns++ }
            })
        val pending = runner.run(emptyList(), task("unknown")) as WorkflowOutcome.NeedsConfirmation
        val result = runner.approve(pending.pending)
        assertTrue(result.toString(), result is WorkflowOutcome.Failed)
        assertEquals(1, unknowns)
        assertFalse(learned.contains("file.patch"))
        assertTrue("Private terminal history entered global learning", learned.isEmpty())
        assertEquals(1, bridge.calls.count { it.tool == "file.patch" })
        assertNotNull((result as WorkflowOutcome.Failed).control.task.kernel.inFlight)
    }
}
