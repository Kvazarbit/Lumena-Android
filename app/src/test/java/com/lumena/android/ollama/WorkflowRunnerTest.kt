package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentController
import com.lumena.android.agent.core.ReflexOption
import com.lumena.android.agent.core.ReflexRuntimeAdvice
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRunnerTest {
    @Test
    fun contextTelemetryIsPublishedBeforeAndAfterModelCall() = runBlocking {
        val estimated = ModelContextUsage(
            promptTokens = 120,
            promptTokensExact = false,
            inputBudgetTokens = 1000,
            requestedContextWindowTokens = 2048,
            reservedOutputTokens = 384
        )
        val exact = estimated.copy(
            promptTokens = 98,
            promptTokensExact = true,
            generatedTokens = 12
        )
        val modelClient = object : ChatModelClient, ModelContextTelemetrySource {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> =
                Result.success("""{"reply":"ok"}""")

            override fun estimateContextUsage(
                messages: List<OllamaMessage>
            ): ModelContextUsage = estimated

            override fun lastContextUsage(): ModelContextUsage = exact
        }
        val seen = mutableListOf<ModelContextUsage>()
        val task = TaskState(
            id = "context-telemetry",
            projectId = null,
            goal = "Поясни коротко, що таке Python",
            status = TaskStatus.WAITING_MODEL
        )

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = null,
            model = "fixture"
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task,
            onContextUsage = { seen += it }
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        assertEquals(2, seen.size)
        assertFalse(seen.first().promptTokensExact)
        assertTrue(seen.last().promptTokensExact)
        assertEquals(98, seen.last().promptTokens)
        assertEquals(12, seen.last().generatedTokens)
    }

    @Test
    fun failedMandatoryWebPreflightAllowsOneVariantThenDegradesPartial() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls.incrementAndGet()
                return Result.success(
                    """{"tool":"web.search","args":{"query":"latest world headlines"},"reason":"one meaningful variant"}"""
                )
            }
        }

        val requests = mutableListOf<ToolRequest>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                requests += toolRequest
                return ToolResult(
                    ok = false,
                    tool = toolRequest.tool,
                    exitCode = 1,
                    stdout = """{"query":"latest news","results":[],"attempts":[{"provider":"duckduckgo","error":"Upstream HTTP 202"}]}""",
                    error = "Search unavailable. Do not invent current facts.",
                    errorCode = "SEARCH_EXHAUSTED",
                    failureClass = "DEPENDENCY_EXHAUSTED",
                    retryable = false,
                    dependency = "web.search"
                )
            }
        }

        val task = TaskState(
            id = "web-loop-regression",
            projectId = null,
            goal = "Знайди останні новини в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = bridge,
            model = "fixture"
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.PARTIAL, outcome.control.task.status)
        assertEquals(1, modelCalls.get())
        assertEquals(2, requests.size)
        assertEquals("web.search", requests[0].tool)
        assertEquals("web.search", requests[1].tool)
        assertEquals(2, outcome.control.semanticRecoverySpent)
        assertEquals(2, outcome.control.actionFamilyFailures["web.search"])
    }

    @Test
    fun failedSearchCanSwitchToAlternativeEvidenceTool() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                return when (modelCalls.incrementAndGet()) {
                    1 -> Result.success(
                        """{"tool":"http.get","args":{"url":"https://example.org"},"reason":"alternate evidence path"}"""
                    )
                    else -> Result.success(
                        """{"partial":true,"summary":"Alternative source was reachable; broader live search remained unavailable."}"""
                    )
                }
            }
        }

        val requests = mutableListOf<ToolRequest>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                requests += toolRequest
                return if (toolRequest.tool == "web.search") {
                    ToolResult(
                        ok = false,
                        tool = toolRequest.tool,
                        exitCode = 1,
                        error = "Search unavailable",
                        errorCode = "SEARCH_EXHAUSTED",
                        failureClass = "DEPENDENCY_EXHAUSTED",
                        retryable = false,
                        dependency = "web.search"
                    )
                } else {
                    ToolResult(
                        ok = true,
                        tool = toolRequest.tool,
                        exitCode = 0,
                        stdout = "Example source body"
                    )
                }
            }
        }

        val task = TaskState(
            id = "web-alt",
            projectId = null,
            goal = "Знайди актуальну інформацію в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(modelClient, bridge, "fixture").run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.PARTIAL, outcome.control.task.status)
        assertEquals(2, modelCalls.get())
        assertEquals(listOf("web.search", "http.get"), requests.map { it.tool })
        assertEquals(1, outcome.control.semanticRecoverySpent)
    }

    @Test
    fun successfulWebPreflightStillAllowsExactlyOneModelTurn() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls.incrementAndGet()
                return Result.success("""{"partial":true,"summary":"Search evidence received; source reading omitted in fixture."}""")
            }
        }

        val requests = mutableListOf<ToolRequest>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                requests += toolRequest
                return ToolResult(
                    ok = true,
                    tool = toolRequest.tool,
                    exitCode = 0,
                    stdout = """{"results":[{"title":"Example","url":"https://example.org"}]}"""
                )
            }
        }

        val task = TaskState(
            id = "web-success-regression",
            projectId = null,
            goal = "Знайди новини в інтернеті",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(modelClient, bridge, "fixture").run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        assertEquals(1, modelCalls.get())
        assertEquals(1, requests.size)
        assertEquals("web.search", requests.single().tool)
    }


    @Test
    fun malformedProtocolIsNotFedBackAndNextStrictRepairCanRecover() = runBlocking {
        val modelCalls = AtomicInteger(0)
        var secondTurnMessages: List<OllamaMessage> = emptyList()
        val malformed = """{"reply":"broken""""

        val modelClient = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                return when (modelCalls.incrementAndGet()) {
                    1 -> Result.success(malformed)
                    else -> {
                        secondTurnMessages = messages
                        Result.success(
                            """{"reply":"Recovered through strict JSON repair."}"""
                        )
                    }
                }
            }
        }

        val task = TaskState(
            id = "protocol-repair-e2e",
            projectId = "demo_project",
            goal = "Поясни коротко стан тесту",
            status = TaskStatus.WAITING_MODEL
        )

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = null,
            model = "fixture"
        ).run(
            history = listOf(
                OllamaMessage("user", task.goal)
            ),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.DONE, outcome.control.task.status)
        assertEquals(2, modelCalls.get())
        assertTrue(
            secondTurnMessages.any {
                it.role == "user" &&
                    it.content.contains("PROTOCOL_REPAIR_MODE retry=1")
            }
        )
        assertFalse(
            secondTurnMessages.any {
                it.role == "assistant" &&
                    it.content == malformed
            }
        )
        assertTrue(
            outcome.text.contains(
                "Recovered through strict JSON repair."
            )
        )
    }

    @Test
    fun exhaustedContextFailureStopsAfterOneModelAttempt() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls.incrementAndGet()
                return Result.failure(
                    IllegalStateException("Ollama stream error: context length exceeded; too many tokens")
                )
            }
        }

        val task = TaskState(
            id = "context-stop-regression",
            projectId = null,
            goal = "Поясни коротко стан системи",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(modelClient, null, "fixture").run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Failed)
        assertEquals(1, modelCalls.get())
        outcome as WorkflowOutcome.Failed
        assertEquals(TaskStatus.FAILED, outcome.control.task.status)
    }
    @Test
    fun modelRuntimeRecoveryAndToolRecoveryUseIndependentBudgets() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val modelClient = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                return when (modelCalls.incrementAndGet()) {
                    1 -> Result.failure(IllegalStateException("temporary model transport failure"))
                    2 -> Result.success(
                        """{"tool":"file.read","args":{"path":"missing.txt"},"reason":"inspect requested file"}"""
                    )
                    else -> Result.success(
                        """{"partial":true,"summary":"The file could not be verified after bounded recovery."}"""
                    )
                }
            }
        }

        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult =
                ToolResult(
                    ok = false,
                    tool = toolRequest.tool,
                    exitCode = 1,
                    error = "FileNotFoundError: No such file",
                    failureClass = "STATE_DRIFT",
                    retryable = false,
                    dependency = "filesystem"
                )
        }

        val task = TaskState(
            id = "budget-separation",
            projectId = null,
            goal = "Виконай контрольовану перевірку інструментом",
            status = TaskStatus.WAITING_MODEL
        )
        val outcome = WorkflowRunner(modelClient, bridge, "fixture").run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.PARTIAL, outcome.control.task.status)
        assertEquals(3, modelCalls.get())
        assertEquals(0, outcome.control.modelFailures)
        assertEquals(1, outcome.control.semanticRecoverySpent)
        assertEquals(1, outcome.control.actionFamilyFailures["file.read"])
    }

    @Test
    fun strongReflexAdviceReachesNextModelTurnWithoutExecutingAnythingByItself() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val providerCalls = AtomicInteger(0)
        var sawReflexAdvice = false

        val modelClient = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                return when (modelCalls.incrementAndGet()) {
                    1 -> Result.success(
                        """{"tool":"file.read","args":{"path":"missing.txt"},"reason":"inspect target"}"""
                    )
                    else -> {
                        sawReflexAdvice = messages.any { message ->
                            message.role == "system" &&
                                message.content.contains(
                                    "REFLEX ADVICE (advisory only; not permission)"
                                ) &&
                                message.content.contains("option=TRY_ALTERNATIVE")
                        }
                        Result.success(
                            """{"partial":true,"summary":"Fixture stops after advisory recovery context."}"""
                        )
                    }
                }
            }
        }

        val requests = mutableListOf<ToolRequest>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                requests += toolRequest
                return ToolResult(
                    ok = false,
                    tool = toolRequest.tool,
                    exitCode = 1,
                    error = "FileNotFoundError: No such file",
                    failureClass = "STATE_DRIFT",
                    retryable = false,
                    dependency = "filesystem"
                )
            }
        }

        val task = TaskState(
            id = "reflex-advisory-e2e",
            projectId = null,
            goal = "Виконай контрольовану перевірку файлу",
            status = TaskStatus.WAITING_MODEL
        )
        val initial = AgentController()
            .initial(task)
            .copy(preflightCompleted = true)

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = bridge,
            model = "fixture",
            reflexAdviceProvider = { _, candidates, _ ->
                providerCalls.incrementAndGet()
                assertTrue(ReflexOption.TRY_ALTERNATIVE in candidates.allowed)
                ReflexRuntimeAdvice(
                    option = ReflexOption.TRY_ALTERNATIVE,
                    confidence = 0.95,
                    evidenceCount = 8,
                    calibrated = false
                )
            }
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task,
            control = initial
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        assertEquals(2, modelCalls.get())
        assertEquals(1, providerCalls.get())
        assertEquals(1, requests.size)
        assertEquals("file.read", requests.single().tool)
        assertTrue(sawReflexAdvice)
    }

    @Test
    fun lowConfidenceReflexAdviceIsNotInjectedIntoModelContext() = runBlocking {
        val modelCalls = AtomicInteger(0)
        var sawReflexAdvice = false

        val modelClient = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                return when (modelCalls.incrementAndGet()) {
                    1 -> Result.success(
                        """{"tool":"file.read","args":{"path":"missing.txt"},"reason":"inspect target"}"""
                    )
                    else -> {
                        sawReflexAdvice = messages.any { message ->
                            message.role == "system" &&
                                message.content.contains("REFLEX ADVICE")
                        }
                        Result.success(
                            """{"partial":true,"summary":"No strong reflex evidence."}"""
                        )
                    }
                }
            }
        }

        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult =
                ToolResult(
                    ok = false,
                    tool = toolRequest.tool,
                    exitCode = 1,
                    error = "No such file",
                    failureClass = "STATE_DRIFT",
                    retryable = false,
                    dependency = "filesystem"
                )
        }

        val task = TaskState(
            id = "reflex-low-confidence",
            projectId = null,
            goal = "Виконай контрольовану перевірку файлу",
            status = TaskStatus.WAITING_MODEL
        )
        val initial = AgentController()
            .initial(task)
            .copy(preflightCompleted = true)

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = bridge,
            model = "fixture",
            reflexAdviceProvider = { _, _, _ ->
                ReflexRuntimeAdvice(
                    option = ReflexOption.TRY_ALTERNATIVE,
                    confidence = 0.50,
                    evidenceCount = 8,
                    calibrated = false
                )
            }
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task,
            control = initial
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        assertEquals(2, modelCalls.get())
        assertFalse(sawReflexAdvice)
    }

    @Test
    fun illegalReflexOptionIsRejectedAndNeverBecomesExecution() = runBlocking {
        val modelCalls = AtomicInteger(0)
        val progress = mutableListOf<String>()
        val requests = mutableListOf<ToolRequest>()

        val modelClient = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                return when (modelCalls.incrementAndGet()) {
                    1 -> Result.success(
                        """{"tool":"file.read","args":{"path":"missing.txt"},"reason":"inspect"}"""
                    )
                    else -> Result.success(
                        """{"partial":true,"summary":"Illegal reflex choice was ignored."}"""
                    )
                }
            }
        }

        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                requests += toolRequest
                return ToolResult(
                    ok = false,
                    tool = toolRequest.tool,
                    exitCode = 1,
                    error = "No such file",
                    failureClass = "STATE_DRIFT",
                    retryable = false,
                    dependency = "filesystem"
                )
            }
        }

        val task = TaskState(
            id = "reflex-illegal-choice",
            projectId = null,
            goal = "Перевір файл",
            status = TaskStatus.WAITING_MODEL
        )
        val initial = AgentController()
            .initial(task)
            .copy(preflightCompleted = true)

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = bridge,
            model = "fixture",
            reflexAdviceProvider = { _, _, _ ->
                ReflexRuntimeAdvice(
                    option = ReflexOption.RETRY_VARIANT,
                    confidence = 0.99,
                    evidenceCount = 20,
                    calibrated = false
                )
            }
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task,
            control = initial,
            onProgress = { progress += it }
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        assertEquals(1, requests.size)
        assertTrue(
            progress.any {
                it.contains(
                    "rejected outside constitutional candidates"
                )
            }
        )
    }

    @Test
    fun unknownMutationOutcomeStopsBeforeReflexProviderAndNeverReplays() = runBlocking {
        val providerCalls = AtomicInteger(0)
        val requests = mutableListOf<ToolRequest>()

        val modelClient = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> = Result.success(
                """{"tool":"file.write","args":{"path":"demo.txt","content":"x"},"reason":"controlled mutation"}"""
            )
        }

        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                requests += toolRequest
                return ToolResult(
                    ok = false,
                    tool = toolRequest.tool,
                    error = "bridge transport lost after dispatch",
                    outcomeUnknown = true,
                    retryable = false,
                    dependency = "bridge"
                )
            }
        }

        val task = TaskState(
            id = "reflex-unknown-mutation",
            projectId = null,
            goal = "Створи контрольований тестовий файл",
            status = TaskStatus.WAITING_MODEL
        )
        val initial = AgentController()
            .initial(task)
            .copy(preflightCompleted = true)

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = bridge,
            model = "fixture",
            reflexAdviceProvider = { _, _, _ ->
                providerCalls.incrementAndGet()
                ReflexRuntimeAdvice(
                    option = ReflexOption.STOP,
                    confidence = 1.0,
                    evidenceCount = 10,
                    calibrated = false
                )
            }
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task,
            control = initial,
            isApprovedForTask = { _, _ -> true }
        )

        assertTrue(outcome is WorkflowOutcome.Failed)
        assertEquals(1, requests.size)
        assertEquals(0, providerCalls.get())
    }
    @Test
    fun evidenceProviderReachesModelContextAndProgressTrace() = runBlocking {
        var sawEvidenceInSystem = false
        val progress = mutableListOf<String>()

        val modelClient = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                sawEvidenceInSystem = messages.any { message ->
                    message.role == "system" &&
                        message.content.contains("EVIDENCE GRAPH") &&
                        message.content.contains(
                            "EVIDENCE [RETRIEVED] Android docs"
                        ) &&
                        message.content.contains(
                            "not permission, execution authority, or proof that the whole goal is complete"
                        )
                }
                return Result.success(
                    """{"reply":"Evidence context was consumed without executing a tool."}"""
                )
            }
        }

        val task = TaskState(
            id = "evidence-provider-context",
            projectId = "lumena",
            goal = "Explain the current verified Android documentation context",
            status = TaskStatus.WAITING_MODEL
        )

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = null,
            model = "fixture",
            evidenceProvider = {
                listOf(
                    "EVIDENCE [RETRIEVED] Android docs · source=https://developer.android.com/docs · verified tool evidence only"
                )
            }
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task,
            onProgress = { progress += it }
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        assertTrue(sawEvidenceInSystem)
        assertTrue(
            progress.any {
                it.contains("EVIDENCE GRAPH CONTEXT") &&
                    it.contains("Android docs")
            }
        )
    }

    @Test
    fun evidenceProviderFailureIsVisibleButDoesNotStopModelTurn() = runBlocking {
        val progress = mutableListOf<String>()
        val modelClient = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> = Result.success(
                """{"reply":"Continue from current verified task state."}"""
            )
        }

        val task = TaskState(
            id = "evidence-provider-failure",
            projectId = null,
            goal = "Answer without inventing unavailable evidence",
            status = TaskStatus.WAITING_MODEL
        )

        val outcome = WorkflowRunner(
            modelClient = modelClient,
            bridge = null,
            model = "fixture",
            evidenceProvider = {
                throw IllegalStateException("fixture evidence store failure")
            }
        ).run(
            history = listOf(OllamaMessage("user", task.goal)),
            task = task,
            onProgress = { progress += it }
        )

        assertTrue(outcome is WorkflowOutcome.Finished)
        assertTrue(
            progress.any {
                it.contains("EVIDENCE GRAPH CONTEXT · unavailable")
            }
        )
    }


}
