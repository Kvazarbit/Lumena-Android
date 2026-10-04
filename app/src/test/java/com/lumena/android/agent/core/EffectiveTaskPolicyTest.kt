package com.lumena.android.agent.core

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveTaskPolicyTest {
    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                value.toByteArray(
                    Charsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff
                )
            }

    @Test
    fun currentReadOnlyInstructionOverridesOlderMutationRoot() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Онови aquarium.html: виправ фізику і запусти тести.",
                    currentInstruction =
                        "На цьому кроці тільки прочитай aquarium.html. Нічого не змінюй і не використовуй file.write, file.patch або python.run."
                )

        assertEquals(
            listOf(ToolRisk.READ_ONLY),
            policy.allowedRisks
        )
        assertEquals(
            setOf(
                "file.write",
                "file.patch",
                "python.run"
            ),
            policy.forbiddenTools.toSet()
        )
        assertTrue(
            policy.requiredTools.isEmpty()
        )
        assertEquals(
            TaskIntent.FILE_INSPECTION,
            TaskIntentRouter
                .route(policy)
                .intent
        )
        assertTrue(
            EffectiveTaskPolicyCompiler
                .validateTool(
                    policy,
                    AgentDecision.ToolCall(
                        "file.read",
                        mapOf(
                            "path" to
                                "aquarium.html"
                        )
                    )
                )
                .allowed
        )
        assertFalse(
            EffectiveTaskPolicyCompiler
                .validateTool(
                    policy,
                    AgentDecision.ToolCall(
                        "file.write",
                        mapOf(
                            "path" to
                                "aquarium.html",
                            "content" to
                                "x"
                        )
                    )
                )
                .allowed
        )
    }

    @Test
    fun readOnlyCuesWorkAcrossSupportedLanguages() {
        val prompts =
            listOf(
                "Тільки прочитай demo.py і нічого не змінюй.",
                "Только прочитай demo.py и ничего не изменяй.",
                "Only read demo.py. Do not modify it.",
                "Tylko przeczytaj demo.py, bez zmian."
            )

        prompts.forEach { prompt ->
            val policy =
                EffectiveTaskPolicyCompiler
                    .compile(
                        rootGoal = prompt,
                        currentInstruction =
                            prompt
                    )
            assertEquals(
                prompt,
                listOf(ToolRisk.READ_ONLY),
                policy.allowedRisks
            )
        }
    }

    @Test
    fun quotedConstraintTextDoesNotBecomePolicy() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Прочитай README",
                    currentInstruction =
                        "Поясни цитату «не використовуй file.write», а потім прочитай README."
                )

        assertFalse(
            "file.write" in
                policy.forbiddenTools
        )
    }

    @Test
    fun notNowReadOnlyWinsOverOldProjectGoal() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Виправ demo.py і перевір.",
                    currentInstruction =
                        "Зараз не змінюй demo.py, тільки прочитай його."
                )

        assertEquals(
            listOf(ToolRisk.READ_ONLY),
            policy.allowedRisks
        )
    }

    @Test
    fun laterSequencedMutationCanReenableMutation() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Онови demo.txt",
                    currentInstruction =
                        "Не змінюй файл зараз; потім виправ його через file.patch."
                )

        assertTrue(
            ToolRisk.MUTATING in
                policy.allowedRisks
        )
        assertTrue(
            "file.patch" in
                policy.requiredTools
        )
        assertFalse(
            "file.patch" in
                policy.forbiddenTools
        )
    }

    @Test
    fun sameClauseContradictionRequiresClarification() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Виправ demo.txt",
                    currentInstruction =
                        "Виправ файл, але нічого не змінюй."
                )

        assertEquals(
            TaskPolicyDisposition
                .NEEDS_CLARIFICATION,
            policy.disposition
        )
        assertFalse(
            EffectiveTaskPolicyCompiler
                .validateTool(
                    policy,
                    AgentDecision.ToolCall(
                        "file.write",
                        mapOf(
                            "path" to
                                "demo.txt",
                            "content" to
                                "x"
                        )
                    )
                )
                .allowed
        )
    }

    @Test
    fun noExecuteStillAllowsExplicitMutation() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Виправ demo.txt",
                    currentInstruction =
                        "Не запускай python.run. Виправ файл через file.patch."
                )

        assertTrue(
            ToolRisk.MUTATING in
                policy.allowedRisks
        )
        assertFalse(
            ToolRisk.EXECUTABLE in
                policy.allowedRisks
        )
        assertTrue(
            "python.run" in
                policy.forbiddenTools
        )
        assertTrue(
            "file.patch" in
                policy.requiredTools
        )
    }

    @Test
    fun laterPositiveToolDirectiveOverridesEarlierNegativeOne() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Онови demo.txt",
                    currentInstruction =
                        "Не використовуй file.write; потім використай file.write для нового файлу."
                )

        assertFalse(
            "file.write" in
                policy.forbiddenTools
        )
        assertTrue(
            "file.write" in
                policy.requiredTools
        )
    }

    @Test
    fun nestedMutatingBatchIsBlockedByReadOnlyPolicy() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Проаналізуй файли",
                    currentInstruction =
                        "Тільки прочитай файли, нічого не змінюй."
                )
        val decision =
            EffectiveTaskPolicyCompiler
                .validateTool(
                    policy,
                    AgentDecision.ToolCall(
                        tool =
                            "inspect.batch",
                        args =
                            mapOf(
                                "requests" to
                                    """[{"tool":"file.write","args":{"path":"x.txt","content":"bad"}}]"""
                            )
                    )
                )

        assertFalse(decision.allowed)
        assertTrue(
            decision.reason
                .orEmpty()
                .contains(
                    "BATCH_CHILD_BLOCKED"
                )
        )
    }

    @Test
    fun localUnknownWriteBlocksReplayUntilSameTargetRead() {
        val write =
            AgentDecision.ToolCall(
                tool = "file.write",
                args =
                    mapOf(
                        "path" to
                            "demo.txt",
                        "content" to
                            "hello"
                    )
            )
        val unresolved =
            PolicyUnresolvedEffect(
                sourceTaskRef =
                    "a".repeat(64),
                tool = "file.write",
                requestSignature =
                    ContextKernel.signature(
                        write
                    ),
                targetRef =
                    sha256("demo.txt"),
                origin =
                    HistoricalRecordOrigin
                        .LOCAL_CURRENT
            )
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Онови demo.txt",
                    currentInstruction =
                        "Запиши demo.txt",
                    unresolvedEffects =
                        listOf(unresolved)
                )

        val blocked =
            EffectiveTaskPolicyCompiler
                .validateTool(
                    policy,
                    write
                )
        assertFalse(blocked.allowed)
        assertTrue(
            blocked.reason
                .orEmpty()
                .contains(
                    "RECONCILIATION_REQUIRED"
                )
        )

        val reconciled =
            EffectiveTaskPolicyCompiler
                .afterTool(
                    policy,
                    AgentDecision.ToolCall(
                        tool =
                            "file.read",
                        args =
                            mapOf(
                                "path" to
                                    "demo.txt"
                            )
                    ),
                    ok = true
                )

        assertTrue(
            reconciled
                .unresolvedEffects
                .isEmpty()
        )
        assertTrue(
            EffectiveTaskPolicyCompiler
                .validateTool(
                    reconciled,
                    write
                )
                .allowed
        )
    }

    @Test
    fun importedUnknownEffectIsAdvisoryNotPermissionGate() {
        val write =
            AgentDecision.ToolCall(
                tool = "file.write",
                args =
                    mapOf(
                        "path" to
                            "demo.txt",
                        "content" to
                            "hello"
                    )
            )
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Онови demo.txt",
                    currentInstruction =
                        "Запиши demo.txt",
                    unresolvedEffects =
                        listOf(
                            PolicyUnresolvedEffect(
                                sourceTaskRef =
                                    "a".repeat(64),
                                tool =
                                    "file.write",
                                requestSignature =
                                    ContextKernel
                                        .signature(
                                            write
                                        ),
                                targetRef =
                                    sha256(
                                        "demo.txt"
                                    ),
                                origin =
                                    HistoricalRecordOrigin
                                        .IMPORTED_ADVISORY
                            )
                        )
                )

        assertTrue(
            EffectiveTaskPolicyCompiler
                .validateTool(
                    policy,
                    write
                )
                .allowed
        )
    }

    @Test
    fun ordinaryAllowedReadStillWorks() {
        val policy =
            EffectiveTaskPolicyCompiler
                .compile(
                    rootGoal =
                        "Прочитай README",
                    currentInstruction =
                        "Прочитай README"
                )

        assertTrue(
            EffectiveTaskPolicyCompiler
                .validateTool(
                    policy,
                    AgentDecision.ToolCall(
                        "file.read",
                        mapOf(
                            "path" to "README"
                        )
                    )
                )
                .allowed
        )
    }
}
