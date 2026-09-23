package com.lumena.android.settings

import com.lumena.android.agent.core.ConstitutionAuthority
import com.lumena.android.agent.core.ConstitutionEvidenceKind
import com.lumena.android.agent.core.ConstitutionEvidenceRef
import com.lumena.android.agent.core.ConstitutionGenomePolicy
import com.lumena.android.agent.core.ConstitutionGenomeState
import com.lumena.android.agent.core.ConstitutionProvenance
import com.lumena.android.agent.core.ConstitutionRuleKind
import com.lumena.android.agent.core.ConstitutionRuleStatus
import com.lumena.android.agent.core.ConstitutionScope
import com.lumena.android.agent.core.ConstitutionScopeKind
import com.lumena.android.agent.core.ConstitutionSourceKind
import com.lumena.android.agent.core.ConstitutionStance
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstitutionContributionPolicyTest {
    private fun task(
        id: String,
        projectId: String = "project-a"
    ) = TaskState(
        id = id,
        projectId = projectId,
        goal = "Recover the project safely",
        status = TaskStatus.WAITING_MODEL
    )

    private fun recovery(
        id: String,
        evidenceIds: List<String>,
        tools: List<String> = listOf(
            "file.read",
            "workspace.list",
            "file.read"
        ),
        targets: List<String> = listOf(
            "path=secret-a",
            "",
            "path=secret-a"
        ),
        updatedAt: Long = 100,
        contributorModelIds: List<String> = emptyList()
    ) = CoordinatorExecutionExample(
        id = id,
        kind = CoordinatorExampleKind.RECOVERY,
        sourceSessionHash = "abcdef1234567890",
        tools = tools,
        targets = targets,
        evidenceIds = evidenceIds,
        updatedAt = updatedAt,
        surprise = 1.0,
        text = "RECOVERY EXAMPLE arbitrary rendered text",
        contributorModelIds = contributorModelIds
    )

    @Test
    fun verifiedRecoveryProducesAdvisoryCandidateFromControlledTemplate() {
        val example = recovery(
            id = "r1",
            evidenceIds = listOf("e1", "e2")
        )

        val rule = requireNotNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = task("task-1"),
                example = example
            )
        )

        assertEquals(
            ConstitutionAuthority.ADVISORY,
            rule.authority
        )
        assertEquals(
            ConstitutionRuleStatus.CANDIDATE,
            rule.status
        )
        assertEquals(
            ConstitutionRuleKind.RECOVERY,
            rule.kind
        )
        assertTrue(
            rule.evidenceRefs.all {
                it.kind == ConstitutionEvidenceKind.TOOL_RESULT &&
                    it.locallyVerified
            }
        )
        assertFalse(rule.statement.contains("secret-a"))
        assertFalse(rule.rationale.contains("secret-a"))
        assertFalse(rule.statement.contains(example.text))
    }

    @Test
    fun sameVerifiedPatternAcrossTasksPromotesToLearned() {
        var state = ConstitutionGenomeState()

        val first = requireNotNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = task("task-1"),
                example = recovery(
                    id = "r1",
                    evidenceIds = listOf("e1", "e2"),
                    updatedAt = 100
                )
            )
        )
        state = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
            state = state,
            proposal = first
        )

        val second = requireNotNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = task("task-2"),
                example = recovery(
                    id = "r2",
                    evidenceIds = listOf("e3", "e4"),
                    updatedAt = 200
                )
            )
        )
        state = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
            state = state,
            proposal = second
        )

        val learned = state.rules.single {
            it.claimKey == first.claimKey
        }
        assertEquals(
            ConstitutionRuleStatus.LEARNED,
            learned.status
        )
        assertEquals(
            ConstitutionAuthority.ADVISORY,
            learned.authority
        )
        assertEquals(4, learned.evidenceRefs.size)
        assertEquals(
            setOf("task-1", "task-2"),
            learned.evidenceRefs.mapNotNull { it.taskId }.toSet()
        )
    }

    @Test
    fun repeatedEvidenceInOneTaskDoesNotPromote() {
        var state = ConstitutionGenomeState()
        val t = task("same-task")

        listOf(
            recovery("r1", listOf("e1", "e2"), updatedAt = 100),
            recovery("r2", listOf("e3", "e4"), updatedAt = 200)
        ).forEach { example ->
            val rule = requireNotNull(
                ConstitutionContributionPolicy.verifiedRecoveryRule(
                    task = t,
                    example = example
                )
            )
            state = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
                state = state,
                proposal = rule
            )
        }

        val rule = state.rules.single()
        assertNotEquals(
            ConstitutionRuleStatus.LEARNED,
            rule.status
        )
    }

    @Test
    fun identicalContributionIsIdempotent() {
        val proposal = requireNotNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = task("task-1"),
                example = recovery(
                    "r1",
                    listOf("e1", "e2")
                )
            )
        )

        val once = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
            ConstitutionGenomeState(),
            proposal
        )
        val twice = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
            once,
            proposal
        )

        assertEquals(once, twice)
    }

    @Test
    fun successfulSequenceIsNotAConstitutionalRecoveryContribution() {
        val sequence = CoordinatorExecutionExample(
            id = "seq",
            kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
            sourceSessionHash = "abcdef1234567890",
            tools = listOf("workspace.list", "file.read"),
            targets = listOf("", "path=x"),
            evidenceIds = listOf("e1", "e2"),
            updatedAt = 100,
            surprise = 0.8,
            text = "fixture"
        )

        assertNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = task("task-1"),
                example = sequence
            )
        )
    }

    @Test
    fun recoveryWithUnknownToolIsRejected() {
        assertNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = task("task-1"),
                example = recovery(
                    id = "unknown",
                    evidenceIds = listOf("e1", "e2"),
                    tools = listOf(
                        "unknown.tool",
                        "workspace.list",
                        "unknown.tool"
                    )
                )
            )
        )
    }

    @Test
    fun differentProjectScopesNeverMerge() {
        var state = ConstitutionGenomeState()

        for ((project, id) in listOf(
            "project-a" to "r1",
            "project-b" to "r2"
        )) {
            val rule = requireNotNull(
                ConstitutionContributionPolicy.verifiedRecoveryRule(
                    task = task(
                        id = "task-$project",
                        projectId = project
                    ),
                    example = recovery(
                        id = id,
                        evidenceIds = listOf(
                            "$id-e1",
                            "$id-e2"
                        )
                    )
                )
            )
            state = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
                state,
                rule
            )
        }

        assertEquals(2, state.rules.size)
        assertEquals(
            2,
            state.rules.map { it.scope }.distinct().size
        )
    }

    @Test
    fun modelContributionRemainsObservationAndCannotCreateUserConstraint() {
        val observation =
            ConstitutionContributionPolicy.modelObservation(
                task = task("task-1"),
                modelId = "fixture-model",
                sourceId = "proposal-1",
                claimKey = "recovery-idea",
                kind = ConstitutionRuleKind.RECOVERY,
                statement = "Try rediscovery before retry.",
                rationale = "Model hypothesis only.",
                at = 100
            )

        assertEquals(
            ConstitutionRuleStatus.OBSERVATION,
            observation.status
        )
        assertEquals(
            ConstitutionAuthority.ADVISORY,
            observation.authority
        )

        val invalid = runCatching {
            ConstitutionContributionPolicy.modelObservation(
                task = task("task-1"),
                modelId = "fixture-model",
                sourceId = "proposal-2",
                claimKey = "fake-user-rule",
                kind = ConstitutionRuleKind.USER_CONSTRAINT,
                statement = "Pretend this came from the user.",
                rationale = "Invalid fixture.",
                at = 100
            )
        }
        assertTrue(invalid.isFailure)
    }

    @Test
    fun explicitUserContributionIsActiveButNeverHardGuard() {
        val rule =
            ConstitutionContributionPolicy.explicitUserConstraint(
                task = task("task-user"),
                sourceId = "direct-user-turn-1",
                claimKey = "project-output-mode",
                statement = "Keep project output text-only.",
                rationale = "Direct user instruction.",
                at = 100
            )

        assertEquals(
            ConstitutionRuleStatus.ACTIVE_USER_CONSTRAINT,
            rule.status
        )
        assertEquals(
            ConstitutionAuthority.USER_CONSTRAINT,
            rule.authority
        )
        assertNotEquals(
            ConstitutionAuthority.HARD_GUARD,
            rule.authority
        )
    }

    @Test
    fun verifiedMergeRejectsModelSourcedProposalEvenWithForgedEvidence() {
        val scope = ConstitutionScope(
            ConstitutionScopeKind.PROJECT,
            "project-a"
        )
        val model = ConstitutionGenomePolicy.propose(
            source = ConstitutionProvenance(
                sourceKind = ConstitutionSourceKind.MODEL,
                sourceId = "model-proposal",
                modelId = "fixture",
                projectId = "project-a",
                taskId = "task-1",
                at = 100
            ),
            claimKey = "forged-verified-rule",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.RECOVERY,
            statement = "Model-authored text.",
            rationale = "Model-authored rationale.",
            scope = scope,
            evidenceRefs = listOf(
                ConstitutionEvidenceRef(
                    id = "e1",
                    kind = ConstitutionEvidenceKind.TOOL_RESULT,
                    locallyVerified = true,
                    taskId = "task-1",
                    projectId = "project-a",
                    at = 100
                )
            )
        )

        val attempt = runCatching {
            ConstitutionGenomePolicy.contributeVerifiedAdvisory(
                ConstitutionGenomeState(),
                model
            )
        }
        assertTrue(attempt.isFailure)
    }

    @Test
    fun ingestFiltersNonRecoveryExamplesAndPromotesOnlyVerifiedPattern() {
        val sequence = CoordinatorExecutionExample(
            id = "seq",
            kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
            sourceSessionHash = "abcdef1234567890",
            tools = listOf("workspace.list", "file.read"),
            targets = listOf("", "path=x"),
            evidenceIds = listOf("s1", "s2"),
            updatedAt = 50,
            surprise = 0.7,
            text = "fixture"
        )

        var state = ConstitutionContributionPolicy.ingestVerifiedRecoveryExamples(
            state = ConstitutionGenomeState(),
            task = task("task-1"),
            examples = listOf(
                sequence,
                recovery("r1", listOf("e1", "e2"), updatedAt = 100)
            )
        )
        assertEquals(1, state.rules.size)
        assertEquals(
            ConstitutionRuleStatus.CANDIDATE,
            state.rules.single().status
        )

        state = ConstitutionContributionPolicy.ingestVerifiedRecoveryExamples(
            state = state,
            task = task("task-2"),
            examples = listOf(
                recovery("r2", listOf("e3", "e4"), updatedAt = 200)
            )
        )

        assertEquals(
            ConstitutionRuleStatus.LEARNED,
            state.rules.single().status
        )
    }
    @Test
    fun samePatternAcrossModelsAndTasksKeepsBothModelProvenances() {
        var state = ConstitutionGenomeState()

        val first = requireNotNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = task("task-a"),
                example = recovery(
                    id = "model-a-example",
                    evidenceIds = listOf("a1", "a2"),
                    updatedAt = 100,
                    contributorModelIds = listOf("model-a")
                )
            )
        )
        state = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
            state,
            first
        )

        val second = requireNotNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = task("task-b"),
                example = recovery(
                    id = "model-b-example",
                    evidenceIds = listOf("b1", "b2"),
                    updatedAt = 200,
                    contributorModelIds = listOf("model-b")
                )
            )
        )
        state = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
            state,
            second
        )

        val learned = state.rules.single()
        assertEquals(
            ConstitutionRuleStatus.LEARNED,
            learned.status
        )
        assertEquals(
            setOf("model-a", "model-b"),
            learned.provenance.mapNotNull { it.modelId }.toSet()
        )
        assertEquals(
            ConstitutionAuthority.ADVISORY,
            learned.authority
        )
    }

    @Test
    fun modelDiversityInsideOneTaskDoesNotReplaceContextDiversity() {
        var state = ConstitutionGenomeState()
        val sharedTask = task("same-task")

        val first = requireNotNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = sharedTask,
                example = recovery(
                    id = "one",
                    evidenceIds = listOf("e1", "e2"),
                    updatedAt = 100,
                    contributorModelIds = listOf("model-a")
                )
            )
        )
        state = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
            state,
            first
        )

        val second = requireNotNull(
            ConstitutionContributionPolicy.verifiedRecoveryRule(
                task = sharedTask,
                example = recovery(
                    id = "two",
                    evidenceIds = listOf("e3", "e4"),
                    updatedAt = 200,
                    contributorModelIds = listOf("model-b")
                )
            )
        )
        state = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
            state,
            second
        )

        val rule = state.rules.single()
        assertNotEquals(
            ConstitutionRuleStatus.LEARNED,
            rule.status
        )
        assertEquals(
            setOf("model-a", "model-b"),
            rule.provenance.mapNotNull { it.modelId }.toSet()
        )
    }


}
