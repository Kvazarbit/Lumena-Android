package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
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
    fun mixedResearchCodeTaskStartsWithEightToolBudget() {
        val mixed = TaskState(
            id = "mixed-budget",
            projectId = "e2e_step87",
            goal = """
                Працюй у проекті e2e_step87.
                Прочитай через web.read https://docs.python.org/3/library/pathlib.html
                Потім створи dir_a.py і test_dir_a.py.
                Запусти python.syntax_check і python.tests.
            """.trimIndent(),
            status = TaskStatus.WAITING_MODEL
        )

        val state = controller.initial(mixed)

        assertEquals(TaskIntent.CODE_WORK, state.intent)
        assertEquals(11, state.task.maxSteps)
        assertTrue("web.read" in state.recommendedTools)
        assertTrue("python.tests" in state.recommendedTools)
        assertEquals(
            setOf(
                "web.read",
                "python.syntax_check",
                "python.tests"
            ),
            state.requiredTools
        )
    }


    @Test
    fun exactE2eGoalWithForbiddenOllamaToolKeepsCodeBudgetAndObligations() {
        val goal = """
            Працюй у проекті e2e_step87.
            Це Step 8.7 E2E, Task A.
            Прочитай через web.read:
            https://docs.python.org/3/library/pathlib.html#pathlib.Path.mkdir
            Використовуй тільки успішний TOOL_RESULT як доказ.
            Потім створи: e2e_step87/dir_a.py
            У ньому функцію ensure_directory(path), яка використовує:
            Path(path).mkdir(parents=True, exist_ok=True)
            Створи також: e2e_step87/test_dir_a.py
            Після запису файлів:
            - python.syntax_check для e2e_step87/dir_a.py
            - python.tests з cwd=e2e_step87
            Не використовуй ollama.generate для перевірки SHADOW/ACTIVE.
            Не створюй mock-файли зі state='ACTIVE' або percentage=100.
        """.trimIndent()

        val state = controller.initial(
            TaskState(
                id = "exact-e2e",
                projectId = "e2e_step87",
                goal = goal,
                status = TaskStatus.WAITING_MODEL
            )
        )

        assertEquals(TaskIntent.CODE_WORK, state.intent)
        assertEquals(11, state.task.maxSteps)
        assertEquals(
            setOf(
                "web.read",
                "python.syntax_check",
                "python.tests"
            ),
            state.requiredTools
        )
        assertTrue("file.write" in state.recommendedTools)
        assertTrue("web.read" in state.recommendedTools)
        assertTrue("python.tests" in state.recommendedTools)
    }

    @Test
    fun simpleCodeTaskGetsSixButGeneralConversationStaysFour() {
        val code = controller.initial(
            TaskState(
                id = "code-budget",
                projectId = "demo",
                goal = "Створи Python script і перевір тестами",
                status = TaskStatus.WAITING_MODEL
            )
        )
        assertEquals(TaskIntent.CODE_WORK, code.intent)
        assertEquals(6, code.task.maxSteps)

        val general = controller.initial(
            TaskState(
                id = "general-budget",
                projectId = null,
                goal = "Поясни різницю між RAM і SSD",
                status = TaskStatus.WAITING_MODEL
            )
        )
        assertEquals(TaskIntent.GENERAL, general.intent)
        assertEquals(4, general.task.maxSteps)
    }


    @Test
    fun requiredWebEvidenceBlocksMutationUntilSuccessfulRead() {
        val task = TaskState(
            id = "required-web-before-write",
            projectId = "e2e_step87",
            goal = """
                Прочитай через web.read https://docs.python.org/3/library/pathlib.html
                Потім створи Python файл і запусти python.tests.
            """.trimIndent(),
            status = TaskStatus.WAITING_MODEL
        )
        var state = controller.initial(task)

        val blocked = controller.interpret(
            """{"tool":"file.write","args":{"path":"e2e_step87/a.py","content":"print('ok')"}}""",
            state
        )
        assertTrue(blocked is ControllerInstruction.AskModelAgain)
        assertEquals(0, blocked.state.task.step)

        val web = controller.interpret(
            """{"tool":"web.read","args":{"url":"https://docs.python.org/3/library/pathlib.html"}}""",
            blocked.state
        )
        assertTrue(web is ControllerInstruction.Execute)
        web as ControllerInstruction.Execute

        state = controller.afterTool(
            state = web.state,
            call = web.call,
            ok = true,
            stdout = "Path.mkdir documentation",
            stderr = "",
            error = null
        ).state

        assertTrue("web.read" in state.completedRequiredTools)

        val write = controller.interpret(
            """{"tool":"file.write","args":{"path":"e2e_step87/a.py","content":"print('ok')"}}""",
            state
        )
        assertTrue(write is ControllerInstruction.Execute)
    }


    @Test
    fun pendingPythonPathsDoNotDoubleReserveRequiredVerificationSlots() {
        val task = TaskState(
            id = "reserve-no-double-count",
            projectId = "e2e_step87",
            goal = """
                Прочитай через web.read https://docs.python.org/3/library/pathlib.html
                Створи e2e_step87/file_b.py і e2e_step87/test_file_b.py.
                Після запису:
                - python.syntax_check для e2e_step87/file_b.py
                - python.tests з cwd=e2e_step87
            """.trimIndent(),
            status = TaskStatus.WAITING_MODEL
        )

        val initial = controller.initial(task)
        assertEquals(11, initial.task.maxSteps)

        val state = initial.copy(
            completedRequiredTools = setOf("web.read"),
            pendingPythonPaths = setOf(
                "e2e_step87/file_a.py",
                "e2e_step87/file_b.py"
            ),
            task = initial.task.copy(
                step = 7,
                maxSteps = 11,
                status = TaskStatus.WAITING_MODEL
            )
        )

        val writeMissingTest = controller.interpret(
            """{"tool":"file.write","args":{"path":"e2e_step87/test_file_b.py","content":"def test_placeholder():\n    assert True\n"}}""",
            state
        )

        assertTrue(writeMissingTest is ControllerInstruction.Execute)
    }

    @Test
    fun oneExtraVerificationSlotIsReservedWhenNoRequiredVerificationToolExists() {
        val task = TaskState(
            id = "reserve-one-verification",
            projectId = "demo",
            goal = "Створи Python файл.",
            status = TaskStatus.WAITING_MODEL
        )
        val initial = controller.initial(task)
        val state = initial.copy(
            requiredTools = emptySet(),
            completedRequiredTools = emptySet(),
            pendingPythonPaths = setOf("demo/a.py", "demo/b.py"),
            task = initial.task.copy(
                step = initial.task.maxSteps - 1,
                status = TaskStatus.WAITING_MODEL
            )
        )

        val unrelated = controller.interpret(
            """{"tool":"workspace.list","args":{}}""",
            state
        )

        assertTrue(unrelated is ControllerInstruction.AskModelAgain)
        assertEquals(
            initial.task.maxSteps - 1,
            unrelated.state.task.step
        )
    }

    @Test
    fun doneIsBlockedUntilExplicitRequiredToolsHaveSuccessfulResults() {
        val task = TaskState(
            id = "required-before-done",
            projectId = "demo",
            goal = "Запусти python.tests для проекту.",
            status = TaskStatus.WAITING_MODEL
        )
        val state = controller.initial(task)

        val done = controller.interpret(
            """{"done":true,"summary":"done"}""",
            state.copy(toolUsed = true)
        )

        assertTrue(done is ControllerInstruction.AskModelAgain)
        done as ControllerInstruction.AskModelAgain
        assertTrue(done.feedback.contains("python.tests"))
    }

    @Test
    fun repeatedSuccessfulWriteIsRejectedWithoutConsumingAnotherToolStep() {
        val task = TaskState(
            id = "duplicate-write",
            projectId = "demo",
            goal = "Створи Python файл.",
            status = TaskStatus.WAITING_MODEL
        )
        var state = controller.initial(task)
        val first = controller.interpret(
            """{"tool":"file.write","args":{"path":"demo/a.py","content":"print('ok')"}}""",
            state
        )
        assertTrue(first is ControllerInstruction.Execute)
        first as ControllerInstruction.Execute

        state = controller.afterTool(
            state = first.state,
            call = first.call,
            ok = true,
            stdout = "wrote",
            stderr = "",
            error = null
        ).state
        val stepAfterFirst = state.task.step

        val duplicate = controller.interpret(
            """{"tool":"file.write","args":{"path":"demo/a.py","content":"print('ok')"}}""",
            state
        )

        assertTrue(duplicate is ControllerInstruction.AskModelAgain)
        assertEquals(stepAfterFirst, duplicate.state.task.step)
    }

    @Test
    fun freshTargetVerificationIsNotRepeatedAfterUnrelatedMutation() {
        val task = TaskState(
            id = "duplicate-verify",
            projectId = "demo",
            goal = "Створи і перевір Python файли.",
            status = TaskStatus.WAITING_MODEL
        )
        var state = controller.initial(task)

        fun after(
            current: AgentControlState,
            call: AgentDecision.ToolCall
        ): AgentControlState =
            controller.afterTool(
                state = current,
                call = call,
                ok = true,
                stdout = "ok",
                stderr = "",
                error = null
            ).state

        state = after(
            state,
            AgentDecision.ToolCall(
                "file.write",
                mapOf(
                    "path" to "demo/a.py",
                    "content" to "print('a')"
                )
            )
        )
        state = after(
            state,
            AgentDecision.ToolCall(
                "python.syntax_check",
                mapOf("script" to "demo/a.py")
            )
        )
        state = after(
            state,
            AgentDecision.ToolCall(
                "file.write",
                mapOf(
                    "path" to "demo/b.py",
                    "content" to "print('b')"
                )
            )
        )

        val duplicate = controller.interpret(
            """{"tool":"python.syntax_check","args":{"script":"demo/a.py"}}""",
            state
        )

        assertTrue(duplicate is ControllerInstruction.AskModelAgain)
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
            pendingPythonPaths = setOf("demo.py"),
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
    fun verificationIsBoundToEachChangedPathAndReopenedAfterAnotherWrite() {
        fun apply(state: AgentControlState, tool: String, key: String, path: String, ok: Boolean = true) =
            controller.afterTool(state, AgentDecision.ToolCall(tool, mapOf(key to path)),
                ok, "", "", null).state

        var state = apply(controller.initial(task()), "file.write", "path", "A.py")
        state = apply(state, "file.patch", "path", "B.py")
        state = apply(state, "python.syntax_check", "script", "unrelated.py")
        state = apply(state, "python.tests", "cwd", ".")
        assertTrue(state.pendingPythonPaths == setOf("A.py", "B.py"))
        state = apply(state, "python.syntax_check", "script", "a.py")
        assertTrue(state.pendingPythonPaths.contains("A.py"))
        state = apply(state, "python.syntax_check", "script", "./A.py")
        assertTrue(state.verificationRequired)
        state = apply(state, "python.syntax_check", "script", "B.py", ok = false)
        assertTrue(state.verificationRequired)
        state = apply(state, "python.syntax_check", "script", "B.py")
        assertFalse(state.verificationRequired)
        state = apply(state, "file.patch", "path", "A.py")
        assertTrue(state.verificationRequired)
    }

    @Test
    fun visualReplyCannotBypassPendingCodeVerification() {
        val state = controller.initial(task().copy(goal = "Find a photo of a cat")).copy(
            toolUsed = true, visualEvidenceReady = true,
            verificationRequired = true, pendingPythonPaths = setOf("demo.py")
        )
        assertTrue(controller.interpret("Here is the photo", state) is ControllerInstruction.AskModelAgain)
    }

    @Test
    fun memoryAndNativeGenerationFailuresStopWithoutUnchangedRetry() {
        for (message in listOf(
            "Not enough free RAM to load this GGUF safely.",
            "Embedded generation failed. llama_decode failed; partial output discarded."
        )) {
            assertTrue(controller.onModelFailure(controller.initial(task()), message) is ControllerInstruction.Stop)
        }
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
    fun extraToolAtLimitGetsOneConclusionTurnAfterVerifiedResult() {
        val atLimit = controller.initial(task().copy(maxSteps = 5)).copy(
            toolUsed = true,
            task = task().copy(
                status = TaskStatus.WAITING_MODEL,
                step = 5,
                maxSteps = 5,
                lastTool = "python.run",
                lastResult = "ok=true stdout=Cleanup script deleted"
            )
        )

        val correction = controller.interpret(
            """{"tool":"python.run","args":{"script":"last_step.py"},"reason":"cleanup again"}""",
            atLimit
        )
        assertTrue(correction is ControllerInstruction.AskModelAgain)
        correction as ControllerInstruction.AskModelAgain
        assertTrue(correction.feedback.contains("was not executed"))
        assertTrue(correction.feedback.contains("Do not request another tool"))

        val done = controller.interpret(
            """{"done":true,"summary":"Очищення виконано і перевірено."}""",
            correction.state
        )
        assertTrue(done is ControllerInstruction.Finish)

        val repeated = controller.interpret(
            """{"tool":"python.run","args":{"script":"another_cleanup.py"}}""",
            correction.state
        )
        assertTrue(repeated is ControllerInstruction.Stop)
    }

    @Test
    fun pendingVerificationNeverGetsConclusionShortcutAtToolLimit() {
        val atLimit = controller.initial(task().copy(maxSteps = 5)).copy(
            toolUsed = true,
            verificationRequired = true,
            verificationReason = "verify demo.py",
            pendingPythonPaths = setOf("demo.py"),
            task = task().copy(status = TaskStatus.WAITING_MODEL, step = 5, maxSteps = 5)
        )

        val instruction = controller.interpret(
            """{"tool":"python.syntax_check","args":{"script":"demo.py"}}""",
            atLimit
        )
        assertTrue(instruction is ControllerInstruction.AskModelAgain)
        val corrected = instruction.state
        assertTrue(controller.interpret("""{"done":true,"summary":"ok"}""", corrected) is ControllerInstruction.AskModelAgain)
        val partial = controller.interpret("""{"partial":true,"summary":"demo.py still needs verification"}""", corrected)
        assertTrue(partial is ControllerInstruction.Finish)
        assertTrue(partial.state.task.status == TaskStatus.PARTIAL)
    }


    @Test
    fun fullProjectPytestClearsOnlyPendingPythonPathsInsideItsCwd() {
        var state = controller.initial(task()).copy(
            toolUsed = true,
            verificationRequired = true,
            pendingPythonPaths = setOf(
                "e2e_step87/dir_a.py",
                "e2e_step87/test_dir_a.py",
                "other_project/keep.py"
            ),
            verificationReason = "verify changed python"
        )

        state = controller.afterTool(
            state = state,
            call = AgentDecision.ToolCall(
                tool = "python.tests",
                args = mapOf(
                    "cwd" to "e2e_step87"
                )
            ),
            ok = true,
            stdout = "1 passed",
            stderr = "",
            error = null
        ).state

        assertEquals(
            setOf("other_project/keep.py"),
            state.pendingPythonPaths
        )
        assertTrue(state.verificationRequired)
    }

    @Test
    fun fullProjectPytestCanCloseAllPendingVerificationButSelectedPytestCannot() {
        val base = controller.initial(task()).copy(
            toolUsed = true,
            verificationRequired = true,
            pendingPythonPaths = setOf(
                "e2e_step87/dir_a.py",
                "e2e_step87/test_dir_a.py"
            ),
            verificationReason = "verify changed python"
        )

        val selected = controller.afterTool(
            state = base,
            call = AgentDecision.ToolCall(
                tool = "python.tests",
                args = mapOf(
                    "cwd" to "e2e_step87",
                    "argv" to "-q test_dir_a.py"
                )
            ),
            ok = true,
            stdout = "1 passed",
            stderr = "",
            error = null
        ).state

        assertEquals(
            base.pendingPythonPaths,
            selected.pendingPythonPaths
        )
        assertTrue(selected.verificationRequired)

        val full = controller.afterTool(
            state = base,
            call = AgentDecision.ToolCall(
                tool = "python.tests",
                args = mapOf(
                    "cwd" to "e2e_step87"
                )
            ),
            ok = true,
            stdout = "1 passed",
            stderr = "",
            error = null
        ).state

        assertTrue(full.pendingPythonPaths.isEmpty())
        assertFalse(full.verificationRequired)

        val done = controller.interpret(
            """{"done":true,"summary":"project verified"}""",
            full
        )
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
    fun failedWebSearchBecomesBoundedSemanticRecoveryInsteadOfTaskFailure() {
        val webTask = TaskState(
            id = "web-fail",
            projectId = null,
            goal = "Знайди останні новини в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val call = AgentDecision.ToolCall(
            tool = "web.search",
            args = mapOf("query" to "останні новини")
        )
        val initial = controller.initial(webTask)
        val executing = initial.copy(
            task = initial.task.copy(
                status = TaskStatus.EXECUTING,
                kernel = ContextKernel.before(initial.task.kernel, call)
            )
        )

        val transition = controller.afterTool(
            state = executing,
            call = call,
            ok = false,
            stdout = """{"query":"останні новини","results":[],"attempts":[{"provider":"duckduckgo","error":"Upstream HTTP 202"}]}""",
            stderr = "",
            error = "Search unavailable. Do not invent current facts.",
            errorCode = "SEARCH_EXHAUSTED",
            failureClass = "DEPENDENCY_EXHAUSTED",
            retryable = false,
            dependency = "web.search"
        )

        assertTrue(transition.stopReason == null)
        assertTrue(transition.partialReason == null)
        assertTrue(transition.state.task.status == TaskStatus.WAITING_MODEL)
        assertTrue(transition.state.task.lastTool == "web.search")
        assertTrue(transition.state.task.kernel.inFlight == null)
        assertTrue(transition.state.semanticRecoverySpent == 1)
        assertTrue(transition.state.actionFamilyFailures["web.search"] == 1)
        assertTrue(transition.state.recoveryHint.orEmpty().contains("provider", ignoreCase = true))
    }

    @Test
    fun secondFailedSearchVariantDegradesPartialWithoutThirdLoop() {
        val webTask = TaskState(
            id = "web-two",
            projectId = null,
            goal = "Знайди останні новини в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val first = AgentDecision.ToolCall(
            tool = "web.search",
            args = mapOf("query" to "останні новини")
        )
        val initial = controller.initial(webTask)
        val firstExecuting = initial.copy(
            task = initial.task.copy(
                status = TaskStatus.EXECUTING,
                kernel = ContextKernel.before(initial.task.kernel, first)
            )
        )
        val afterFirst = controller.afterTool(
            state = firstExecuting,
            call = first,
            ok = false,
            stdout = "",
            stderr = "",
            error = "Search unavailable",
            errorCode = "SEARCH_EXHAUSTED",
            failureClass = "DEPENDENCY_EXHAUSTED"
        ).state

        val second = AgentDecision.ToolCall(
            tool = "web.search",
            args = mapOf("query" to "головні світові новини сьогодні")
        )
        val secondExecuting = afterFirst.copy(
            task = afterFirst.task.copy(
                status = TaskStatus.EXECUTING,
                kernel = ContextKernel.before(afterFirst.task.kernel, second)
            )
        )
        val afterSecond = controller.afterTool(
            state = secondExecuting,
            call = second,
            ok = false,
            stdout = "",
            stderr = "",
            error = "Search unavailable",
            errorCode = "SEARCH_EXHAUSTED",
            failureClass = "DEPENDENCY_EXHAUSTED"
        )

        assertTrue(afterSecond.stopReason == null)
        assertTrue(afterSecond.partialReason != null)
        assertTrue(afterSecond.state.task.status == TaskStatus.PARTIAL)
        assertTrue(afterSecond.state.semanticRecoverySpent == 2)
        assertTrue(afterSecond.state.actionFamilyFailures["web.search"] == 2)
    }

    @Test
    fun successfulWebSearchStillReturnsControlToModelForSourceReading() {
        val webTask = TaskState(
            id = "web-ok",
            projectId = null,
            goal = "Знайди новини в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val call = AgentDecision.ToolCall(
            tool = "web.search",
            args = mapOf("query" to "новини")
        )
        val initial = controller.initial(webTask)
        val executing = initial.copy(
            task = initial.task.copy(
                status = TaskStatus.EXECUTING,
                kernel = ContextKernel.before(initial.task.kernel, call)
            )
        )

        val transition = controller.afterTool(
            state = executing,
            call = call,
            ok = true,
            stdout = """{"results":[{"url":"https://example.org","title":"Example"}]}""",
            stderr = "",
            error = null
        )

        assertTrue(transition.stopReason == null)
        assertTrue(transition.state.task.status == TaskStatus.WAITING_MODEL)
        assertTrue(transition.state.task.kernel.inFlight == null)
    }

    @Test
    fun exhaustedContextErrorStopsInsteadOfStartingControllerRetryLoop() {
        val instruction = controller.onModelFailure(
            controller.initial(task()),
            "Ollama stream error: context length exceeded; prompt has too many tokens"
        )

        assertTrue(instruction is ControllerInstruction.Stop)
        assertTrue(instruction.state.task.status == TaskStatus.FAILED)
        assertTrue(instruction.state.modelFailures == 1)
    }

    @Test
    fun publicWebCanTrySeveralDistinctReadOnlySourcesBeforePartial() {
        val webTask = TaskState(
            id = "web-source-fallback",
            projectId = null,
            goal = "Знайди в інтернеті останні новини Python сьогодні",
            status = TaskStatus.WAITING_MODEL,
            maxSteps = 8
        )
        var state = controller.initial(webTask)
        val search = AgentDecision.ToolCall(
            "web.search",
            mapOf("query" to "останні новини Python сьогодні")
        )
        state = controller.afterTool(
            state = state,
            call = search,
            ok = true,
            stdout = """{"results":[{"url":"https://a.example"},{"url":"https://b.example"},{"url":"https://c.example"},{"url":"https://d.example"}]}""",
            stderr = "",
            error = null
        ).state

        val urls = listOf(
            "https://a.example",
            "https://b.example",
            "https://c.example",
            "https://d.example"
        )

        urls.take(3).forEachIndexed { index, url ->
            val execute = controller.interpret(
                """{"tool":"web.read","args":{"url":"$url"},"reason":"try alternate source"}""",
                state
            )
            assertTrue("source index=$index should still be executable", execute is ControllerInstruction.Execute)
            execute as ControllerInstruction.Execute
            val transition = controller.afterTool(
                state = execute.state,
                call = execute.call,
                ok = false,
                stdout = "",
                stderr = "",
                error = if (index == 0) {
                    "ValueError: Page requires human verification; use another source"
                } else {
                    "ValueError: Public HTTPS transport failed (TimeoutError)"
                }
            )
            assertTrue(transition.partialReason == null)
            assertTrue(transition.stopReason == null)
            state = transition.state
        }

        val fourth = controller.interpret(
            """{"tool":"web.read","args":{"url":"${urls[3]}"},"reason":"last bounded alternate source"}""",
            state
        )
        assertTrue(fourth is ControllerInstruction.Execute)
        fourth as ControllerInstruction.Execute
        val exhausted = controller.afterTool(
            state = fourth.state,
            call = fourth.call,
            ok = false,
            stdout = "",
            stderr = "",
            error = "ValueError: Public HTTPS transport failed (TimeoutError)"
        )

        assertTrue(exhausted.partialReason != null)
        assertTrue(exhausted.state.task.status == TaskStatus.PARTIAL)
        assertTrue(exhausted.state.actionFamilyFailures["web.read"] == 4)
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
    fun longEmbeddedLoadErrorIsNonRetryableBeforeTruncation() {
        val state = controller.initial(
            TaskState(
                id = "load-fail",
                projectId = null,
                goal = "Answer using the selected local model",
                status = TaskStatus.WAITING_MODEL
            )
        )
        val hugeTail = buildString {
            repeat(200) {
                append("create_tensor: loading tensor blk.38.ffn_up.input_scale\n")
            }
        }
        val instruction = controller.onModelFailure(
            state,
            "Embedded model load failed. GGUF metadata is readable, but the full model load failed before inference started.\n" +
                hugeTail
        )

        assertTrue(instruction is ControllerInstruction.Stop)
        assertFalse(instruction is ControllerInstruction.AskModelAgain)
        val stopped = instruction as ControllerInstruction.Stop
        assertTrue(stopped.reason.contains("Embedded model load failed"))
        assertTrue(stopped.reason.contains("technical log omitted"))
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
    @Test
    fun failedNonWebToolCannotEraseExistingModelFailureBudget() {
        val initial = controller.initial(task()).copy(modelFailures = 2)
        val transition = controller.afterTool(
            state = initial,
            call = AgentDecision.ToolCall(
                tool = "file.read",
                args = mapOf("path" to "missing.txt")
            ),
            ok = false,
            stdout = "",
            stderr = "",
            error = "FileNotFoundError"
        )

        assertTrue(transition.stopReason == null)
        assertTrue(transition.state.modelFailures == 2)

        val nextFailure = controller.onModelFailure(
            transition.state,
            "temporary model transport failure"
        )
        assertTrue(nextFailure is ControllerInstruction.Stop)
    }

    @Test
    fun toolResultDoesNotOwnModelRuntimeFailureCounter() {
        val initial = controller.initial(task()).copy(modelFailures = 2)
        val transition = controller.afterTool(
            state = initial,
            call = AgentDecision.ToolCall(
                tool = "workspace.list",
                args = emptyMap()
            ),
            ok = true,
            stdout = "workspace ok",
            stderr = "",
            error = null
        )

        assertTrue(transition.stopReason == null)
        assertTrue(transition.state.modelFailures == 2)

        val modelRecovered = controller.interpret(
            """{"tool":"workspace.list","args":{},"reason":"model generated a valid next action"}""",
            initial
        )
        assertTrue(modelRecovered is ControllerInstruction.Execute)
        modelRecovered as ControllerInstruction.Execute
        assertTrue(modelRecovered.state.modelFailures == 0)
    }

    @Test
    fun alternateOllamaContextPressureMessagesRemainNonRetryableAtControllerLayer() {
        for (message in listOf(
            "Ollama stream error: requested tokens exceed model capacity",
            "Ollama stream error: input exceeds the context limit",
            "Ollama stream error: invalid num_ctx for request"
        )) {
            val instruction = controller.onModelFailure(
                controller.initial(task()),
                message
            )
            assertTrue(instruction is ControllerInstruction.Stop)
        }
    }

    @Test
    fun singleFunctionMutationWrapperStillRequiresNormalConfirmation() {
        val state = controller.initial(task())

        val instruction = controller.interpret(
            """{"tool_calls":[{"type":"function","function":{"name":"file.write","arguments":{"path":"demo.py","content":"print('ok')"}}}]}""",
            state
        )

        assertTrue(instruction is ControllerInstruction.Execute)
        instruction as ControllerInstruction.Execute
        assertTrue(instruction.call.tool == "file.write")
        assertTrue(instruction.requiresConfirmation)
        assertTrue(
            instruction.state.lastNormalizationRule ==
                "SINGLE_FUNCTION_TOOL_CALL"
        )
    }

    @Test
    fun knownActionReplyEnvelopeFinishesWithoutProtocolRetry() {
        val conversational = TaskState(
            id = "normalized-reply",
            projectId = null,
            goal = "Поясни коротко різницю між RAM і SSD",
            status = TaskStatus.WAITING_MODEL
        )
        val state = controller.initial(conversational)

        val instruction = controller.interpret(
            """{"action":"reply","result":"Привіт із локальної моделі"}""",
            state
        )

        assertTrue(instruction is ControllerInstruction.Finish)
        instruction as ControllerInstruction.Finish
        assertTrue(instruction.text.contains("Привіт із локальної моделі"))
        assertTrue(instruction.state.protocolRetries == 0)
        assertTrue(instruction.state.protocolNormalizations == 1)
        assertTrue(instruction.state.lastNormalizationRule == "ACTION_REPLY")
    }

    @Test
    fun registeredToolActionEnvelopeExecutesWithoutCorrectionTurn() {
        val webTask = TaskState(
            id = "normalized-web",
            projectId = null,
            goal = "Знайди актуальні новини",
            status = TaskStatus.WAITING_MODEL
        )
        val state = controller.initial(webTask)

        val instruction = controller.interpret(
            """{"action":"web_search","parameters":{"query":"latest Poland news","limit":3}}""",
            state
        )

        assertTrue(instruction is ControllerInstruction.Execute)
        instruction as ControllerInstruction.Execute
        assertTrue(instruction.call.tool == "web.search")
        assertTrue(instruction.call.args["query"] == "latest Poland news")
        assertTrue(instruction.state.protocolRetries == 0)
        assertTrue(instruction.state.protocolNormalizations == 1)
        assertTrue(instruction.state.lastNormalizationRule == "REGISTERED_ACTION_ALIAS")
    }

    @Test
    fun unknownActionCannotSmuggleMutationThroughNormalizer() {
        val state = controller.initial(task())

        val instruction = controller.interpret(
            """{"action":"shell","tool":"file.write","args":{"path":"x","content":"bad"}}""",
            state
        )

        assertTrue(instruction is ControllerInstruction.AskModelAgain)
        instruction as ControllerInstruction.AskModelAgain
        assertTrue(instruction.state.protocolRetries == 1)
        assertTrue(instruction.feedback.contains("UNKNOWN_ACTION"))
    }

    @Test
    fun quotedToolJsonInProseNeverBecomesExecute() {
        val state = controller.initial(task())
        val raw =
            """Example only: {"tool":"file.write","args":{"path":"x","content":"bad"}} do not run it."""

        val instruction = controller.interpret(raw, state)

        assertFalse(instruction is ControllerInstruction.Execute)
    }

    @Test
    fun failedWebSearchCarriesTypedFailureEventIntoRecovery() {
        val webTask = TaskState(
            id = "typed-web-fail",
            projectId = null,
            goal = "Знайди останні новини в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val call = AgentDecision.ToolCall(
            tool = "web.search",
            args = mapOf("query" to "останні новини")
        )
        val initial = controller.initial(webTask)
        val executing = initial.copy(
            task = initial.task.copy(
                status = TaskStatus.EXECUTING,
                kernel = ContextKernel.before(initial.task.kernel, call)
            )
        )

        val transition = controller.afterTool(
            state = executing,
            call = call,
            ok = false,
            stdout = "",
            stderr = "",
            error = "Search unavailable",
            errorCode = "SEARCH_EXHAUSTED",
            failureClass = "DEPENDENCY_EXHAUSTED",
            retryable = false,
            dependency = "web.search"
        )

        val event = transition.failureEvent
        assertTrue(event != null)
        event!!
        assertTrue(event.source == FailureSource.TOOL)
        assertTrue(event.failureClass == FailureClass.DEPENDENCY_EXHAUSTED)
        assertTrue(event.effectClass == EffectClass.READ_ONLY)
        assertTrue(event.actionFamily == "web.search")
        assertTrue(event.attempt == 1)
        assertTrue(event.retryable == false)
    }

    @Test
    fun unknownMutationOutcomeCarriesTypedEventAndStopsWithoutReplay() {
        val call = AgentDecision.ToolCall(
            tool = "file.write",
            args = mapOf("path" to "demo.txt", "content" to "x")
        )
        val initial = controller.initial(task())

        val transition = controller.afterTool(
            state = initial,
            call = call,
            ok = false,
            stdout = "",
            stderr = "",
            error = "bridge transport lost after dispatch",
            outcomeUnknown = true,
            retryable = true,
            dependency = "bridge"
        )

        assertTrue(transition.stopReason != null)
        val event = transition.failureEvent
        assertTrue(event != null)
        event!!
        assertTrue(event.failureClass == FailureClass.UNKNOWN_EFFECT)
        assertTrue(event.effectClass == EffectClass.MUTATING_OR_EXECUTABLE)
        assertTrue(event.outcomeUnknown)
        assertTrue(event.retryable == false)
    }



    @Test
    fun projectScopedTaskBlocksUnrequestedMutationOutsideProject() {
        val state = controller.initial(
            TaskState(
                id = "project-scope",
                projectId = "e2e_step87",
                goal = "Створи e2e_step87/dir_a.py і перевір його.",
                status = TaskStatus.WAITING_MODEL
            )
        )

        val blocked = controller.interpret(
            """{"tool":"file.write","args":{"path":"workspace/mkdir_test.py","content":"print('helper')"}}""",
            state
        )

        assertTrue(blocked is ControllerInstruction.AskModelAgain)
        blocked as ControllerInstruction.AskModelAgain
        assertEquals(0, blocked.state.task.step)
        assertTrue(
            blocked.feedback.contains(
                "outside that project"
            )
        )
    }

    @Test
    fun projectScopedTaskAllowsMutationInsideProject() {
        val state = controller.initial(
            TaskState(
                id = "project-scope-ok",
                projectId = "e2e_step87",
                goal = "Створи e2e_step87/dir_a.py.",
                status = TaskStatus.WAITING_MODEL
            )
        )

        val allowed = controller.interpret(
            """{"tool":"file.write","args":{"path":"e2e_step87/dir_a.py","content":"print('ok')"}}""",
            state
        )

        assertTrue(allowed is ControllerInstruction.Execute)
    }

    @Test
    fun explicitlyNamedOutsideProjectMutationRemainsPossible() {
        val state = controller.initial(
            TaskState(
                id = "project-scope-explicit",
                projectId = "demo",
                goal = "У проекті demo створи також shared/config.py.",
                status = TaskStatus.WAITING_MODEL
            )
        )

        val allowed = controller.interpret(
            """{"tool":"file.write","args":{"path":"shared/config.py","content":"VALUE = 1"}}""",
            state
        )

        assertTrue(allowed is ControllerInstruction.Execute)
    }

    @Test
    fun protocolRepairRestoresExactActiveGoalAndPendingObligations() {
        val goal = """
            Працюй у проекті e2e_step87.
            Це Step 8.7 E2E, Task A.
            Прочитай через web.read:
            https://docs.python.org/3/library/pathlib.html#pathlib.Path.mkdir
            Потім створи e2e_step87/dir_a.py і e2e_step87/test_dir_a.py.
            Після запису файлів:
            - python.syntax_check для e2e_step87/dir_a.py
            - python.tests з cwd=e2e_step87
            Не використовуй ollama.generate для перевірки SHADOW/ACTIVE.
        """.trimIndent()

        var state = controller.initial(
            TaskState(
                id = "repair-goal",
                projectId = "e2e_step87",
                goal = goal,
                status = TaskStatus.WAITING_MODEL
            )
        )

        assertEquals(TaskIntent.CODE_WORK, state.intent)
        assertEquals(11, state.task.maxSteps)

        state = state.copy(
            completedRequiredTools = setOf("web.read"),
            task = state.task.copy(
                step = 1,
                lastTool = "context.snapshot",
                lastResult = "ok=true stdout={fixture}"
            )
        )

        val instruction = controller.interpret(
            """{"action":"shell","tool":"file.write","args":{"path":"x","content":"bad"}}""",
            state
        )

        assertTrue(instruction is ControllerInstruction.AskModelAgain)
        instruction as ControllerInstruction.AskModelAgain
        assertTrue(instruction.feedback.contains("PROTOCOL_REPAIR_MODE retry=1"))
        assertTrue(instruction.feedback.contains("ACTIVE_TASK"))
        assertTrue(instruction.feedback.contains("project=e2e_step87"))
        assertTrue(instruction.feedback.contains("intent=CODE_WORK"))
        assertTrue(instruction.feedback.contains("step=1/11"))
        assertTrue(
            instruction.feedback.contains(
                "https://docs.python.org/3/library/pathlib.html#pathlib.Path.mkdir"
            )
        )
        assertTrue(
            instruction.feedback.contains(
                "e2e_step87/dir_a.py"
            )
        )
        assertTrue(
            instruction.feedback.contains(
                "pending_required_tools=python.syntax_check,python.tests"
            )
        )
        assertTrue(
            instruction.feedback.contains(
                "completed_required_tools=web.read"
            )
        )
        assertTrue(
            instruction.feedback.contains(
                "Do NOT ask the user to restate it"
            )
        )
    }

    @Test
    fun protocolKernelStopsThirdInvalidEnvelope() {
        var state = controller.initial(
            TaskState(
                id = "protocol-budget",
                projectId = null,
                goal = "Поясни коротко різницю між RAM і SSD",
                status = TaskStatus.WAITING_MODEL
            )
        )
        val raw = """{"action":"shell","tool":"file.write","args":{"path":"x","content":"bad"}}"""

        val first = controller.interpret(raw, state)
        assertTrue(first is ControllerInstruction.AskModelAgain)
        first as ControllerInstruction.AskModelAgain
        assertTrue(first.feedback.contains("PROTOCOL_REPAIR_MODE retry=1"))
        assertTrue(first.feedback.contains("Return EXACTLY ONE JSON object"))
        assertTrue(first.feedback.contains("{\"tool\":\"registered.tool\",\"args\":{}}"))
        assertTrue(first.feedback.contains("NOTHING from it was run"))
        state = first.state

        val second = controller.interpret(raw, state)
        assertTrue(second is ControllerInstruction.AskModelAgain)
        state = second.state

        val third = controller.interpret(raw, state)
        assertTrue(third is ControllerInstruction.Stop)
        assertTrue(third.state.task.status == TaskStatus.FAILED)
    }

    @Test
    fun modelKernelStopsThirdTransientFailureButAllowsFirstTwo() {
        var state = controller.initial(
            TaskState(
                id = "model-budget",
                projectId = null,
                goal = "Поясни коротко різницю між RAM і SSD",
                status = TaskStatus.WAITING_MODEL
            )
        )

        val first = controller.onModelFailure(state, "temporary model transport failure")
        assertTrue(first is ControllerInstruction.AskModelAgain)
        state = first.state

        val second = controller.onModelFailure(state, "temporary model transport failure")
        assertTrue(second is ControllerInstruction.AskModelAgain)
        state = second.state

        val third = controller.onModelFailure(state, "temporary model transport failure")
        assertTrue(third is ControllerInstruction.Stop)
        assertTrue(third.state.task.status == TaskStatus.FAILED)
    }

}
