package com.lumena.android.diagnostics

import com.lumena.android.agent.core.*
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.lumena.android.ollama.ChatModelClient
import com.lumena.android.ollama.OllamaMessage
import com.lumena.android.ollama.WorkflowOutcome
import com.lumena.android.ollama.WorkflowRunner
import kotlinx.coroutines.runBlocking
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

    @Test fun duplicateSearchLoopMustYieldPartialBeforeProtocolTurnCap() = runBlocking {
        val goal = "Аудит коду: file.patch і file.write існують, mutate не існує. " +
            "Модель описувала виконання; фактичних TOOL_RESULT немає."
        var turns = 0
        val model = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                // Four distinct queries, followed by the fourth repeatedly.
                // This fixture is one possible causal path, not the owner's
                // undisclosed original request arguments.
                val query = minOf(++turns, 4)
                return Result.success("""{"tool":"project.search","args":{"cwd":"@fixture-project","query":"missing-$query","limit":"5"},"reason":"Inspect audit evidence"}""")
            }
        }
        val calls = mutableListOf<ToolRequest>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                calls += toolRequest
                return ToolResult(true, toolRequest.tool, exitCode = 0,
                    stdout = """{"cwd":"@fixture-project","source_digest":"unchanged-fixture","matches":[]}""")
            }
        }
        val outcome = WorkflowRunner(model, bridge, "fixture-model").run(
            listOf(OllamaMessage("user", goal)), TaskState("diagnostic-loop", null, goal))
        val task = when (outcome) {
            is WorkflowOutcome.Failed -> outcome.control.task
            is WorkflowOutcome.Finished -> outcome.control.task
            is WorkflowOutcome.NeedsConfirmation -> outcome.pending.control.task
        }
        println("DIAG_TRACE loop model_turns=$turns receipts=${task.kernel.observed} " +
            "step=${task.step}/${task.maxSteps} status=${task.status} " +
            "world_revision=${task.kernel.worldRevision} errors=${task.errors}")
        assertTrue("Fixture reached an unexpected mutation", calls.all {
            ToolRegistry.get(it.tool)?.risk == ToolRisk.READ_ONLY
        })
        assertEquals("Fixture fabricated or lost a receipt", calls.size, task.kernel.observed)
        assertEquals(0, task.kernel.worldRevision)
        assertFalse("SEARCH_LOOP_REACHED_PROTOCOL_CAP", task.errors.any { it.contains("Protocol turn cap") })
    }
}
