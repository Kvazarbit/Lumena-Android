package com.lumena.android.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {
    @Test
    fun protocolAndTaskSurviveOversizedMemoryPressure() {
        val context = ContextBuilder(
            maxMemoryItems = 8,
            maxChars = 2_400
        ).build(
            task = TaskState(
                id = "ctx",
                projectId = null,
                goal = "Run the exact requested verification",
                status = TaskStatus.WAITING_MODEL,
                step = 1,
                maxSteps = 4
            ),
            project = VerifiedProjectContext(
                projectName = "huge",
                cwd = "@Lumena-Android",
                verifiedFacts = List(40) { "project-fact-" + "x".repeat(300) }
            ),
            relevantMemory = List(20) {
                "POSITIVE verified memory " + "m".repeat(500)
            },
            allowedTools = setOf("file.read", "git.status")
        )

        assertTrue(context.length <= 2_400)
        assertTrue(context.contains("SYSTEM"))
        assertTrue(context.contains("OUTPUT RULE"))
        assertTrue(context.contains("TASK STATE"))
        assertTrue(context.contains("goal=Run the exact requested verification"))
        assertTrue(context.contains("return ONLY JSON"))
    }

    @Test
    fun verifiedMemoryIsSanitizedAndBounded() {
        val context = ContextBuilder(
            maxMemoryItems = 2,
            maxChars = 8_000
        ).build(
            task = TaskState(
                id = "mem",
                projectId = null,
                goal = "Check audio",
                status = TaskStatus.WAITING_MODEL
            ),
            project = null,
            relevantMemory = listOf(
                "NEGATIVE unresolved\npython.run audio_test.py",
                "POSITIVE verified git.status",
                "third memory must be omitted"
            )
        )

        assertTrue(context.contains("RELEVANT VERIFIED MEMORY"))
        assertTrue(context.contains("NEGATIVE unresolved python.run audio_test.py"))
        assertTrue(context.contains("POSITIVE verified git.status"))
        assertFalse(context.contains("third memory must be omitted"))
    }

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
