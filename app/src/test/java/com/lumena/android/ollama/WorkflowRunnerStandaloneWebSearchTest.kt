package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentController
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
