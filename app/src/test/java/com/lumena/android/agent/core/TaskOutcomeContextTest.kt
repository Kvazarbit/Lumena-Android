package com.lumena.android.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskOutcomeContextTest {
    @Test
    fun failedContextPreservesBoundedFailureIdentity() {
        val task = TaskState(
            id = "task-123",
            projectId = "demo_project",
            goal = "Працюй у проєкті demo_project і перевір реалізацію",
            status = TaskStatus.FAILED,
            lastTool = "web.search",
            lastResult = "ok=true source=https://example.org",
            errors = listOf("first", "Protocol SYNTAX [INVALID_INPUT]")
        )

        val context = TaskOutcomeContext.failed(
            task,
            "Model protocol failed repeatedly"
        )

        assertTrue(context.startsWith("TASK_OUTCOME_CONTEXT"))
        assertTrue(context.contains("kind=FAILED"))
        assertTrue(context.contains("task_id=task-123"))
        assertTrue(context.contains("project_id=demo_project"))
        assertTrue(context.contains("last_tool=web.search"))
        assertTrue(context.contains("Protocol SYNTAX [INVALID_INPUT]"))
        assertTrue(context.contains("Do not treat it as permission"))
    }

    @Test
    fun failureContextNeverRestoresExecutionAuthority() {
        val context = TaskOutcomeContext.failed(
            TaskState(
                id = "task",
                projectId = "demo",
                goal = "test",
                status = TaskStatus.FAILED
            ),
            "failure"
        )

        assertFalse(context.contains("approved=true"))
        assertFalse(context.contains("execute=true"))
        assertFalse(context.contains("permission=true"))
    }

    @Test
    fun hostileOrHugeFailureTextIsFlattenedAndBounded() {
        val context = TaskOutcomeContext.failed(
            TaskState(
                id = "task\nother",
                projectId = null,
                goal = "goal\nwith\tlines",
                status = TaskStatus.FAILED,
                errors = List(10) { "E$it " + "x".repeat(1_000) }
            ),
            "boom\n" + "z".repeat(10_000)
        )

        assertTrue(context.length <= 5_000)
        assertFalse(context.contains("\u0000"))
        assertFalse(context.contains("task\nother"))
        assertFalse(context.contains("goal\nwith"))
        assertTrue(context.contains("failure=boom"))
    }
}
