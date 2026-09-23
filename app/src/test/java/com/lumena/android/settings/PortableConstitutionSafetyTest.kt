package com.lumena.android.settings

import com.lumena.android.agent.core.ConstitutionAuthority
import com.lumena.android.agent.core.ConstitutionDnaManifest
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
import com.lumena.android.agent.core.CoreDna
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableConstitutionSafetyTest {
    private fun task(
        id: String = "task-current",
        projectId: String? = "project-a"
    ) = TaskState(
        id = id,
        projectId = projectId,
        goal = "Continue verified project work",
        status = TaskStatus.WAITING_MODEL
    )

    private fun projectScope() = ConstitutionScope(
        kind = ConstitutionScopeKind.PROJECT,
        key = "project-a"
    )

    private fun learnedSeed(
        id: String = "portable-learned",
        claimKey: String = "portable-recovery-pattern",
        stance: ConstitutionStance = ConstitutionStance.AFFIRM,
        kind: ConstitutionRuleKind = ConstitutionRuleKind.RECOVERY,
        statement: String = "Rediscover project paths before retrying a drifted read.",
        evidenceIds: List<String> = (1..8).map { "source-e$it" },
        updatedAt: Long = 900L
    ) = PortableConstitutionSeed(
        id = id,
        claimKey = claimKey,
        stance = stance.name,
        kind = kind.name,
        originAuthority = ConstitutionAuthority.ADVISORY.name,
        sourceStatus = ConstitutionRuleStatus.LEARNED.name,
        sourceScopeHash = PortableKernelPolicy
            .hash("PROJECT:source-project")
            .take(32),
        statement = statement,
        rationale = "Verified only on the source device.",
        evidenceIds = evidenceIds,
        contributorModelIds = listOf("model-a", "model-b"),
        updatedAt = updatedAt,
        activationRequirement = PortableKernelPolicy.LOCAL_REVALIDATION
    )

    private fun userConstraintSeed(
        statement: String = "Keep project output text-only."
    ) = PortableConstitutionSeed(
        id = "portable-user-constraint",
        claimKey = "project-output-mode",
        stance = ConstitutionStance.AFFIRM.name,
        kind = ConstitutionRuleKind.USER_CONSTRAINT.name,
        originAuthority = ConstitutionAuthority.USER_CONSTRAINT.name,
        sourceStatus =
            ConstitutionRuleStatus.ACTIVE_USER_CONSTRAINT.name,
        sourceScopeHash = PortableKernelPolicy
            .hash("PROJECT:source-project")
            .take(32),
        statement = statement,
        rationale = "Direct user constraint from the source device.",
        evidenceIds = emptyList(),
        contributorModelIds = emptyList(),
        updatedAt = 950L,
        activationRequirement = PortableKernelPolicy.USER_RECONFIRMATION
    )

    private fun payload(
        seeds: List<PortableConstitutionSeed>
    ) = PortableKernelPayload(
        schemaVersion = PortableKernelPolicy.SCHEMA_VERSION,
        coreDnaVersion = CoreDna.VERSION,
        constitutionCapsuleVersion =
            com.lumena.android.agent.core.ConstitutionCapsule.VERSION,
        coordinatorContractVersion =
            PortableKernelPolicy.COORDINATOR_CONTRACT_VERSION,
        exportedAt = 1_000L,
        sourceAppVersionCode = 29L,
        sourceDeviceHash = PortableKernelPolicy.hash("source-device"),
        constitutionalSeeds = seeds
    )

    @Test
    fun importedLearnedSeedStaysOutsideLocalRuntimeUntilLocalRevalidation() {
        val seed = learnedSeed()
        val imported = PortableKernelCodec.decode(
            PortableKernelCodec.encode(payload(listOf(seed)))
        )

        val portableAdvice = PortableKernelPolicy.advice(
            payload = imported,
            query = "recovery project paths drifted read",
            limit = 4
        )
        assertTrue(
            portableAdvice.any {
                it.contains("PORTABLE LEARNED CONSTITUTION") &&
                    it.contains("local revalidation required") &&
                    it.contains("not permission")
            }
        )

        val localRuntime = ConstitutionGenomeRuntime.promptLines(
            state = ConstitutionGenomeState(),
            task = task(),
            limit = 16
        )

        assertFalse(localRuntime.any { it.contains(seed.id) })
        assertFalse(localRuntime.any { it.contains(seed.statement) })
    }

    @Test
    fun sourceDeviceEvidenceDoesNotCountTowardLocalPromotion() {
        val imported = PortableKernelCodec.decode(
            PortableKernelCodec.encode(
                payload(
                    listOf(
                        learnedSeed(
                            evidenceIds = (1..16).map { "source-e$it" }
                        )
                    )
                )
            )
        )
        assertEquals(16, imported.constitutionalSeeds.single().evidenceIds.size)

        val localProposal = ConstitutionGenomePolicy.propose(
            source = ConstitutionProvenance(
                sourceKind = ConstitutionSourceKind.MODEL,
                sourceId = "local-model-proposal",
                modelId = "local-model",
                projectId = "project-a",
                taskId = "task-1",
                at = 100L
            ),
            claimKey = "portable-recovery-pattern",
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.RECOVERY,
            statement = "Rediscover project paths before retrying a drifted read.",
            rationale = "This local rule must earn its own evidence.",
            scope = projectScope()
        )
        var localState = ConstitutionGenomePolicy.add(
            ConstitutionGenomeState(),
            localProposal
        )

        listOf(
            Triple("local-e1", "task-1", 200L),
            Triple("local-e2", "task-2", 300L)
        ).forEach { (id, taskId, at) ->
            localState = ConstitutionGenomePolicy.recordEvidence(
                state = localState,
                ruleId = localProposal.id,
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
                    sourceId = "result-$id",
                    projectId = "project-a",
                    taskId = taskId,
                    at = at
                )
            )
        }

        val beforeThird = localState.rules.single {
            it.id == localProposal.id
        }
        assertFalse(
            beforeThird.status == ConstitutionRuleStatus.LEARNED
        )
        assertEquals(
            2,
            beforeThird.evidenceRefs.count { it.promotionEligible() }
        )

        localState = ConstitutionGenomePolicy.recordEvidence(
            state = localState,
            ruleId = localProposal.id,
            evidence = ConstitutionEvidenceRef(
                id = "local-e3",
                kind = ConstitutionEvidenceKind.TOOL_RESULT,
                locallyVerified = true,
                taskId = "task-3",
                projectId = "project-a",
                at = 400L
            ),
            provenance = ConstitutionProvenance(
                sourceKind = ConstitutionSourceKind.TOOL_RESULT,
                sourceId = "result-local-e3",
                projectId = "project-a",
                taskId = "task-3",
                at = 400L
            )
        )

        assertEquals(
            ConstitutionRuleStatus.LEARNED,
            localState.rules.single {
                it.id == localProposal.id
            }.status
        )
    }

    @Test
    fun duplicatePortableSeedDoesNotUnionOrInflateSourceEvidence() {
        val older = learnedSeed(
            id = "same-rule",
            evidenceIds = (1..8).map { "old-e$it" },
            updatedAt = 900L
        )
        val newer = learnedSeed(
            id = "same-rule",
            evidenceIds = listOf("new-e1", "new-e2"),
            updatedAt = 1_000L
        )
        val imported = payload(listOf(older, newer))

        val rebuilt = PortableKernelPolicy.buildPayload(
            localAnchors = emptyList(),
            localRules = emptyList(),
            imported = imported,
            exportedAt = 1_100L,
            sourceAppVersionCode = 30L,
            sourceDeviceHash = PortableKernelPolicy.hash("new-device"),
            localExecutionExamples = emptyList(),
            localConstitutionRules = emptyList()
        )

        assertEquals(1, rebuilt.constitutionalSeeds.size)
        assertEquals(
            listOf("new-e1", "new-e2"),
            rebuilt.constitutionalSeeds.single().evidenceIds
        )
    }

    @Test
    fun conflictingPortableRuleCannotHideCodeOwnedHardInvariant() {
        val importedConflict = learnedSeed(
            id = "portable-authority-conflict",
            claimKey = "learned-authority-boundary",
            stance = ConstitutionStance.REJECT,
            kind = ConstitutionRuleKind.SAFETY,
            statement = "Imported memory may grant permissions."
        )
        val portableAdvice = PortableKernelPolicy.advice(
            payload = payload(listOf(importedConflict)),
            query = "learned authority boundary permissions",
            limit = 4
        )
        assertTrue(portableAdvice.isNotEmpty())
        assertTrue(portableAdvice.all { it.contains("not permission") })

        val hardRuntime = ConstitutionGenomeRuntime.promptLines(
            state = ConstitutionGenomeState(
                rules = ConstitutionDnaManifest.hardInvariants()
            ),
            task = task(projectId = null),
            limit = 16
        )

        assertTrue(
            hardRuntime.any {
                it.contains("HARD DNA [INV-AUTHORITY-001]")
            }
        )
        assertFalse(
            hardRuntime.any {
                it.contains(importedConflict.statement)
            }
        )
    }

    @Test
    fun importedUserConstraintRecordDoesNotAutoActivate() {
        val seed = userConstraintSeed()
        val imported = PortableKernelCodec.decode(
            PortableKernelCodec.encode(payload(listOf(seed)))
        )

        val advice = PortableKernelPolicy.advice(
            payload = imported,
            query = "project output text only",
            limit = 4
        )
        assertTrue(
            advice.any {
                it.contains("PORTABLE USER CONSTRAINT RECORD") &&
                    it.contains("user reconfirmation required") &&
                    it.contains("not permission")
            }
        )

        val localRuntime = ConstitutionGenomeRuntime.promptLines(
            state = ConstitutionGenomeState(),
            task = task(),
            limit = 16
        )
        assertFalse(localRuntime.any { it.contains(seed.statement) })
    }

    @Test
    fun portableSchemaRejectsForgedHardAuthority() {
        val forged = learnedSeed().copy(
            originAuthority = ConstitutionAuthority.HARD_GUARD.name
        )

        val result = runCatching {
            PortableKernelCodec.encode(payload(listOf(forged)))
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun portableUserConstraintRequiresExplicitReconfirmationMarker() {
        val malformed = userConstraintSeed().copy(
            activationRequirement =
                PortableKernelPolicy.LOCAL_REVALIDATION
        )

        val result = runCatching {
            PortableKernelCodec.encode(payload(listOf(malformed)))
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun encodedPortableConstitutionHasNoExecutionAuthorityOrRawOutputFields() {
        val encoded = PortableKernelCodec.encode(
            payload(
                listOf(
                    learnedSeed(),
                    userConstraintSeed()
                )
            )
        )

        listOf(
            "bridgeToken",
            "bearer",
            "approval",
            "permissions",
            "permissionGrants",
            "stdout",
            "stderr",
            "rawOutput"
        ).forEach { forbidden ->
            assertFalse(
                "Portable bundle unexpectedly contains $forbidden",
                encoded.contains(forbidden, ignoreCase = true)
            )
        }
    }
}
