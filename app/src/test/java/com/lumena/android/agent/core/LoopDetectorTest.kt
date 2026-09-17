package com.lumena.android.agent.core

import org.junit.Assert.assertTrue
import org.junit.Test

class LoopDetectorTest {
    @Test
    fun detectsThirdIdenticalCall() {
        val detector = LoopDetector(maxIdenticalAttempts = 3)
        val call = AgentDecision.ToolCall(
            tool = "file.read",
            args = mapOf("path" to "README.md")
        )

        assertTrue(detector.record(call) is LoopState.Ok)
        assertTrue(detector.record(call) is LoopState.Ok)
        assertTrue(detector.record(call) is LoopState.Detected)
    }

    @Test
    fun changedArgumentsResetSequence() {
        val detector = LoopDetector(maxIdenticalAttempts = 3)
        detector.record(AgentDecision.ToolCall("file.read", mapOf("path" to "a.txt")))
        detector.record(AgentDecision.ToolCall("file.read", mapOf("path" to "a.txt")))

        val state = detector.record(
            AgentDecision.ToolCall("file.read", mapOf("path" to "b.txt"))
        )
        assertTrue(state is LoopState.Ok && state.count == 1)
    }
}
