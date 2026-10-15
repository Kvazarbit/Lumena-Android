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

    @Test fun phoneReadOnlyPythonWebGoalCompletesWithoutForbiddenWrite() = runBlocking {
        val goal = """
            Проведи контрольний вебпошук, лише читання.
            1. Знайди через web.search офіційну сторінку про Python на python.org.
            2. Прочитай знайдену сторінку через web.read.
            3. Назви заголовок сторінки, її URL та один факт із прочитаного тексту.
            Не використовуй python.run, file.write або інші виконавчі чи змінювальні інструменти.
            Не оголошуй DONE без реальних результатів інструментів.
            Якщо пошук або читання не відбулися, чесно повідом про незавершену задачу.
        """.trimIndent()
        val task = TaskState(
            id = "ci2188-python-org-read-only",
            projectId = null,
            goal = goal,
            status = TaskStatus.WAITING_MODEL
        )
        val controller = AgentController()
        val initial = controller.initial(task).copy(preflightCompleted = true)
        assertEquals(com.lumena.android.agent.core.TaskIntent.PUBLIC_WEB, initial.intent)
        assertEquals(setOf("web.search", "web.read"), initial.requiredTools)

        var modelTurns = 0
        val fakeModel = object : ChatModelClient {
            override suspend fun chat(
                model: String, messages: List<OllamaMessage>
            ): Result<String> {
                modelTurns++
                return Result.success(when (modelTurns) {
                    1 -> """{"tool":"web.search","args":{"query":"official Python page python.org"},"reason":"Find official page"}"""
                    2 -> """{"tool":"web.read","args":{"url":"https://www.python.org/"},"reason":"Read source content"}"""
                    else -> """{"done":true,"summary":"Title: Welcome to Python.org. URL: https://www.python.org/. Fact: Python supports functions and control flow."}"""
                })
            }
        }
        val calls = mutableListOf<ToolRequest>()
        val fakeBridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                calls += toolRequest
                return when (toolRequest.tool) {
                    "web.search" -> ToolResult(
                        ok = true, tool = "web.search", exitCode = 0,
                        stdout = """{"query":"official Python page python.org","results":[{"url":"https://www.python.org/","title":"Welcome to Python.org"}]}"""
                    )
                    "web.read" -> ToolResult(
                        ok = true, tool = "web.read", exitCode = 0,
                        stdout = """{"url":"https://www.python.org/","title":"Welcome to Python.org","text":"Python supports functions and control flow."}"""
                    )
                    else -> error("Forbidden/unexpected tool used: " + toolRequest.tool)
                }
            }
        }

        val outcome = WorkflowRunner(
            modelClient = fakeModel, bridge = fakeBridge, model = "fixture",
            controller = controller
        ).run(history = listOf(OllamaMessage("user", goal)), task = task, control = initial)

        assertTrue("Expected Finished, got $outcome", outcome is WorkflowOutcome.Finished)
        outcome as WorkflowOutcome.Finished
        assertEquals(TaskStatus.DONE, outcome.control.task.status)
        assertEquals(listOf("web.search", "web.read"), calls.map { it.tool })
        assertEquals(2, outcome.control.task.kernel.observed)
        assertTrue(outcome.control.task.goalContract.criteria.all {
            !it.required || it.status == com.lumena.android.agent.core.CriterionStatus.PASSED
        })
        assertEquals(0, outcome.control.task.goalContract.criteria.count {
            it.id.contains("file.write") || it.id.contains("code-content")
        })
        assertTrue(outcome.text.contains("Welcome to Python.org"))
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
