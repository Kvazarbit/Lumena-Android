package com.lumena.android.ollama

import com.lumena.android.agent.core.*
import com.lumena.android.agent.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompletionReceiptWorkflowTest {
    @Test fun phoneSummaryRepairKeepsAllReceiptsUnderContextPressureWithoutToolReplay() = runBlocking {
        val controller = AgentController()
        var state = controller.initial(TaskState("phone", null,
            "system.time workspace.list web.read https://example.com"))
            .copy(preflightCompleted = true, intent = TaskIntent.PUBLIC_WEB)
        for ((tool, output) in listOf(
            "system.time" to "local_time=2026-09-26T21:57:04+02:00",
            "workspace.list" to "demo_project/ bridge_inspect/",
            "web.read" to "Example Domain"
        )) {
            state = controller.afterTool(state, AgentDecision.ToolCall(tool), true, output, "", null).state
        }
        var modelCalls = 0
        var toolCalls = 0
        val model = object : ChatModelClient {
            override suspend fun chat(model: String, messages: List<OllamaMessage>): Result<String> {
                modelCalls++
                // Both the constitution/task policy and execution receipts are
                // now mandatory. 2K chars cannot contain this task's two blocks.
                val requiredChars = messages.sumOf { RequiredProjectContext.minimum(it.content) }
                assertTrue(requiredChars > 2000)
                assertThrows(IllegalArgumentException::class.java) {
                    OllamaContextPolicy.compact(messages, OllamaRequestBudget(OllamaOptions(), 2000, 1000))
                }
                val fitted = OllamaContextPolicy.compact(messages, OllamaRequestBudget(OllamaOptions(), 3000, 1000))
                assertTrue(fitted.first().content.contains(ConstitutionCapsule.prompt()))
                assertTrue(fitted.first().content.contains(ModelOperatingContract.binding()))
                val recent = fitted.last().content
                for (tool in listOf("system.time", "workspace.list", "web.read")) {
                    assertTrue("Missing receipt for $tool: $recent", recent.contains("$tool executed=true ok=true"))
                }
                assertTrue(recent.contains("21:57:04"))
                assertTrue(recent.contains("demo_project/"))
                assertTrue(recent.contains("Example Domain"))
                return Result.success(if (modelCalls == 1) {
                    """{"done":true,"summary":"workspace.list не був викликаний."}"""
                } else {
                    """{"done":true,"summary":"Час 21:57:04; каталоги demo_project і bridge_inspect; сторінка Example Domain."}"""
                })
            }
        }
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                toolCalls++
                error("Report repair must not rerun completed tools")
            }
        }
        val outcome = WorkflowRunner(model, bridge, "fixture").run(
            history = listOf(OllamaMessage("system", "old system ".repeat(1000)),
                OllamaMessage("user", "old workspace listing ".repeat(200)),
                OllamaMessage("user", "PROTOCOL_REPAIR_MODE ".repeat(200))),
            task = state.task, control = state
        ) as WorkflowOutcome.Finished
        assertEquals(2, modelCalls)
        assertEquals(0, toolCalls)
        assertEquals(TaskStatus.DONE, outcome.control.task.status)
        assertEquals(1, outcome.control.summaryRepairs)
        assertEquals(state.task.kernel, outcome.control.task.kernel)
    }
}
