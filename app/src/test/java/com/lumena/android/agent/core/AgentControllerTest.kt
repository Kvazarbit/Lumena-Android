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
        val conversational = TaskState(
            id = "chat",
            projectId = null,
            goal = "Поясни різницю між RAM і SSD",
            status = TaskStatus.WAITING_MODEL
        )
        val state = controller.initial(conversational)
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

        val state = controller.initial(atLimit).copy(toolUsed = true)
        val done = controller.interpret("""{"done":true,"summary":"verified"}""", state)
        assertTrue(done is ControllerInstruction.Finish)
    }

    @Test
    fun verifiedExperienceIsInjectedIntoDynamicContext() {
        val state = controller.initial(task())
        val context = controller.dynamicContext(
            state,
            relevantMemory = listOf(
                "NEGATIVE unresolved · python.run · target=script=demo.py · failure: missing dependency · seen=1x",
                "POSITIVE verified · git.status · target=cwd=@Lumena-Android · success: clean · seen=2x"
            )
        )

        assertTrue(context.contains("RELEVANT VERIFIED MEMORY"))
        assertTrue(context.contains("NEGATIVE unresolved"))
        assertTrue(context.contains("POSITIVE verified"))
        assertTrue(context.contains("script=demo.py"))
    }

    @Test
    fun imageGoalCannotFinishBeforeImageSearch() {
        val visualTask = TaskState(
            id = "img",
            projectId = null,
            goal = "знайди фото жінки в інтернеті і покажи",
            status = TaskStatus.WAITING_MODEL
        )
        val state = controller.initial(visualTask)

        val done = controller.interpret(
            """{"done":true,"summary":"Ось посилання"}""",
            state
        )

        assertTrue(done is ControllerInstruction.AskModelAgain)
    }

    @Test
    fun successfulImageSearchSatisfiesVisualGoal() {
        val visualTask = TaskState(
            id = "img-ok",
            projectId = null,
            goal = "знайди фото жінки в інтернеті і покажи",
            status = TaskStatus.WAITING_MODEL
        )
        var state = controller.initial(visualTask)
        val call = AgentDecision.ToolCall(
            tool = "image.search",
            args = mapOf("query" to "woman portrait")
        )

        state = controller.afterTool(
            state = state,
            call = call,
            ok = true,
            stdout = """{"display_ready":true,"images":[{"thumbnail_url":"https://upload.wikimedia.org/example.jpg"}]}""",
            stderr = "",
            error = null
        ).state

        assertTrue(state.visualEvidenceReady)

        val done = controller.interpret(
            """{"done":true,"summary":"Знайшла фото нижче."}""",
            state
        )
        assertTrue(done is ControllerInstruction.Finish)
    }

    @Test
    fun plainReplyMayFinishAfterVerifiedVisualEvidence() {
        val visualTask = TaskState(
            id = "img-reply",
            projectId = null,
            goal = "знайди фото жінки в інтернеті і покажи",
            status = TaskStatus.WAITING_MODEL
        )
        val state = controller.initial(visualTask).copy(
            toolUsed = true,
            visualEvidenceReady = true
        )

        val instruction = controller.interpret(
            """{"reply":"Ось знайдені фото."}""",
            state
        )

        assertTrue(instruction is ControllerInstruction.Finish)
    }

    @Test
    fun initialStateCarriesDeterministicIntentRecipe() {
        val visualTask = TaskState(
            id = "intent",
            projectId = null,
            goal = "знайди фото жінки і покажи",
            status = TaskStatus.WAITING_MODEL
        )

        val state = controller.initial(visualTask)
        val context = controller.dynamicContext(state)

        assertTrue(state.intent == TaskIntent.VISUAL_SEARCH)
        assertTrue(state.intentConfidence == 100)
        assertTrue("image.search" in state.recommendedTools)
        assertTrue(context.contains("TASK RECIPE"))
        assertTrue(context.contains("intent=VISUAL_SEARCH"))
        assertTrue(context.contains("recommended_tools=image.search"))
    }

    @Test
    fun failedToolProducesRecoveryGuidanceInDynamicContext() {
        val fileTask = TaskState(
            id = "recover",
            projectId = null,
            goal = "знайди файл missing.txt і прочитай його",
            status = TaskStatus.WAITING_MODEL
        )
        var state = controller.initial(fileTask)
        val call = AgentDecision.ToolCall(
            tool = "file.read",
            args = mapOf("path" to "missing.txt")
        )

        state = controller.afterTool(
            state = state,
            call = call,
            ok = false,
            stdout = "",
            stderr = "",
            error = "FileNotFoundError: No such file"
        ).state

        val context = controller.dynamicContext(state)

        assertTrue(state.recoveryHint.orEmpty().contains("workspace.list"))
        assertTrue(context.contains("RECOVERY GUIDANCE"))
        assertTrue(context.contains("file.search"))
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
