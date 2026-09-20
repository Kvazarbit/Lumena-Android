package com.lumena.android.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentControllerTest {
    private val controller = AgentController()

    private fun task() = TaskState(
        id = "t1",
        projectId = null,
        goal = "Create and verify a Python script",
        status = TaskStatus.WAITING_MODEL
    )

    @Test
    fun ordinaryReplyCanFinishBeforeToolWork() {
        val state = controller.initial(task())
        val instruction = controller.interpret("{\"reply\":\"hello\"}", state)
        assertTrue(instruction is ControllerInstruction.Finish)
    }

    @Test
    fun pythonWriteRequiresVerificationBeforeDone() {
        val state = controller.initial(task())
        val call = AgentDecision.ToolCall(
            tool = "file.write",
            args = mapOf("path" to "demo.py", "content" to "print('ok')"),
            reason = "Create script",
            plan = listOf("write", "verify")
        )
        val execute = controller.interpret(
            """{"plan":["write","verify"],"tool":"file.write","args":{"path":"demo.py","content":"print('ok')"},"reason":"Create script"}""",
            state
        ) as ControllerInstruction.Execute

        val after = controller.afterTool(
            execute.state,
            call,
            ok = true,
            stdout = "wrote=demo.py",
            stderr = "",
            error = null
        ).state
        assertTrue(after.verificationRequired)

        val done = controller.interpret("""{"done":true,"summary":"finished"}""", after)
        assertTrue(done is ControllerInstruction.AskModelAgain)
    }

    @Test
    fun successfulPythonVerificationClearsRequirement() {
        var state = controller.initial(task()).copy(
            toolUsed = true,
            verificationRequired = true,
            verificationReason = "verify python"
        )
        val call = AgentDecision.ToolCall(
            tool = "python.syntax_check",
            args = mapOf("script" to "demo.py")
        )
        state = controller.afterTool(
            state,
            call,
            ok = true,
            stdout = "",
            stderr = "",
            error = null
        ).state
        assertFalse(state.verificationRequired)

        val done = controller.interpret("""{"done":true,"summary":"verified"}""", state)
        assertTrue(done is ControllerInstruction.Finish)
    }

    @Test
    fun successfulPythonRecoveryReservesVerificationSteps() {
        val recoveredState = controller.initial(task()).copy(
            pythonFailures = 1,
            task = task().copy(
                status = TaskStatus.WAITING_MODEL,
                step = 3,
                maxSteps = 4,
                errors = listOf("ModuleNotFoundError: No module named requests")
            )
        )
        val call = AgentDecision.ToolCall(
            tool = "python.run",
            args = mapOf("script" to "install_dependency.py")
        )

        val after = controller.afterTool(
            recoveredState,
            call,
            ok = true,
            stdout = "dependency installed",
            stderr = "",
            error = null
        ).state

        assertTrue(after.task.maxSteps >= 6)
        assertTrue(after.task.canContinue)
        assertTrue(after.pythonFailures == 0)
    }

    @Test
    fun taskAllowsFinalModelTurnAtToolLimit() {
        val atLimit = task().copy(
            status = TaskStatus.WAITING_MODEL,
            step = 4,
            maxSteps = 4
        )
        assertTrue(atLimit.canContinue)

        val state = controller.initial(atLimit)
        val done = controller.interpret("""{"done":true,"summary":"verified"}""", state)
        assertTrue(done is ControllerInstruction.Finish)
    }

    @Test
    fun repeatedIdenticalCallsAreStopped() {
        var state = controller.initial(task())
        val raw = """{"tool":"workspace.list","args":{},"reason":"inspect"}"""

        val first = controller.interpret(raw, state) as ControllerInstruction.Execute
        state = first.state
        val second = controller.interpret(raw, state) as ControllerInstruction.Execute
        state = second.state
        val third = controller.interpret(raw, state)

        assertTrue(third is ControllerInstruction.Stop)
    }

    @Test
    fun proseWrappedToolJsonIsCorrectedInsteadOfShownAsReply() {
        val state = controller.initial(task())
        val raw = """
            I will verify the loaded model now.

            {"plan":["verify"],"tool":"ollama.status","args":{},"reason":"Check loaded model"}
        """.trimIndent()

        val instruction = controller.interpret(raw, state)

        assertTrue(instruction is ControllerInstruction.AskModelAgain)
    }

    @Test
    fun proseCannotFinishAfterToolWork() {
        val state = controller.initial(task()).copy(toolUsed = true)
        val instruction = controller.interpret("Looks good, done!", state)
        assertTrue(instruction is ControllerInstruction.AskModelAgain)
    }
}
