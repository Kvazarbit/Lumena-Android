package com.lumena.android.agent.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryAdvisorTest {
    private val task = TaskState(
        id = "t",
        projectId = null,
        goal = "test",
        status = TaskStatus.WAITING_MODEL
    )

    @Test
    fun missingFileSuggestsDiscoveryInsteadOfRepeat() {
        val hint = RecoveryAdvisor.suggest(
            task = task,
            call = AgentDecision.ToolCall(
                tool = "file.read",
                args = mapOf("path" to "missing.txt")
            ),
            ok = false,
            stdout = "",
            stderr = "",
            error = "FileNotFoundError: No such file"
        )

        assertTrue(hint.orEmpty().contains("workspace.list"))
        assertTrue(hint.orEmpty().contains("file.search"))
    }

    @Test
    fun imageFailureKnowsBridgeAlreadyBroadensProviders() {
        val hint = RecoveryAdvisor.suggest(
            task = task,
            call = AgentDecision.ToolCall(
                tool = "image.search",
                args = mapOf("query" to "rare subject")
            ),
            ok = false,
            stdout = "",
            stderr = "",
            error = "No displayable images found"
        )

        assertTrue(hint.orEmpty().contains("multiple providers"))
        assertTrue(hint.orEmpty().contains("Do not repeat"))
    }

    @Test
    fun ollamaGenerateFailureSuggestsRealStatusCheck() {
        val hint = RecoveryAdvisor.suggest(
            task = task,
            call = AgentDecision.ToolCall(
                tool = "ollama.generate",
                args = mapOf("model" to "x", "prompt" to "hi")
            ),
            ok = false,
            stdout = "",
            stderr = "",
            error = "connection refused"
        )

        assertTrue(hint.orEmpty().contains("ollama.status"))
        assertTrue(hint.orEmpty().contains("ollama.start"))
    }

    @Test
    fun successfulToolClearsRecoveryHint() {
        val hint = RecoveryAdvisor.suggest(
            task = task,
            call = AgentDecision.ToolCall("file.read", mapOf("path" to "ok.txt")),
            ok = true,
            stdout = "ok",
            stderr = "",
            error = null
        )

        assertNull(hint)
    }
}
