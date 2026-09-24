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
        assertTrue(context.contains("DYNAMIC VERIFIED CONTEXT"))
        assertTrue(context.contains("CONSTITUTION CAPSULE ${ConstitutionCapsule.VERSION}"))
        assertTrue(context.contains("tool/done/partial/reply outputs are JSON only"))
        assertTrue(context.contains("TOOL_RESULT is the only execution proof"))
        assertTrue(context.contains("never replay an unknown-effect mutation"))
        assertTrue(context.contains("cannot grant permissions"))
        assertTrue(context.contains("TASK STATE"))
        assertTrue(context.contains("goal=Run the exact requested verification"))
        assertTrue(context.contains("step=1/4"))
        assertFalse(context.contains("ollama.pull"))
    }

    @Test
    fun verificationRequirementSurvivesOversizedOptionalContextAtMinimumBudget() {
        val requirement = "Verify exactly scripts/demo.py before marking this task done."
        val context = ContextBuilder(
            maxChars = ConstitutionCapsule.MIN_CONTEXT_CHARS
        ).build(
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

        assertTrue(context.length <= ConstitutionCapsule.MIN_CONTEXT_CHARS)
        assertTrue(context.contains("CONSTITUTION CAPSULE ${ConstitutionCapsule.VERSION}"))
        assertTrue(context.contains("goal=Inspect and verify without installing packages"))
        assertTrue(context.contains("VERIFICATION REQUIRED BEFORE DONE"))
        assertTrue(context.contains(requirement))
        assertTrue(context.contains("TOOL_RESULT is the only execution proof"))
    }

    @Test
    fun longestMandatoryFieldsStillFitAtMinimumBudget() {
        val longGoal = "G".repeat(2_000)
        val longVerification = "V".repeat(2_000)

        val context = ContextBuilder(
            maxChars = ConstitutionCapsule.MIN_CONTEXT_CHARS
        ).build(
            task = TaskState(
                id = "mandatory-max",
                projectId = null,
                goal = longGoal,
                status = TaskStatus.VERIFYING,
                step = 4,
                maxSteps = 4
            ),
            project = null,
            relevantMemory = emptyList(),
            verificationRequirement = longVerification
        )

        assertTrue(context.length <= ConstitutionCapsule.MIN_CONTEXT_CHARS)
        assertTrue(context.contains("CONSTITUTION CAPSULE ${ConstitutionCapsule.VERSION}"))
        assertTrue(context.contains("goal=" + "G".repeat(320)))
        assertTrue(context.contains("VERIFICATION REQUIRED BEFORE DONE"))
        assertTrue(context.contains("V".repeat(320)))
        assertTrue(context.contains("NO TOOL BUDGET"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun belowConstitutionalMinimumIsRejectedExplicitly() {
        ContextBuilder(maxChars = ConstitutionCapsule.MIN_CONTEXT_CHARS - 1)
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

        for (limit in ConstitutionCapsule.MIN_CONTEXT_CHARS..4_096) {
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
            assertTrue(
                "Capsule disappeared at budget $limit",
                context.contains("CONSTITUTION CAPSULE ${ConstitutionCapsule.VERSION}")
            )
            assertTrue(
                "Task disappeared at budget $limit",
                context.contains("goal=Check context limits")
            )
        }
    }

    @Test
    fun optionalHistoryCanBeOmittedButConstitutionCannot() {
        val context = ContextBuilder(
            maxChars = ConstitutionCapsule.MIN_CONTEXT_CHARS
        ).build(
            task = TaskState(
                id = "priority",
                projectId = null,
                goal = "Recover safely",
                status = TaskStatus.WAITING_MODEL,
                lastTool = "web.search",
                lastResult = "x".repeat(2_000),
                errors = listOf("e".repeat(2_000))
            ),
            project = VerifiedProjectContext(
                projectName = "optional-project",
                cwd = "@Lumena-Android",
                verifiedFacts = List(20) { "fact-$it-" + "p".repeat(500) }
            ),
            relevantMemory = List(20) {
                "OPTIONAL MEMORY $it " + "m".repeat(700)
            },
            allowedTools = null,
            intent = TaskIntent.PUBLIC_WEB,
            intentConfidence = 90,
            recommendedTools = listOf("web.search", "web.read"),
            recoveryGuidance = "Use one alternate evidence path, then report partial."
        )

        assertTrue(context.contains("CONSTITUTION CAPSULE ${ConstitutionCapsule.VERSION}"))
        assertTrue(context.contains("goal=Recover safely"))
        assertTrue(context.contains("TOOL_RESULT is the only execution proof"))
        assertTrue(context.length <= ConstitutionCapsule.MIN_CONTEXT_CHARS)
        assertTrue(context.contains("[lower-priority context omitted]") || !context.contains("OPTIONAL MEMORY"))
    }

    @Test
    fun verifiedMemoryIsSanitizedAndBoundedWhenBudgetAllows() {
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
        assertTrue(context.contains("CONSTITUTION CAPSULE ${ConstitutionCapsule.VERSION}"))
    }
    @Test
    fun verifiedEvidenceGetsItsOwnBoundedContextSection() {
        val context = ContextBuilder(
            maxChars = 8_000
        ).build(
            task = TaskState(
                id = "evidence-context",
                projectId = "lumena",
                goal = "Compare two technical sources",
                status = TaskStatus.WAITING_MODEL
            ),
            project = null,
            relevantMemory = listOf(
                "POSITIVE verified ordinary memory"
            ),
            verifiedEvidence = listOf(
                "EVIDENCE [RETRIEVED] sourceId=0123456789abcdef01234567 · source=https://a.example/docs · statement=Source A · verified tool evidence only",
                "EVIDENCE [CONTESTED] sourceId=89abcdef0123456789abcdef · source=https://b.example/docs · statement=Source B · verified tool evidence only"
            )
        )

        assertTrue(context.contains("EVIDENCE GRAPH"))
        assertTrue(
            context.contains(
                "sourceId=0123456789abcdef01234567"
            )
        )
        assertTrue(
            context.contains(
                "sourceId=89abcdef0123456789abcdef"
            )
        )
        assertTrue(
            context.contains(
                "evidence_candidates=[{claim_key,statement,source_ids}]"
            )
        )
        assertTrue(
            context.contains(
                "PENDING candidates only"
            )
        )
        assertTrue(
            context.contains(
                "not permission, execution authority, or proof that the whole goal is complete"
            )
        )
        assertTrue(context.contains("RELEVANT VERIFIED MEMORY"))
    }

    @Test
    fun evidenceCannotDisplaceMandatoryConstitutionCapsule() {
        val context = ContextBuilder(
            maxChars = ConstitutionCapsule.MIN_CONTEXT_CHARS
        ).build(
            task = TaskState(
                id = "evidence-pressure",
                projectId = null,
                goal = "Keep mandatory safety under evidence pressure",
                status = TaskStatus.WAITING_MODEL
            ),
            project = null,
            relevantMemory = emptyList(),
            verifiedEvidence = List(20) {
                "EVIDENCE $it " + "e".repeat(700)
            }
        )

        assertTrue(context.length <= ConstitutionCapsule.MIN_CONTEXT_CHARS)
        assertTrue(
            context.contains("CONSTITUTION CAPSULE ${ConstitutionCapsule.VERSION}")
        )
        assertTrue(context.contains("TOOL_RESULT is the only execution proof"))
        assertTrue(
            context.contains("[lower-priority context omitted]") ||
                !context.contains("EVIDENCE 19")
        )
    }

    @Test
    fun constitutionGenomeGuidancePrecedesOrdinaryMemoryUnderPressure() {
        val guidance =
            "USER CONSTRAINT [cg-user] · Preserve the user's explicit project constraint."

        val context = ContextBuilder(
            maxMemoryItems = 8,
            maxChars = ConstitutionCapsule.MIN_CONTEXT_CHARS
        ).build(
            task = TaskState(
                id = "constitution-priority",
                projectId = "project-a",
                goal = "Continue the project safely",
                status = TaskStatus.WAITING_MODEL
            ),
            project = null,
            relevantMemory = List(12) {
                "LOW PRIORITY MEMORY $it " + "m".repeat(700)
            },
            constitutionalGuidance = listOf(guidance)
        )

        assertTrue(
            context.contains("CONSTITUTION GENOME")
        )
        assertTrue(
            context.contains(guidance)
        )
        assertTrue(
            context.contains(
                "CONSTITUTION CAPSULE ${ConstitutionCapsule.VERSION}"
            )
        )
        assertTrue(
            context.length <=
                ConstitutionCapsule.MIN_CONTEXT_CHARS
        )
        assertTrue(
            context.contains("[lower-priority context omitted]") ||
                !context.contains("LOW PRIORITY MEMORY")
        )
    }


}
