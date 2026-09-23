package com.lumena.android.settings

import com.lumena.android.agent.core.ConstitutionAuthority
import com.lumena.android.agent.core.ConstitutionCapsule
import com.lumena.android.agent.core.ConstitutionDnaManifest
import com.lumena.android.agent.core.ConstitutionEvidenceKind
import com.lumena.android.agent.core.ConstitutionEvidenceRef
import com.lumena.android.agent.core.ConstitutionGenomePolicy
import com.lumena.android.agent.core.ConstitutionGenomeState
import com.lumena.android.agent.core.ConstitutionProvenance
import com.lumena.android.agent.core.ConstitutionRule
import com.lumena.android.agent.core.ConstitutionRuleKind
import com.lumena.android.agent.core.ConstitutionRuleStatus
import com.lumena.android.agent.core.ConstitutionScope
import com.lumena.android.agent.core.ConstitutionScopeKind
import com.lumena.android.agent.core.ConstitutionSourceKind
import com.lumena.android.agent.core.ConstitutionStance
import com.lumena.android.agent.core.CoreDna
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstitutionGenomeInspectorTest {
    private fun projectScope() = ConstitutionScope(
        kind = ConstitutionScopeKind.PROJECT,
        key = "project-a"
    )

    private fun globalScope() = ConstitutionScope(
        kind = ConstitutionScopeKind.GLOBAL,
        key = "lumena"
    )

    private fun learnedRule(): ConstitutionRule {
        var state = ConstitutionGenomeState()
        val proposal = ConstitutionGenomePolicy.propose(
            source = ConstitutionProvenance(
                sourceKind = ConstitutionSourceKind.MODEL,
                sourceId = "proposal",
                modelId = "model-a",
                projectId = "project-a",
                taskId = "task-1",
                at = 100L
            ),
            claimKey = "path-recovery",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.RECOVERY,
            statement = "Rediscover project paths before retrying.",
            rationale = "Repeated verified local recovery pattern.",
            scope = projectScope()
        )
        state = ConstitutionGenomePolicy.add(state, proposal)

        listOf(
            Triple("e1", "task-1", 200L),
            Triple("e2", "task-2", 300L),
            Triple("e3", "task-3", 400L)
        ).forEachIndexed { index, (id, taskId, at) ->
            state = ConstitutionGenomePolicy.recordEvidence(
                state = state,
                ruleId = proposal.id,
                evidence = ConstitutionEvidenceRef(
                    id = id,
                    kind = ConstitutionEvidenceKind.TOOL_RESULT,
                    locallyVerified = true,
                    taskId = taskId,
                    projectId = "project-a",
                    at = at
                ),
                provenance = ConstitutionProvenance(
                    sourceKind = ConstitutionSourceKind.TOOL_RESULT,
                    sourceId = "result-" + id,
                    modelId = if (index == 2) "model-b" else "model-a",
                    projectId = "project-a",
                    taskId = taskId,
                    at = at
                )
            )
        }

        return state.rules.single { it.id == proposal.id }
    }

    private fun userRule() = ConstitutionGenomePolicy.propose(
        source = ConstitutionProvenance(
            sourceKind = ConstitutionSourceKind.USER,
            sourceId = "user-turn",
            projectId = "project-a",
            taskId = "task-user",
            at = 500L
        ),
        claimKey = "project-output-mode",
        stance = ConstitutionStance.AFFIRM,
        kind = ConstitutionRuleKind.USER_CONSTRAINT,
        statement = "Keep project output text-only.",
        rationale = "Direct local user constraint.",
        scope = projectScope()
    )

    private fun portableLearned() = PortableConstitutionSeed(
        id = "portable-learned",
        claimKey = "path-recovery",
        stance = ConstitutionStance.AFFIRM.name,
        kind = ConstitutionRuleKind.RECOVERY.name,
        originAuthority = ConstitutionAuthority.ADVISORY.name,
        sourceStatus = ConstitutionRuleStatus.LEARNED.name,
        sourceScopeHash = PortableKernelPolicy.hash("PROJECT:source").take(32),
        statement = "Source-device recovery strategy.",
        rationale = "Verified on the source device only.",
        evidenceIds = listOf("source-e1", "source-e2", "source-e3"),
        contributorModelIds = listOf("model-source-a", "model-source-b"),
        updatedAt = 900L,
        activationRequirement = PortableKernelPolicy.LOCAL_REVALIDATION
    )

    private fun portableUser() = PortableConstitutionSeed(
        id = "portable-user",
        claimKey = "project-output-mode",
        stance = ConstitutionStance.AFFIRM.name,
        kind = ConstitutionRuleKind.USER_CONSTRAINT.name,
        originAuthority = ConstitutionAuthority.USER_CONSTRAINT.name,
        sourceStatus =
            ConstitutionRuleStatus.ACTIVE_USER_CONSTRAINT.name,
        sourceScopeHash = PortableKernelPolicy.hash("PROJECT:source").take(32),
        statement = "Source-device user preference.",
        rationale = "Needs explicit reconfirmation here.",
        evidenceIds = emptyList(),
        contributorModelIds = emptyList(),
        updatedAt = 950L,
        activationRequirement = PortableKernelPolicy.USER_RECONFIRMATION
    )

    private fun portablePayload() = PortableKernelPayload(
        schemaVersion = PortableKernelPolicy.SCHEMA_VERSION,
        coreDnaVersion = CoreDna.VERSION,
        constitutionCapsuleVersion = ConstitutionCapsule.VERSION,
        coordinatorContractVersion =
            PortableKernelPolicy.COORDINATOR_CONTRACT_VERSION,
        exportedAt = 1_000L,
        sourceAppVersionCode = 29L,
        sourceDeviceHash = PortableKernelPolicy.hash("source-device"),
        constitutionalSeeds = listOf(
            portableLearned(),
            portableUser()
        )
    )

    @Test
    fun hardInspectorCarriesRationaleThreatEnforcementAndRegressionTests() {
        val snapshot = ConstitutionGenomeInspectorPolicy.build(
            localState = ConstitutionGenomeState(),
            imported = null
        )

        val authority = snapshot.hardDna.single {
            it.id == "INV-AUTHORITY-001"
        }

        assertEquals(ConstitutionAuthority.HARD_GUARD.name, authority.authority)
        assertTrue(authority.rationale.isNotBlank())
        assertTrue(authority.threatPrevented.isNotBlank())
        assertTrue(authority.enforcementPoints.contains("ToolGate"))
        assertTrue(authority.regressionTests.isNotEmpty())
        assertTrue(authority.explanation.contains("Code-owned HARD_GUARD"))
    }

    @Test
    fun learnedInspectorExplainsLocalPromotionContextsAndModels() {
        val learned = learnedRule()
        val snapshot = ConstitutionGenomeInspectorPolicy.build(
            localState = ConstitutionGenomeState(
                rules = ConstitutionDnaManifest.hardInvariants() + learned
            ),
            imported = null
        )

        val entry = snapshot.learned.single { it.id == learned.id }

        assertEquals(3, entry.localEvidenceCount)
        assertEquals(3, entry.distinctLocalContexts)
        assertEquals(
            setOf("model-a", "model-b"),
            entry.contributorModelIds.toSet()
        )
        assertTrue(entry.provenance.any { it.contains("TOOL_RESULT") })
        assertTrue(entry.explanation.contains("3 locally verified"))
        assertTrue(entry.explanation.contains("advisory"))
        assertTrue(entry.explanation.contains("not permission"))
    }

    @Test
    fun contestedInspectorKeepsHardInvariantAndCompetingSourceVisible() {
        val opposite = ConstitutionGenomePolicy.propose(
            source = ConstitutionProvenance(
                sourceKind = ConstitutionSourceKind.MODEL,
                sourceId = "unsafe-model",
                modelId = "fixture-model",
                at = 100L
            ),
            claimKey = "learned-authority-boundary",
            stance = ConstitutionStance.REJECT,
            kind = ConstitutionRuleKind.SAFETY,
            statement = "Learned memory may grant permissions.",
            rationale = "Unsafe fixture.",
            scope = globalScope()
        )

        val local = ConstitutionGenomePolicy.add(
            ConstitutionGenomeState(
                rules = ConstitutionDnaManifest.hardInvariants()
            ),
            opposite
        )
        val snapshot = ConstitutionGenomeInspectorPolicy.build(
            localState = local,
            imported = null
        )

        val conflict = snapshot.contested.single {
            it.claimKey == "learned-authority-boundary"
        }
        assertTrue(conflict.hasHardInvariant)
        assertTrue(
            conflict.rules.any {
                it.id == "INV-AUTHORITY-001" &&
                    it.authority == ConstitutionAuthority.HARD_GUARD.name
            }
        )
        assertTrue(
            conflict.rules.any {
                it.id == opposite.id &&
                    it.status == ConstitutionRuleStatus.CONTESTED.name &&
                    it.sourceKinds.contains(ConstitutionSourceKind.MODEL.name)
            }
        )
    }

    @Test
    fun explicitLocalUserConstraintIsVisibleAsActiveButNeverHard() {
        val user = userRule()
        val snapshot = ConstitutionGenomeInspectorPolicy.build(
            localState = ConstitutionGenomeState(
                rules = ConstitutionDnaManifest.hardInvariants() + user
            ),
            imported = null
        )

        val entry = snapshot.userConstraints.single { it.id == user.id }
        assertEquals(
            ConstitutionRuleStatus.ACTIVE_USER_CONSTRAINT.name,
            entry.status
        )
        assertEquals(
            ConstitutionAuthority.USER_CONSTRAINT.name,
            entry.authority
        )
        assertFalse(entry.authority == ConstitutionAuthority.HARD_GUARD.name)
        assertTrue(entry.provenance.any { it.contains("USER") })
        assertTrue(entry.explanation.contains("Explicit local user directive"))
    }

    @Test
    fun importedRecordsStaySeparateAndExplainPendingActivation() {
        val imported = PortableKernelCodec.decode(
            PortableKernelCodec.encode(portablePayload())
        )
        val snapshot = ConstitutionGenomeInspectorPolicy.build(
            localState = ConstitutionGenomeState(),
            imported = imported
        )

        assertTrue(snapshot.learned.isEmpty())
        assertTrue(snapshot.userConstraints.isEmpty())

        val learned = snapshot.importedLearned.single()
        assertEquals(3, learned.sourceEvidenceCount)
        assertEquals(
            PortableKernelPolicy.LOCAL_REVALIDATION,
            learned.activationRequirement
        )
        assertEquals(
            setOf("model-source-a", "model-source-b"),
            learned.contributorModelIds.toSet()
        )
        assertEquals(imported.sourceDeviceHash, learned.sourceDeviceHash)
        assertTrue(learned.explanation.contains("does not count as local proof"))

        val user = snapshot.importedUserConstraints.single()
        assertEquals(
            PortableKernelPolicy.USER_RECONFIRMATION,
            user.activationRequirement
        )
        assertTrue(user.explanation.contains("not active locally"))
        assertTrue(user.explanation.contains("not permission"))
    }
}
