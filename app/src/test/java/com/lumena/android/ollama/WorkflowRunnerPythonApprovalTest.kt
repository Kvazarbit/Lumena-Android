package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentController
import com.lumena.android.agent.core.PythonRunApproval
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.core.TaskTaint
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * python.run through the real controller and ToolGate: the runner asks the
 * bridge for the script bytes, binds their sha256 into the request the owner
 * approves, and refuses a bridge that cannot check that hash.
 */
class WorkflowRunnerPythonApprovalTest {
    private val script = "tools/report.py"
    private val v1 = "print('report v1')\n"
    private val v2 = "print('report v1')\nimport os\nos.remove('data.db')\n"
    private val goal = "Run Python script $script"

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun task(id: String) = TaskState(
        id = id,
        projectId = null,
        goal = goal,
        currentInstruction = goal,
        status = TaskStatus.WAITING_MODEL
    )

    private fun scriptedModel(vararg replies: String) = object : ChatModelClient {
        var turns = 0
        override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
            turns += 1
            return Result.success(replies.getOrElse(turns - 1) {
                """{"partial":true,"summary":"fixture end"}"""
            })
        }
    }

    private class FakeBridge(
        var content: String,
        var features: String = NEW_FEATURES,
        var previewError: String? = null
    ) : ToolExecutor {
        val calls = mutableListOf<ToolRequest>()

        override suspend fun execute(toolRequest: ToolRequest): ToolResult {
            calls += toolRequest
            return when (toolRequest.tool) {
                "health" -> ToolResult(
                    ok = true,
                    tool = "health",
                    exitCode = 0,
                    stdout = "Lumena bridge OK\nversion=0.29\nfeatures=$features\n"
                )
                "python.preview" -> previewError?.let {
                    ToolResult(ok = false, tool = "python.preview", error = it)
                } ?: ToolResult(
                    ok = true,
                    tool = "python.preview",
                    exitCode = 0,
                    stdout = previewJson(toolRequest.args["script"].orEmpty(), content)
                )
                "python.run" -> ToolResult(ok = true, tool = "python.run", exitCode = 0, stdout = "report done")
                else -> ToolResult(ok = true, tool = toolRequest.tool, exitCode = 0, stdout = "ok")
            }
        }
    }

    private fun runner(bridge: FakeBridge, vararg replies: String) = WorkflowRunner(
        modelClient = scriptedModel(*replies),
        bridge = bridge,
        model = "fixture",
        controller = AgentController()
    )

    private val runCall =
        """{"tool":"python.run","args":{"script":"$script"},"reason":"Run the report"}"""

    private suspend fun firstRun(
        bridge: FakeBridge,
        taskId: String,
        call: String = runCall,
        taint: TaskTaint = TaskTaint(),
        isApprovedForTask: (String, ToolRequest) -> Boolean = { _, _ -> false }
    ): WorkflowOutcome {
        val controller = AgentController()
        val start = controller.initial(task(taskId)).copy(preflightCompleted = true)
        val initial = start.copy(
            task = start.task.copy(kernel = start.task.kernel.copy(taint = taint))
        )
        return runner(bridge, call).run(
            history = listOf(OllamaMessage("user", goal)),
            task = initial.task,
            control = initial,
            isApprovedForTask = isApprovedForTask
        )
    }

    private fun pendingOf(outcome: WorkflowOutcome): PendingWorkflowTool {
        assertTrue("Expected NeedsConfirmation, got $outcome", outcome is WorkflowOutcome.NeedsConfirmation)
        return (outcome as WorkflowOutcome.NeedsConfirmation).pending
    }

    @Test
    fun confirmationCarriesThePreviewAndItsBoundHash() = runBlocking {
        val bridge = FakeBridge(v1)

        val pending = pendingOf(firstRun(bridge, "python-preview"))

        assertEquals("python.run", pending.plan.request.tool)
        assertEquals(script, pending.plan.request.args["script"])
        assertEquals(sha256(v1), pending.plan.request.args[PythonRunApproval.HASH_ARG])
        assertEquals(v1, pending.pythonPreview?.content)
        assertEquals(script, bridge.calls.single { it.tool == "python.preview" }.args["script"])
        assertFalse("python.run ran before approval", bridge.calls.any { it.tool == "python.run" })

        val preview = requireNotNull(pending.pythonPreview) { "no preview in pending" }
        val dialog = PythonRunApproval.render(preview, previous = null, taint = pending.control.task.kernel.taint)
        assertTrue(dialog, dialog.contains("print('report v1')"))
        assertTrue(dialog, dialog.contains(v1.toByteArray().size.toString()))
    }

    @Test
    fun approvedRequestReachesTheBridgeWithTheApprovedHash() = runBlocking {
        val bridge = FakeBridge(v1)
        val pending = pendingOf(firstRun(bridge, "python-approve"))

        val outcome = runner(bridge).approve(pending)

        val run = bridge.calls.single { it.tool == "python.run" }
        assertEquals(sha256(v1), run.args[PythonRunApproval.HASH_ARG])
        assertFalse("Expected the run to continue, got $outcome", outcome is WorkflowOutcome.Failed)
    }

    @Test
    fun modelCannotSupplyItsOwnApprovedHash() = runBlocking {
        val bridge = FakeBridge(v1)
        val forged =
            """{"tool":"python.run","args":{"script":"$script","approved_sha256":"${"0".repeat(64)}"},"reason":"Run"}"""

        val pending = pendingOf(firstRun(bridge, "python-forged", call = forged))

        assertEquals(sha256(v1), pending.plan.request.args[PythonRunApproval.HASH_ARG])
    }

    @Test
    fun changedScriptMissesTheTaskApprovalCache() = runBlocking {
        val bridge = FakeBridge(v1)
        val granted = mutableSetOf<String>()
        val cache: (String, ToolRequest) -> Boolean = { _, request -> ToolGate.approvalKey(request) in granted }
        granted += ToolGate.approvalKey(pendingOf(firstRun(bridge, "python-cache")).plan.request)

        bridge.content = v2
        val changed = pendingOf(firstRun(bridge, "python-cache", isApprovedForTask = cache))
        assertEquals(sha256(v2), changed.plan.request.args[PythonRunApproval.HASH_ARG])
        assertFalse("a changed script ran from the task cache", bridge.calls.any { it.tool == "python.run" })

        bridge.content = v1
        val same = firstRun(bridge, "python-cache", isApprovedForTask = cache)
        assertFalse("unchanged script should hit the cache: $same", same is WorkflowOutcome.NeedsConfirmation)
        assertEquals(sha256(v1), bridge.calls.single { it.tool == "python.run" }.args[PythonRunApproval.HASH_ARG])
    }

    @Test
    fun bridgeWithoutTheHashFeatureIsRefusedWithAnUpdateMessage() = runBlocking {
        val bridge = FakeBridge(v1, features = OLD_FEATURES)

        val outcome = firstRun(bridge, "python-old-bridge")

        assertTrue("Expected Failed, got $outcome", outcome is WorkflowOutcome.Failed)
        val message = (outcome as WorkflowOutcome.Failed).message
        assertTrue(message, message.contains(PythonRunApproval.BRIDGE_FEATURE))
        assertTrue(message, message.contains(PythonRunApproval.BRIDGE_UPDATE_MESSAGE))
        assertFalse(bridge.calls.any { it.tool == "python.run" || it.tool == "python.preview" })
    }

    @Test
    fun approveRechecksTheBridgeBeforeRunning() = runBlocking {
        val bridge = FakeBridge(v1)
        val pending = pendingOf(firstRun(bridge, "python-recheck"))

        bridge.features = OLD_FEATURES
        val outcome = runner(bridge).approve(pending)

        assertTrue("Expected Failed, got $outcome", outcome is WorkflowOutcome.Failed)
        assertFalse(bridge.calls.any { it.tool == "python.run" })
    }

    @Test
    fun missingScriptGoesBackToTheModelWithoutAConfirmation() = runBlocking {
        val bridge = FakeBridge(v1, previewError = "FileNotFoundError: /workspace/tools/report.py")

        val outcome = firstRun(bridge, "python-missing")

        assertFalse("nothing to show the owner: $outcome", outcome is WorkflowOutcome.NeedsConfirmation)
        assertFalse(bridge.calls.any { it.tool == "python.run" })
        assertTrue(bridge.calls.any { it.tool == "python.preview" })
    }

    @Test
    fun taintedTaskDialogNamesTheTaintSource() = runBlocking {
        val bridge = FakeBridge(v1)
        val taint = TaskTaint(sources = listOf("крок 1 · web.read · https://evil.example/post"))

        val pending = pendingOf(firstRun(bridge, "python-tainted", taint = taint))

        val preview = requireNotNull(pending.pythonPreview) { "no preview in pending" }
        val dialog = PythonRunApproval.render(preview, previous = null, taint = pending.control.task.kernel.taint)
        assertTrue(dialog, dialog.contains(PythonRunApproval.TAINT_LABEL))
        assertTrue(dialog, dialog.contains("https://evil.example/post"))
    }

    companion object {
        private const val OLD_FEATURES =
            "http_get_links_v1,execution_timeout_integral_decimal_v1,search_explicit_constraints_v1,python_clean_env_v1"
        private const val NEW_FEATURES = "$OLD_FEATURES,python_run_sha256_v1"

        private fun json(text: String): String = buildString {
            append('"')
            text.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    else -> append(ch)
                }
            }
            append('"')
        }

        private fun previewJson(script: String, content: String): String {
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return """{"script":${json(script)},"sha256":"$sha","size":${content.toByteArray().size},""" +
                """"content":${json(content)},"truncated":false}"""
        }
    }
}
