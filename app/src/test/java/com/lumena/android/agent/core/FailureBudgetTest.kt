package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailureBudgetTest {
    @Test
    fun blocksAfterRepeatedIdenticalFailures() {
        val tracker = FailureTracker(
            FailureBudget(maxIdenticalToolFailures = 2)
        )
        val call = AgentDecision.ToolCall("file.read", mapOf("path" to "x.txt"))

        assertNull(tracker.recordToolResult(call, ok = false))
        assertNull(tracker.recordToolResult(call, ok = false))
        assertEquals(
            BudgetViolation.IDENTICAL_TOOL_FAILURE_LIMIT,
            tracker.recordToolResult(call, ok = false)
        )
    }

    @Test
    fun stepLimitStopsTheAgent() {
        val tracker = FailureTracker(FailureBudget(maxTotalSteps = 2))
        assertNull(tracker.recordStep())
        assertNull(tracker.recordStep())
        assertEquals(BudgetViolation.STEP_LIMIT, tracker.recordStep())
    }
}
