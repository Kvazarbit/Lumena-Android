package com.lumena.android.diagnostics

import com.lumena.android.agent.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Desired-behavior tests, not a statement that the phone incident is fixed. */
class PhoneReport2296DiagnosticTest {
    @Before fun prepare() { BridgeCompatibility.observe("0.31") }
    @After fun cleanUp() { BridgeCompatibility.clearForTests() }

    private fun contract(text: String): GoalContract {
        val policy = EffectiveTaskPolicyCompiler.compile(text, text)
        return GoalContractPolicy.initial(
            TaskIntentRouter.route(policy).intent, policy.requiredTools.toSet(), false, text
        )
    }

    @Test fun unquotedAuditMustNotRequireWrites() {
        val text = "Аудит помилки: назви file.patch і file.write — це перелік інструментів; " +
            "mutate не існує. Модель говорила про виконання, але TOOL_RESULT немає."
        val policy = EffectiveTaskPolicyCompiler.compile(text, text)
        assertEquals("AUDIT_MENTION_BECAME_REQUIRED_TOOL", emptyList<String>(), policy.requiredTools)
    }

    @Test fun quotedMentionsDoNotCreateRequiredTools() {
        for (names in listOf("`file.patch` і `file.write`", "«file.patch» і «file.write»",
                "\"file.patch\" і \"file.write\"", "'file.patch' і 'file.write'")) {
            val text = "Аудит: $names — перелік інструментів, mutate не існує."
            assertTrue("QUOTED_TOOL_REQUIRED: $names",
                EffectiveTaskPolicyCompiler.compile(text, text).requiredTools.isEmpty())
        }
    }

    @Test fun quotedAuditMustNotBecomeCodeWork() {
        val text = "Аудит коду: `file.patch` і `file.write` — перелік інструментів, mutate не існує."
        assertNotEquals("QUOTED_AUDIT_BECAME_CODE_WORK", TaskIntent.CODE_WORK,
            TaskIntentRouter.route(text).intent)
    }

    @Test fun pastActionsMustNotBecomeCurrentCodeWork() {
        val text = "Звіт: модель виправила код у GitHub і написала, що зробила patch. " +
            "Це опис минулої сесії; TOOL_RESULT відсутній."
        assertNotEquals("PAST_ACTION_BECAME_CODE_WORK", TaskIntent.CODE_WORK,
            TaskIntentRouter.route(text).intent)
    }

    @Test fun auditContractMustNotDemandMutation() {
        val text = "Аудит коду: file.patch і file.write існують, mutate не існує. " +
            "Модель описувала виконання; фактичних TOOL_RESULT немає."
        val unwanted = contract(text).criteria.filter {
            it.id in setOf("required-tool:file.patch", "required-tool:file.write", "code-action-evidence")
        }.map { it.id }
        assertEquals("AUDIT_CONTRACT_DEMANDS_MUTATION", emptyList<String>(), unwanted)
    }

    @Test fun positiveCommandStillRequiresItsTool() {
        val text = "Тепер виправ demo.py через file.patch"
        val policy = EffectiveTaskPolicyCompiler.compile(text, text)
        assertEquals(TaskIntent.CODE_WORK, TaskIntentRouter.route(policy).intent)
        assertTrue("AFFIRMATIVE_COMMAND_LOST", "file.patch" in policy.requiredTools)
    }

    @Test fun negativeCommandStillBlocksWritesAndExecution() {
        val text = "Не використовуй file.write, file.patch або python.run; тільки прочитай файл."
        val policy = EffectiveTaskPolicyCompiler.compile(text, text)
        assertTrue("NEGATED_TOOLS_REQUIRED", policy.requiredTools.isEmpty())
        for (tool in listOf("file.write", "file.patch", "python.run")) {
            assertTrue("NEGATION_LOST: $tool", tool in policy.forbiddenTools)
            assertFalse(EffectiveTaskPolicyCompiler.validateTool(
                policy, AgentDecision.ToolCall(tool, emptyMap())).allowed)
        }
    }

    private fun search(query: String) = AgentDecision.ToolCall("project.search",
        mapOf("cwd" to "@fixture-project", "query" to query, "limit" to "5"))

    @Test fun identicalSearchWithIdenticalReceiptsIsDetected() {
        val call = search("SemanticMemoryPanel")
        var kernel = ContextKernelState()
        repeat(2) { kernel = ContextKernel.record(kernel, call, true, "same source and result") }
        assertTrue("EXACT_DUPLICATE_NOT_DETECTED", ContextKernel.repeatedObservation(kernel, call))
    }

    @Test fun distinctUsefulQueriesAreNotBlockedBySameRepositoryDigest() {
        val output = "{\"cwd\":\"@fixture-project\",\"source_digest\":\"unchanged-fixture\"}"
        var kernel = ContextKernelState()
        for (query in listOf("SemanticMemoryPanel", "rememberSaveable")) {
            kernel = ContextKernel.record(kernel, search(query), true, output)
        }
        assertFalse("USEFUL_DISTINCT_QUERY_BLOCKED",
            ContextKernel.repeatedObservation(kernel, search("SemanticLiteRtModelStore")))
        assertEquals(0, kernel.worldRevision)
    }

    @Test fun variedQueriesWithNoNewResultsAreNotMistakenForExactDuplicateProof() {
        // A limitation measurement. This does not prove the owner's five args
        // were identical or that all distinct queries should be blocked.
        var kernel = ContextKernelState()
        for (n in 1..5) {
            val call = search("missing-$n")
            assertFalse(ContextKernel.repeatedObservation(kernel, call))
            kernel = ContextKernel.record(kernel, call, true, "{\"matches\":[],\"source_digest\":\"same\"}")
        }
        assertEquals(5, kernel.observed)
        assertEquals(0, kernel.worldRevision)
    }
}
