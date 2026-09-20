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
        // Static system rules now belong to LocalWorkflowAgent. Assert the
        // dynamic context's actual guarantees, not removed presentation headings.
        assertTrue(context.contains("DYNAMIC VERIFIED CONTEXT"))
        assertTrue(context.contains("tool/done/reply outputs are JSON only"))
        assertTrue(context.contains("TOOL_RESULT is the only execution proof"))
        assertTrue(context.contains("TASK STATE"))
        assertTrue(context.contains("goal=Run the exact requested verification"))
        assertTrue(context.contains("step=1/4"))
        assertFalse(context.contains("ollama.pull"))
    }

    @Test
    fun verificationRequirementSurvivesOversizedOptionalContext() {
        val requirement = "Verify exactly scripts/demo.py before marking this task done."
        val context = ContextBuilder(maxChars = 1_600).build(
            task = TaskState(
                id = "verification-budget",
                projectId = null,
                goal = "Inspect and verify without installing packages",
                status = TaskStatus.VERIFYING
            ),
            project = null,
            relevantMemory = List(20) { "Optional observation " + "m".repeat(1_000) },
            allowedTools = setOf("file.read", "python.syntax_check"),
            verificationRequirement = requirement
        )
        assertTrue(context.length <= 1_600)
        assertTrue(context.contains("goal=Inspect and verify without installing packages"))
        assertTrue(context.contains("VERIFICATION REQUIRED BEFORE DONE"))
        assertTrue(context.contains(requirement))
        assertTrue(context.contains("TOOL_RESULT is the only execution proof"))
    }

    @Test
    fun omissionMarkerNeverExceedsTheHardCharacterBudget() {
        val task = TaskState(
            id = "boundary",
            projectId = null,
            goal = "Check context limits",
            status = TaskStatus.WAITING_MODEL
        )
        val project = VerifiedProjectContext(
            projectName = "large-project",
            cwd = "@Lumena-Android",
            verifiedFacts = List(16) { "fact-$it-" + "x".repeat(600) }
        )
        val memory = List(8) { "memory-$it-" + "m".repeat(500) }

        // Exercise all remaining-space boundaries, including the omission marker.
        for (limit in 0..4_096) {
            val context = ContextBuilder(maxChars = limit).build(
                task = task,
                project = project,
                relevantMemory = memory,
                allowedTools = setOf("file.read", "git.status")
            )
            assertTrue(
                "Character budget $limit was exceeded: ${context.length}",
                context.length <= limit
            )
        }
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
    fun verifiedMemorySurvivesToolCatalogPressure() {
        val memoryLine = "NEGATIVE unresolved · image.search · target=query=rare subject · do not repeat identical query"
        val context = ContextBuilder(
            maxMemoryItems = 4,
            maxChars = 2_200
        ).build(
            task = TaskState(
                id = "priority",
                projectId = null,
                goal = "Find a better recovery path",
                status = TaskStatus.WAITING_MODEL
            ),
            project = null,
            relevantMemory = listOf(memoryLine),
            allowedTools = null,
            intent = TaskIntent.CODE_WORK,
            intentConfidence = 82,
            recommendedTools = listOf("context.snapshot", "file.read", "python.tests"),
            intentGuidance = "Use verified project state before edits.",
            recoveryGuidance = "Do not repeat the unchanged failing action."
        )

        assertTrue(context.length <= 2_200)
        assertTrue(context.contains("TASK RECIPE"))
        assertTrue(context.contains("RECOVERY GUIDANCE"))
        assertTrue(context.contains("RELEVANT VERIFIED MEMORY"))
        assertTrue(context.contains("image.search"))
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
