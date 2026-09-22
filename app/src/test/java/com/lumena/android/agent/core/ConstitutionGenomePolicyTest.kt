package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstitutionGenomePolicyTest {
    private fun scope(
        kind: ConstitutionScopeKind = ConstitutionScopeKind.PROJECT,
        key: String = "project-a"
    ) = ConstitutionScope(kind, key)

    private fun provenance(
        kind: ConstitutionSourceKind,
        id: String,
        at: Long = 100,
        projectId: String? = "project-a",
        taskId: String? = null,
        modelId: String? = null
    ) = ConstitutionProvenance(
        sourceKind = kind,
        sourceId = id,
        modelId = modelId,
        projectId = projectId,
        taskId = taskId,
        at = at
    )

    private fun verified(
        id: String,
        taskId: String,
        at: Long,
        kind: ConstitutionEvidenceKind =
            ConstitutionEvidenceKind.TOOL_RESULT,
        projectId: String? = "project-a"
    ) = ConstitutionEvidenceRef(
        id = id,
        kind = kind,
        locallyVerified = true,
        taskId = taskId,
        projectId = projectId,
        at = at
    )

    @Test
    fun modelProposalCannotPromoteWithoutVerifiedEvidence() {
        val rule = ConstitutionGenomePolicy.propose(
            source = provenance(
                kind = ConstitutionSourceKind.MODEL,
                id = "model-proposal-1",
                modelId = "fixture-model"
            ),
            claimKey = "prefer-workspace-list-before-file-read",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.STRATEGY,
            statement = "Inspect the workspace before reading an uncertain path.",
            rationale = "The model proposes discovery before path access.",
            scope = scope()
        )

        assertEquals(
            ConstitutionRuleStatus.OBSERVATION,
            rule.status
        )
        assertEquals(
            ConstitutionAuthority.ADVISORY,
            rule.authority
        )
        assertFalse(
            ConstitutionGenomePolicy.canPromoteToLearned(rule)
        )
    }

    @Test
    fun threeVerifiedEvidenceItemsAcrossTwoContextsPromoteToLearned() {
        var state = ConstitutionGenomeState()
        val rule = ConstitutionGenomePolicy.propose(
            source = provenance(
                kind = ConstitutionSourceKind.MODEL,
                id = "model-proposal-1",
                modelId = "fixture-model"
            ),
            claimKey = "recover-file-path-with-discovery",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.RECOVERY,
            statement = "After path drift, rediscover the workspace before retrying the read.",
            rationale = "Repeated verified recoveries may justify an advisory recovery preference.",
            scope = scope()
        )
        state = ConstitutionGenomePolicy.add(state, rule)

        val evidence = listOf(
            verified("e1", "task-a", 200),
            verified("e2", "task-a", 300),
            verified("e3", "task-b", 400)
        )

        evidence.forEachIndexed { index, item ->
            state = ConstitutionGenomePolicy.recordEvidence(
                state = state,
                ruleId = rule.id,
                evidence = item,
                provenance = provenance(
                    kind = ConstitutionSourceKind.TOOL_RESULT,
                    id = "tool-result-$index",
                    at = item.at,
                    taskId = item.taskId
                )
            )
        }

        val learned = state.rules.single { it.id == rule.id }
        assertEquals(
            ConstitutionRuleStatus.LEARNED,
            learned.status
        )
        assertEquals(
            ConstitutionAuthority.ADVISORY,
            learned.authority
        )
        assertTrue(
            ConstitutionGenomePolicy.canPromoteToLearned(learned)
        )
    }

    @Test
    fun repeatedEvidenceFromOnlyOneContextDoesNotPromote() {
        var state = ConstitutionGenomeState()
        val rule = ConstitutionGenomePolicy.propose(
            source = provenance(
                kind = ConstitutionSourceKind.PROJECT_ARTIFACT,
                id = "project-observation"
            ),
            claimKey = "one-task-pattern",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.STRATEGY,
            statement = "One exact path seems reliable.",
            rationale = "Fixture for context diversity.",
            scope = scope()
        )
        state = ConstitutionGenomePolicy.add(state, rule)

        listOf(
            verified("e1", "same-task", 200),
            verified("e2", "same-task", 300),
            verified("e3", "same-task", 400),
            verified("e4", "same-task", 500)
        ).forEachIndexed { index, item ->
            state = ConstitutionGenomePolicy.recordEvidence(
                state,
                rule.id,
                item,
                provenance(
                    kind = ConstitutionSourceKind.TOOL_RESULT,
                    id = "result-$index",
                    at = item.at,
                    taskId = item.taskId
                )
            )
        }

        val current = state.rules.single { it.id == rule.id }
        assertNotEquals(
            ConstitutionRuleStatus.LEARNED,
            current.status
        )
    }

    @Test
    fun importedSourceDeviceEvidenceNeverCountsAsLocalProof() {
        var state = ConstitutionGenomeState()
        val rule = ConstitutionGenomePolicy.propose(
            source = provenance(
                kind = ConstitutionSourceKind.IMPORTED_SOURCE_DEVICE,
                id = "phone-bundle"
            ),
            claimKey = "portable-path-preference",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.STRATEGY,
            statement = "A source-device path worked repeatedly.",
            rationale = "Imported evidence must be revalidated locally.",
            scope = scope(
                kind = ConstitutionScopeKind.DEVICE,
                key = "new-device"
            )
        )
        state = ConstitutionGenomePolicy.add(state, rule)

        repeat(8) { index ->
            val imported = ConstitutionEvidenceRef(
                id = "imported-$index",
                kind = ConstitutionEvidenceKind.IMPORTED_SOURCE_DEVICE,
                locallyVerified = false,
                taskId = "remote-$index",
                projectId = "remote-project",
                at = (200 + index).toLong()
            )
            state = ConstitutionGenomePolicy.recordEvidence(
                state,
                rule.id,
                imported,
                provenance(
                    kind = ConstitutionSourceKind.IMPORTED_SOURCE_DEVICE,
                    id = "remote-$index",
                    at = imported.at,
                    projectId = "remote-project",
                    taskId = imported.taskId
                )
            )
        }

        val current = state.rules.single { it.id == rule.id }
        assertEquals(
            ConstitutionRuleStatus.OBSERVATION,
            current.status
        )
        assertFalse(
            ConstitutionGenomePolicy.canPromoteToLearned(current)
        )
    }

    @Test
    fun modelTextCannotBeMarkedLocallyVerified() {
        val result = runCatching {
            ConstitutionEvidenceRef(
                id = "model-text",
                kind = ConstitutionEvidenceKind.MODEL_TEXT,
                locallyVerified = true,
                taskId = "task-a",
                at = 100
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun userConstraintIsActiveButNotHardGuard() {
        val rule = ConstitutionGenomePolicy.propose(
            source = provenance(
                kind = ConstitutionSourceKind.USER,
                id = "user-directive-1"
            ),
            claimKey = "user-long-only",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.USER_CONSTRAINT,
            statement = "Use only long-side trading logic for this project.",
            rationale = "Direct user constraint.",
            scope = scope()
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
    fun learnedRuleNeverBecomesHardGuard() {
        var state = ConstitutionGenomeState()
        val rule = ConstitutionGenomePolicy.propose(
            source = provenance(
                kind = ConstitutionSourceKind.MODEL,
                id = "proposal",
                modelId = "fixture"
            ),
            claimKey = "learned-not-hard",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.STRATEGY,
            statement = "Prefer discovery before reading an uncertain path.",
            rationale = "Useful strategy after repeated proof.",
            scope = scope()
        )
        state = ConstitutionGenomePolicy.add(state, rule)

        listOf(
            verified("e1", "task-a", 200),
            verified("e2", "task-b", 300),
            verified("e3", "task-c", 400)
        ).forEachIndexed { index, item ->
            state = ConstitutionGenomePolicy.recordEvidence(
                state,
                rule.id,
                item,
                provenance(
                    ConstitutionSourceKind.TOOL_RESULT,
                    "evidence-$index",
                    at = item.at,
                    taskId = item.taskId
                )
            )
        }

        val learned = state.rules.single { it.id == rule.id }
        assertEquals(
            ConstitutionRuleStatus.LEARNED,
            learned.status
        )
        assertEquals(
            ConstitutionAuthority.ADVISORY,
            learned.authority
        )
        assertFalse(
            learned.status == ConstitutionRuleStatus.HARD_INVARIANT
        )
    }

    @Test
    fun conflictingStancesRemainVisibleAndBecomeContested() {
        var state = ConstitutionGenomeState()
        val affirm = ConstitutionGenomePolicy.propose(
            source = provenance(
                ConstitutionSourceKind.MODEL,
                "model-a",
                modelId = "model-a"
            ),
            claimKey = "provider-retry",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.RECOVERY,
            statement = "Retry the same provider once.",
            rationale = "One hypothesis.",
            scope = scope()
        )
        val reject = ConstitutionGenomePolicy.propose(
            source = provenance(
                ConstitutionSourceKind.MODEL,
                "model-b",
                at = 110,
                modelId = "model-b"
            ),
            claimKey = "provider-retry",
            stance = ConstitutionStance.REJECT,
            kind = ConstitutionRuleKind.RECOVERY,
            statement = "Do not retry the same provider; switch route.",
            rationale = "Conflicting hypothesis.",
            scope = scope()
        )

        state = ConstitutionGenomePolicy.add(state, affirm)
        state = ConstitutionGenomePolicy.add(state, reject)

        val view = ConstitutionGenomePolicy.view(state)
        assertEquals(1, view.conflicts.size)
        assertEquals(
            setOf(
                ConstitutionRuleStatus.CONTESTED
            ),
            view.rules.map { it.status }.toSet()
        )
        assertEquals(2, view.rules.size)
    }

    @Test
    fun hardInvariantWinsAuthorityAndOppositeRuleIsContested() {
        var state = ConstitutionGenomeState(
            rules = ConstitutionDnaManifest.hardInvariants()
        )
        val opposite = ConstitutionGenomePolicy.propose(
            source = provenance(
                ConstitutionSourceKind.MODEL,
                "unsafe-model",
                modelId = "unsafe-model"
            ),
            claimKey = "learned-authority-boundary",
            stance = ConstitutionStance.REJECT,
            kind = ConstitutionRuleKind.SAFETY,
            statement = "Learned memory may grant permissions.",
            rationale = "Unsafe conflicting proposal.",
            scope = ConstitutionScope(
                ConstitutionScopeKind.GLOBAL,
                "lumena"
            )
        )
        state = ConstitutionGenomePolicy.add(state, opposite)

        val hard = state.rules.single {
            it.id == "INV-AUTHORITY-001"
        }
        val contested = state.rules.single {
            it.id == opposite.id
        }

        assertEquals(
            ConstitutionRuleStatus.HARD_INVARIANT,
            hard.status
        )
        assertEquals(
            ConstitutionAuthority.HARD_GUARD,
            hard.authority
        )
        assertEquals(
            ConstitutionRuleStatus.CONTESTED,
            contested.status
        )
    }

    @Test
    fun hardInvariantCannotBeSupersededByLearnedRule() {
        val hard = ConstitutionDnaManifest.hardInvariants()
            .first { it.id == "INV-EVIDENCE-001" }
        val replacement = ConstitutionGenomePolicy.propose(
            source = provenance(
                ConstitutionSourceKind.MODEL,
                "replacement",
                modelId = "fixture"
            ),
            claimKey = hard.claimKey,
            stance = hard.stance,
            kind = hard.kind,
            statement = "A weaker evidence rule.",
            rationale = "Fixture.",
            scope = hard.scope
        )
        val state = ConstitutionGenomeState(
            rules = listOf(hard, replacement)
        )

        val attempt = runCatching {
            ConstitutionGenomePolicy.supersedeAdvisory(
                state,
                oldRuleId = hard.id,
                replacementRuleId = replacement.id,
                at = 500
            )
        }

        assertTrue(attempt.isFailure)
    }

    @Test
    fun manifestHardRulesCarryRationaleEnforcementAndRegressionTests() {
        val rules = ConstitutionDnaManifest.hardInvariants()

        assertTrue(rules.isNotEmpty())
        assertEquals(
            rules.size,
            rules.map { it.id }.distinct().size
        )
        rules.forEach { rule ->
            assertEquals(
                ConstitutionRuleStatus.HARD_INVARIANT,
                rule.status
            )
            assertEquals(
                ConstitutionAuthority.HARD_GUARD,
                rule.authority
            )
            assertTrue(rule.rationale.isNotBlank())
            assertTrue(rule.testRefs.isNotEmpty())
            assertTrue(rule.enforcementPoints.isNotEmpty())
            assertTrue(
                rule.provenance.any {
                    it.sourceKind ==
                        ConstitutionSourceKind.SYSTEM_SEED
                }
            )
        }
    }

    @Test
    fun effectiveRulesIncludeHardLearnedAndUserConstraintButNotObservations() {
        var state = ConstitutionGenomeState(
            rules = ConstitutionDnaManifest.hardInvariants()
        )

        val user = ConstitutionGenomePolicy.propose(
            source = provenance(
                ConstitutionSourceKind.USER,
                "user-constraint"
            ),
            claimKey = "project-style",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.USER_CONSTRAINT,
            statement = "Keep generated code text-only.",
            rationale = "Direct user constraint.",
            scope = scope()
        )
        state = ConstitutionGenomePolicy.add(state, user)

        var learned = ConstitutionGenomePolicy.propose(
            source = provenance(
                ConstitutionSourceKind.MODEL,
                "model-proposal",
                modelId = "fixture"
            ),
            claimKey = "project-recovery-style",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.RECOVERY,
            statement = "Rediscover path before retrying.",
            rationale = "Verified project recovery.",
            scope = scope()
        )
        state = ConstitutionGenomePolicy.add(state, learned)

        listOf(
            verified("e1", "t1", 200),
            verified("e2", "t2", 300),
            verified("e3", "t3", 400)
        ).forEachIndexed { index, evidence ->
            state = ConstitutionGenomePolicy.recordEvidence(
                state,
                learned.id,
                evidence,
                provenance(
                    ConstitutionSourceKind.TOOL_RESULT,
                    "result-$index",
                    at = evidence.at,
                    taskId = evidence.taskId
                )
            )
        }
        learned = state.rules.single { it.id == learned.id }

        val observation = ConstitutionGenomePolicy.propose(
            source = provenance(
                ConstitutionSourceKind.MODEL,
                "new-model-text",
                at = 600,
                modelId = "fixture-2"
            ),
            claimKey = "untested-hypothesis",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.STRATEGY,
            statement = "An untested model hypothesis.",
            rationale = "No world evidence yet.",
            scope = scope()
        )
        state = ConstitutionGenomePolicy.add(state, observation)

        val effective = ConstitutionGenomePolicy.effectiveRules(
            state,
            scope()
        )

        assertTrue(
            effective.any { it.id == user.id }
        )
        assertTrue(
            effective.any { it.id == learned.id }
        )
        assertTrue(
            effective.any {
                it.status ==
                    ConstitutionRuleStatus.HARD_INVARIANT
            }
        )
        assertFalse(
            effective.any { it.id == observation.id }
        )
    }
}
