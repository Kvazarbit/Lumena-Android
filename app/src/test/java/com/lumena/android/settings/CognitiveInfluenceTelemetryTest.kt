package com.lumena.android.settings

import com.lumena.android.agent.core.AgentController
import com.lumena.android.agent.core.ControllerInstruction
import com.lumena.android.agent.core.ReflexAdviceSource
import com.lumena.android.agent.core.ReflexOption
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveInfluenceTelemetryTest {
    private fun state() = CognitiveInfluenceState(
        epoch = "epoch-1",
        since = 1,
        versionCode = 36
    )

    @Test
    fun exposedAdviceResolvesAgainstOnlyTheNextObservedToolOutcome() {
        var telemetry = CognitiveInfluencePolicy.recordLayer(
            state(),
            CognitiveInfluenceLayer.REFLEX,
            eligible = true,
            generated = true,
            admitted = true
        )
        telemetry = CognitiveInfluencePolicy.recordLayer(
            telemetry,
            CognitiveInfluenceLayer.TINYJEV,
            eligible = true,
            generated = true,
            admitted = true
        )
        telemetry = CognitiveInfluencePolicy.recordReflexExposure(
            state = telemetry,
            taskId = "task-a",
            family = "web.search",
            option = ReflexOption.RETRY_VARIANT,
            source = ReflexAdviceSource.TINYJEV,
            now = 10
        )
        assertEquals(1, telemetry.pendingReflex.size)

        telemetry = CognitiveInfluencePolicy.resolveNextTool(
            state = telemetry,
            taskId = "task-a",
            request = ToolRequest("web.search", mapOf("query" to "x")),
            result = ToolResult(ok = false, tool = "web.search")
        )

        assertTrue(telemetry.pendingReflex.isEmpty())
        assertEquals(1L, telemetry.reflex.agreed)
        assertEquals(1L, telemetry.reflex.nextFail)
        assertEquals(1L, telemetry.tinyJev.agreed)
        assertEquals(1L, telemetry.tinyJev.nextFail)

        val unchanged = CognitiveInfluencePolicy.resolveNextTool(
            telemetry,
            "task-a",
            ToolRequest("web.search"),
            ToolResult(ok = true)
        )
        assertEquals(telemetry, unchanged)
    }

    @Test
    fun partialIsClassifiedDeterministicallyWithoutFabricatingToolOutcome() {
        var telemetry = CognitiveInfluencePolicy.recordReflexExposure(
            state = state(),
            taskId = "task-b",
            family = "python.tests",
            option = ReflexOption.DEGRADE_PARTIAL,
            source = ReflexAdviceSource.REFLEX_EXPERIENCE,
            now = 20
        )
        telemetry = CognitiveInfluencePolicy.resolvePartial(
            telemetry,
            "task-b"
        )
        assertTrue(telemetry.pendingReflex.isEmpty())
        assertEquals(1L, telemetry.reflex.agreed)
        assertEquals(0L, telemetry.reflex.nextOk)
        assertEquals(0L, telemetry.reflex.nextFail)
        assertEquals(0L, telemetry.reflex.nextUnknown)
    }

    @Test
    fun telemetryReductionCannotChangeControllerInstruction() {
        val controller = AgentController()
        val task = TaskState(
            id = "invariance",
            projectId = null,
            goal = "inspect file",
            status = TaskStatus.WAITING_MODEL
        )
        val control = controller.initial(task)
        val reply = """{"tool":"file.read","args":{"path":"a.txt"}}"""

        val before = controller.interpret(reply, control)
        val observed = CognitiveInfluencePolicy.recordLayer(
            state(),
            CognitiveInfluenceLayer.CONSTITUTION,
            eligible = true,
            generated = true,
            admitted = true,
            wouldExpose = true
        )
        assertFalse(observed == state())
        val after = controller.interpret(reply, control)

        assertEquals(before::class, after::class)
        assertTrue(before is ControllerInstruction.Execute)
        assertTrue(after is ControllerInstruction.Execute)
        before as ControllerInstruction.Execute
        after as ControllerInstruction.Execute
        assertEquals(before.call, after.call)
        assertEquals(before.state, after.state)
    }
}
