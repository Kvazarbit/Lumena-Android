package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentController
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline end-to-end execution using the REAL controller and ToolGate. */
class WorkflowRunnerStandaloneWebSearchTest {
    @Test fun gemmaFunctionTextExecutesThroughToolGateAndReadsSourceBeforeDone() = runBlocking {
        val goal = "Знайди поточну ціну Bitcoin та покажи графік"
        val task = TaskState(
            id = "gemma-function-search-e2e",
            projectId = null,
            goal = goal,
            status = TaskStatus.WAITING_MODEL
        )
        val controller = AgentController()
        var turns = 0
        val fixtureModel = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                turns += 1
                return Result.success(when (turns) {
                    1 -> """
                        expect_ok: true
                        confidence: 1.0
                        web.search(query="bitcoin price chart current")
                    """.trimIndent()
                    2 -> """{"tool":"web.read","args":{"url":"https://example.org/btc"},"reason":"Read matching source"}"""
                    else -> """{"done":true,"summary":"A web source was successfully read; this test does not assert a live BTC price."}"""
                })
            }
        }
        val calls = mutableListOf<ToolRequest>()
        val fixtureBridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                calls += toolRequest
                return when (toolRequest.tool) {
                    "web.search" -> ToolResult(
                        ok = true,
                        tool = "web.search",
                        exitCode = 0,
                        stdout = """{"results":[{"url":"https://example.org/btc","title":"Bitcoin price information"}]}"""
                    )
                    "web.read" -> ToolResult(
                        ok = true,
                        tool = "web.read",
                        exitCode = 0,
                        stdout = """{"url":"https://example.org/btc","text":"Fixture BTC source content. Not actual live market data."}"""
                    )
                    else -> error("Unexpected tool " + toolRequest.tool)
                }
            }
        }
        val initial = controller.initial(task).copy(
            // Isolate model text normalization from the standard web.search preflight.
            preflightCompleted = true
        )
        val outcome = WorkflowRunner(
            modelClient = fixtureModel,
            bridge = fixtureBridge,
            model = "fixture",
            controller = controller
        ).run(
            history = listOf(OllamaMessage("user", goal)),
            task = task,
            control = initial
        )
        assertTrue("Expected Finished, got $outcome", outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.DONE, outcome.control.task.status)
        assertEquals(3, turns)
        assertEquals(listOf("web.search", "web.read"), calls.map { it.tool })
        assertEquals("bitcoin price chart current", calls[0].args["query"])
        assertEquals("https://example.org/btc", calls[1].args["url"])
        assertEquals(2, outcome.control.task.kernel.observed)
        assertEquals(2, outcome.control.task.kernel.evidence.count { it.ok })
        assertTrue(outcome.text.contains("does not assert a live BTC price"))
    }

    @Test fun longUnverifiedSearchPromiseReturnsPartialWithoutAnyToolCall() = runBlocking {
        val prompt = "якого зрена ти мене дуриш???? ляєш текстову воду????"
        val task = TaskState(
            id = "angry-btc-diagnostic-e2e",
            projectId = null,
            goal = prompt,
            status = TaskStatus.WAITING_MODEL
        )
        val reply =
            "Ви маєте повне право злитися. Я не перевірив дані в реальному часі. " +
                "**Більше жодних виправдань. Ось реальний запит прямо зараз.** " +
                "expect_ok: true confidence: 1.0 " +
                "web.search(query=\"current bitcoin price usd\")"
        var modelCalls = 0
        var toolCalls = 0
        val fakeModel = object : ChatModelClient {
            override suspend fun chat(
                model: String,
                messages: List<OllamaMessage>
            ): Result<String> {
                modelCalls++
                return Result.success(reply)
            }
        }
        val fakeBridge = object : ToolExecutor {
            override suspend fun execute(request: ToolRequest): ToolResult {
                toolCalls++
                return ToolResult(
                    ok = true,
                    tool = request.tool,
                    exitCode = 0,
                    stdout = "This must never execute"
                )
            }
        }
        val controller = AgentController()
        val control = controller.initial(task)
        val outcome = WorkflowRunner(
            modelClient = fakeModel,
            bridge = fakeBridge,
            model = "fixture",
            controller = controller
        ).run(
            history = listOf(OllamaMessage("user", prompt)),
            task = task,
            control = control
        )
        assertTrue("Expected Finished, got $outcome", outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.PARTIAL, outcome.control.task.status)
        assertEquals(0, toolCalls)
        assertEquals(1, modelCalls)
        assertEquals(0, outcome.control.task.kernel.observed)
        assertTrue(outcome.text.contains("Пошук не підтверджено"))
        assertFalse(outcome.text.contains("Ось реальний запит прямо зараз"))
    }

    @Test fun aFakeSearchPredictionDoesNotCountAsToolSuccess() {
        val controller = AgentController()
        val state = controller.initial(TaskState(
            id = "not-executed", projectId = null,
            goal = "Find current gold price chart",
            status = TaskStatus.WAITING_MODEL
        ))
        val decision = controller.interpret(
            """
                expect_ok: true
                confidence: 1.0
                web.search(query="current gold price")
            """.trimIndent(),
            state
        )
        assertTrue(decision is com.lumena.android.agent.core.ControllerInstruction.Execute)
        assertEquals(0, decision.state.task.kernel.observed)
        assertTrue(decision.state.task.kernel.evidence.isEmpty())
        val falseDone = controller.interpret(
            """{"done":true,"summary":"Successfully retrieved current gold price"}""",
            decision.state
        )
        assertTrue(falseDone !is com.lumena.android.agent.core.ControllerInstruction.Finish)
    }
}
