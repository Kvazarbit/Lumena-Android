package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression from the real ci2188 phone E2E on 2026-10-15.
 * The system really searched/read Python.org, then falsely required file.write.
 * No network or executable tools are run by these tests.
 */
class ReadOnlyWebIntentRegressionTest {
    private val phonePrompt = """
        Проведи контрольний вебпошук, лише читання.
        1. Знайди через web.search офіційну сторінку про Python на python.org.
        2. Прочитай знайдену сторінку через web.read.
        3. Назви заголовок сторінки, її URL та один факт із прочитаного тексту.
        Не використовуй python.run, file.write або інші виконавчі чи змінювальні інструменти.
        Не оголошуй DONE без реальних результатів інструментів.
        Якщо пошук або читання не відбулися, чесно повідом про незавершену задачу.
    """.trimIndent()

    @Test
    fun exactPhonePromptIsPublicWebAndNotCodeWork() {
        val policy = EffectiveTaskPolicyCompiler.compile(
            rootGoal = phonePrompt,
            currentInstruction = phonePrompt
        )
        assertEquals(TaskPolicyDisposition.READY, policy.disposition)
        assertEquals(listOf(ToolRisk.READ_ONLY), policy.allowedRisks)
        assertTrue(ToolCapability.WRITE_WORKSPACE in policy.deniedCapabilities)
        assertEquals(setOf("web.search", "web.read"), policy.requiredTools.toSet())
        assertEquals(setOf("python.run", "file.write"), policy.forbiddenTools.toSet())

        val profile = TaskIntentRouter.route(policy)
        assertEquals(TaskIntent.PUBLIC_WEB, profile.intent)
        assertFalse(profile.recommendedTools.contains("file.write"))

        val sanitized = EffectiveTaskPolicyCompiler.routingText(policy)
        assertFalse(sanitized.contains("python.run"))
        assertFalse(sanitized.contains("file.write"))
        assertTrue(sanitized.contains("python.org"))

        for (tool in listOf("python.run", "file.write", "file.patch", "python.tests")) {
            val decision = EffectiveTaskPolicyCompiler.validateTool(
                policy, AgentDecision.ToolCall(tool, emptyMap())
            )
            assertFalse("Should block ${tool}: ${decision.reason}", decision.allowed)
        }
        for (tool in listOf("web.search", "web.read", "context.snapshot")) {
            assertTrue(
                "Expected read-only ${tool} to be allowed",
                EffectiveTaskPolicyCompiler.validateTool(
                    policy, AgentDecision.ToolCall(tool, emptyMap())
                ).allowed
            )
        }
    }

    @Test
    fun exactPhonePromptHasOnlyWebEvidenceRequirements() {
        val control = AgentController().initial(
            TaskState(
                id = "ci2188-phone-python-web-e2e",
                projectId = null,
                goal = phonePrompt,
                status = TaskStatus.WAITING_MODEL
            )
        )
        assertEquals(TaskIntent.PUBLIC_WEB, control.intent)
        assertEquals(setOf("web.search", "web.read"), control.requiredTools)
        val contract = control.task.goalContract
        val requiredIds = contract.criteria.filter { it.required }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "operational-tool-evidence",
                "required-tool:web.search",
                "required-tool:web.read",
                "source-content-evidence"
            ),
            requiredIds
        )
        assertFalse(requiredIds.contains("required-tool:file.write"))
        assertFalse(requiredIds.contains("code-content-evidence"))
        assertFalse(GoalContractPolicy.allMandatoryPassed(contract))
    }

    @Test
    fun realSearchAndSourceReadCanPassTheCorrectContractWithoutFileWrites() {
        var contract = GoalContractPolicy.initial(
            intent = TaskIntentRouter.route(phonePrompt).intent,
            requiredTools = TaskIntentRouter.explicitRequiredTools(phonePrompt),
            visualRequired = false,
            goal = EffectiveTaskPolicyCompiler.routingText(
                EffectiveTaskPolicyCompiler.compile(phonePrompt, phonePrompt)
            )
        )
        var kernel = ContextKernelState()
        val calls = listOf(
            AgentDecision.ToolCall(
                "context.snapshot", emptyMap()
            ),
            AgentDecision.ToolCall(
                "web.search", mapOf("query" to "official Python page python.org")
            ),
            AgentDecision.ToolCall(
                "web.read", mapOf("url" to "https://www.python.org/")
            )
        )
        for (call in calls) {
            kernel = ContextKernel.record(kernel, call, true, "fixture tool result")
            contract = GoalContractPolicy.afterTool(
                contract, call, ok = true, kernel = kernel,
                stdout = if (call.tool == "web.read")
                    """{"url":"https://www.python.org/","title":"Welcome to Python.org","text":"Python supports functions and control-flow statements."}"""
                else "fixture"
            )
        }
        assertEquals(3, kernel.observed)
        assertTrue(GoalContractPolicy.allMandatoryPassed(contract))
        assertEquals(4, contract.criteria.count { it.required && it.status == CriterionStatus.PASSED })
        assertEquals(
            VerificationStrength.INDEPENDENT_TOOL_RESULT,
            contract.criteria.first { it.id == "source-content-evidence" }.evidence.single().strength
        )
    }

    @Test
    fun earlierNegatedToolCannotReopenFollowingToolViaRunSubstring() {
        val prompt = "Не використовуй python.run, file.write або file.patch. " +
            "Знайди через web.search і прочитай через web.read."
        val policy = EffectiveTaskPolicyCompiler.compile(prompt, prompt)
        assertEquals(setOf("python.run", "file.write", "file.patch"), policy.forbiddenTools.toSet())
        assertEquals(setOf("web.search", "web.read"), policy.requiredTools.toSet())
        assertEquals(TaskIntent.PUBLIC_WEB, TaskIntentRouter.route(policy).intent)
    }

    @Test
    fun laterRealPositiveDirectiveCanStillReopenExplicitTool() {
        val prompt = "Не використовуй python.run, file.write; потім використай file.write для тестового файлу."
        val policy = EffectiveTaskPolicyCompiler.compile(prompt, prompt)
        assertTrue("python.run" in policy.forbiddenTools)
        assertFalse("file.write" in policy.forbiddenTools)
        assertTrue("file.write" in policy.requiredTools)
    }

    @Test
    fun readOnlyModePhrasesForTwoAdditionalLanguagesAreRecognized() {
        for (prompt in listOf(
            "Знайди сторінку лише для читання, без запису файлів.",
            "Найди сайт, только чтения, без изменения файлов.",
            "Znajdź dokument, tylko do odczytu, bez zmian."
        )) {
            val policy = EffectiveTaskPolicyCompiler.compile(prompt, prompt)
            assertEquals(prompt, listOf(ToolRisk.READ_ONLY), policy.allowedRisks)
        }
    }
}
