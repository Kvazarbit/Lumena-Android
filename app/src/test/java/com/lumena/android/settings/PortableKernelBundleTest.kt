package com.lumena.android.settings

import com.lumena.android.agent.core.ConstitutionCapsule
import com.lumena.android.agent.core.CoreDna
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableKernelBundleTest {
    private fun positive(
        signature: String = "a".repeat(64),
        tool: String = "file.read",
        target: String = "path=README.md",
        occurrences: Int = 3,
        firstSeenAt: Long = 100,
        lastSeenAt: Long = 200
    ) = ExperienceAnchor(
        id = "anchor-$signature",
        signature = signature,
        tool = tool,
        target = target,
        valence = ExperienceValence.POSITIVE,
        summary = "success: source output is intentionally not exported",
        occurrences = occurrences,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt
    )

    private fun negative() = ExperienceAnchor(
        id = "negative",
        signature = "b".repeat(64),
        tool = "file.read",
        target = "path=missing.txt",
        valence = ExperienceValence.NEGATIVE,
        summary = "failure: missing",
        occurrences = 4,
        firstSeenAt = 100,
        lastSeenAt = 300
    )

    private fun rule(
        id: String,
        kind: String,
        status: LandscapeRuleStatus
    ) = LandscapeRule(
        id = id,
        nodeId = "node-$id",
        kind = kind,
        text = "Trusted template for $kind",
        scope = "scopehash",
        intent = "FILE_INSPECTION",
        status = status,
        evidenceIds = listOf("e1")
    )

    @Test
    fun exportContainsOnlyPositiveExperienceAndActivePreferRules() {
        val payload = PortableKernelPolicy.buildPayload(
            localAnchors = listOf(positive(), negative()),
            localRules = listOf(
                rule("prefer-active", "PREFER", LandscapeRuleStatus.ACTIVE),
                rule("recheck-active", "RECHECK", LandscapeRuleStatus.ACTIVE),
                rule("prefer-candidate", "PREFER", LandscapeRuleStatus.CANDIDATE)
            ),
            imported = null,
            exportedAt = 1_000,
            sourceAppVersionCode = 29,
            sourceDeviceHash = PortableKernelPolicy.hash("device-a")
        )

        assertEquals(1, payload.positiveExperience.size)
        assertEquals("file.read", payload.positiveExperience.single().tool)
        assertEquals(1, payload.dormantRules.size)
        assertEquals("prefer-active", payload.dormantRules.single().id)
        assertTrue(payload.requiresLocalRevalidation)
        assertEquals(CoreDna.VERSION, payload.coreDnaVersion)
        assertEquals(ConstitutionCapsule.VERSION, payload.constitutionCapsuleVersion)
    }

    @Test
    fun portableMergeDoesNotInflateOccurrenceCount() {
        val imported = PortableKernelPayload(
            coreDnaVersion = CoreDna.VERSION,
            constitutionCapsuleVersion = ConstitutionCapsule.VERSION,
            coordinatorContractVersion = PortableKernelPolicy.COORDINATOR_CONTRACT_VERSION,
            exportedAt = 500,
            sourceAppVersionCode = 29,
            sourceDeviceHash = PortableKernelPolicy.hash("old-device"),
            positiveExperience = listOf(
                PortableExperienceSeed(
                    signature = "a".repeat(64),
                    tool = "file.read",
                    target = "path=README.md",
                    occurrences = 7,
                    firstSeenAt = 50,
                    lastSeenAt = 150
                )
            )
        )

        val payload = PortableKernelPolicy.buildPayload(
            localAnchors = listOf(
                positive(
                    occurrences = 3,
                    firstSeenAt = 100,
                    lastSeenAt = 300
                )
            ),
            localRules = emptyList(),
            imported = imported,
            exportedAt = 1_000,
            sourceAppVersionCode = 29,
            sourceDeviceHash = PortableKernelPolicy.hash("new-device")
        )

        val seed = payload.positiveExperience.single()
        assertEquals(7, seed.occurrences)
        assertEquals(50, seed.firstSeenAt)
        assertEquals(300, seed.lastSeenAt)
    }

    @Test
    fun importedAdviceIsExplicitlyAdvisoryAndRequiresRevalidation() {
        val payload = PortableKernelPayload(
            coreDnaVersion = CoreDna.VERSION,
            constitutionCapsuleVersion = ConstitutionCapsule.VERSION,
            coordinatorContractVersion = PortableKernelPolicy.COORDINATOR_CONTRACT_VERSION,
            exportedAt = 1_000,
            sourceAppVersionCode = 29,
            sourceDeviceHash = PortableKernelPolicy.hash("device"),
            positiveExperience = listOf(
                PortableExperienceSeed(
                    signature = "c".repeat(64),
                    tool = "web.search",
                    target = "query=latest python news",
                    occurrences = 5,
                    firstSeenAt = 100,
                    lastSeenAt = 900
                )
            )
        )

        val advice = PortableKernelPolicy.advice(
            payload,
            query = "python news web.search",
            limit = 3
        ).single()

        assertTrue(advice.contains("PORTABLE VERIFIED EXPERIENCE"))
        assertTrue(advice.contains("revalidate locally"))
        assertTrue(advice.contains("not permission"))
        assertTrue(advice.contains("web.search"))
    }

    @Test
    fun dormantPortableRulesArePreservedButNotReturnedAsRuntimeAdvice() {
        val payload = PortableKernelPayload(
            coreDnaVersion = CoreDna.VERSION,
            constitutionCapsuleVersion = ConstitutionCapsule.VERSION,
            coordinatorContractVersion = PortableKernelPolicy.COORDINATOR_CONTRACT_VERSION,
            exportedAt = 1_000,
            sourceAppVersionCode = 29,
            sourceDeviceHash = PortableKernelPolicy.hash("device"),
            dormantRules = listOf(
                PortableRuleSeed(
                    id = "r1",
                    kind = "PREFER",
                    text = "This dormant rule must not auto-activate",
                    intent = "CODE_WORK",
                    sourceScopeHash = "scope"
                )
            )
        )

        assertTrue(PortableKernelPolicy.advice(payload, "code work", 3).isEmpty())
    }

    @Test
    fun codecRoundTripPreservesIntegrityMetadata() {
        val payload = PortableKernelPolicy.buildPayload(
            localAnchors = listOf(positive()),
            localRules = emptyList(),
            imported = null,
            exportedAt = 1_000,
            sourceAppVersionCode = 29,
            sourceDeviceHash = PortableKernelPolicy.hash("device")
        )

        val decoded = PortableKernelCodec.decode(
            PortableKernelCodec.encode(payload)
        )

        assertEquals(payload, decoded)
        assertTrue(PortableKernelPolicy.versionsMatchCurrentRuntime(decoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tamperedBundleFailsIntegrityCheck() {
        val payload = PortableKernelPolicy.buildPayload(
            localAnchors = listOf(positive()),
            localRules = emptyList(),
            imported = null,
            exportedAt = 1_000,
            sourceAppVersionCode = 29,
            sourceDeviceHash = PortableKernelPolicy.hash("device")
        )
        val encoded = PortableKernelCodec.encode(payload)
        val tampered = encoded.replace("README.md", "SECRETS.md")

        PortableKernelCodec.decode(tampered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownPortableToolIsRejected() {
        val payload = PortableKernelPayload(
            coreDnaVersion = CoreDna.VERSION,
            constitutionCapsuleVersion = ConstitutionCapsule.VERSION,
            coordinatorContractVersion = PortableKernelPolicy.COORDINATOR_CONTRACT_VERSION,
            exportedAt = 1_000,
            sourceAppVersionCode = 29,
            sourceDeviceHash = PortableKernelPolicy.hash("device"),
            positiveExperience = listOf(
                PortableExperienceSeed(
                    signature = "d".repeat(64),
                    tool = "shell.exec",
                    target = "cmd=anything",
                    occurrences = 1,
                    firstSeenAt = 100,
                    lastSeenAt = 100
                )
            )
        )

        PortableKernelCodec.encode(payload)
    }

    @Test
    fun portablePayloadNeverCarriesApprovalOrBridgeTokenFields() {
        val payload = PortableKernelPolicy.buildPayload(
            localAnchors = listOf(positive()),
            localRules = emptyList(),
            imported = null,
            exportedAt = 1_000,
            sourceAppVersionCode = 29,
            sourceDeviceHash = PortableKernelPolicy.hash("device")
        )
        val encoded = PortableKernelCodec.encode(payload)

        assertFalse(encoded.contains("bridgeToken", ignoreCase = true))
        assertFalse(encoded.contains("approval", ignoreCase = true))
        assertFalse(encoded.contains("bearer", ignoreCase = true))
    }
}
