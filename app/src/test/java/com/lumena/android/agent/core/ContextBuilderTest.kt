package com.lumena.android.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {
    @Test
    fun includesVerifiedStateAndAllowedToolsOnly() {
        val context = ContextBuilder().build(
            task = TaskState(
                id = "1",
                projectId = "btc",
                goal = "Fix loader",
                status = TaskStatus.PLANNING,
                step = 2,
                lastTool = "file.read",
                lastResult = "Found load_csv"
            ),
            project = VerifiedProjectContext(
                projectName = "btc-agent",
                cwd = "btc-agent",
                branch = "feature/loader",
                importantFiles = listOf("src/loader.py"),
                verifiedFacts = listOf("Tests use pytest")
            ),
            relevantMemory = listOf("Python 3.11"),
            allowedTools = setOf("file.read", "file.patch")
        )

        assertTrue(context.contains("goal=Fix loader"))
        assertTrue(context.contains("branch=feature/loader"))
        assertTrue(context.contains("file.read"))
        assertTrue(context.contains("file.patch"))
        assertFalse(context.contains("ollama.pull"))
    }
}
