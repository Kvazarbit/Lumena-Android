package com.lumena.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedCauseLadderPolicyTest {
    private fun link(
        structured: Boolean
    ): FractalCausalLink =
        FractalCausalLink(
            id = "link-1",
            sourceRecordId = "record-1",
            sourceTaskHash = "task",
            scopeHash = "scope",
            failedStepIndex = 0,
            failedTool = "python.run",
            targetHints = listOf("script=demo.py"),
            recoveryTools = listOf("file.read", "python.run"),
            recoveryOutcomes = listOf(true, true),
            resolution = FractalCausalResolution.RECOVERED,
            causeKnowledge =
                if (structured) {
                    FractalCausalCauseKnowledge
                        .STRUCTURED_TOOL_FAILURE
                } else {
                    FractalCausalCauseKnowledge
                        .UNKNOWN_NOT_CAPTURED
                },
            causeFailureClass =
                if (structured) "INVALID_INPUT" else null,
            causeErrorCode =
                if (structured) "PYTHON_SCRIPT_REQUIRED" else null,
            causeRetryable =
                if (structured) true else null,
            causeDependency =
                if (structured) "tool-schema" else null,
            evidenceIds = listOf("event-fail", "event-ok"),
            contributorModelIds = listOf("chatgpt"),
            origin = FractalExperienceOrigin.LIVE,
            updatedAt = 1_000L
        )

    @Test
    fun baseStageSeparatesUnknownFromStructuredEvidence() {
        assertEquals(
            CauseLadderStage.UNKNOWN,
            VerifiedCauseLadderPolicy.baseStage(
                link(structured = false)
            )
        )
        assertEquals(
            CauseLadderStage.STRUCTURED,
            VerifiedCauseLadderPolicy.baseStage(
                link(structured = true)
            )
        )
    }

    @Test
    fun modelExplanationCreatesOnlyHypothesisAndStoresNoRawProse() {
        val raw =
            "The file probably failed because a dependency changed."
        val hypothesis =
            VerifiedCauseLadderPolicy.propose(
                link = link(structured = false),
                claim = raw,
                modelId = "gemma"
            )

        assertNotNull(hypothesis)
        hypothesis!!
        assertEquals(24, hypothesis.claimHash.length)
        assertEquals("gemma", hypothesis.proposedByModelId)
        assertFalse(hypothesis.toString().contains(raw))
        assertTrue(
            VerifiedCauseLadderPolicy
                .assess(hypothesis, emptyList())
                .stage == CauseLadderStage.HYPOTHESIS
        )
    }

    @Test
    fun preHashedRuntimeClaimCanCreateHypothesisWithoutRawProse() {
        val hash = "0123456789abcdef01234567"
        val hypothesis =
            VerifiedCauseLadderPolicy.proposeHashed(
                anchorId = "cause-fail-1",
                claimHash = hash,
                modelId = "gemma",
                structuredFailureClass =
                    "STATE_DRIFT",
                structuredErrorCode =
                    "PATH_CHANGED",
                structuredDependency =
                    "filesystem"
            )

        assertNotNull(hypothesis)
        hypothesis!!
        assertEquals(hash, hypothesis.claimHash)
        assertEquals(
            "cause-fail-1",
            hypothesis.causalLinkId
        )
        assertEquals(
            "STATE_DRIFT",
            hypothesis.structuredFailureClass
        )
        assertFalse(
            hypothesis.toString()
                .contains(
                    "The target moved"
                )
        )
    }

    @Test
    fun malformedRuntimeClaimHashCannotBecomeHypothesis() {
        val hypothesis =
            VerifiedCauseLadderPolicy.proposeHashed(
                anchorId = "cause-fail-1",
                claimHash = "not-a-hash",
                modelId = "gemma"
            )

        assertEquals(null, hypothesis)
    }

    @Test
    fun oneSupportingProbeIsProbedButNotVerified() {
        val hypothesis = requireNotNull(
            VerifiedCauseLadderPolicy.propose(
                link(false),
                "Path drift caused the failure",
                "chatgpt"
            )
        )

        val assessment =
            VerifiedCauseLadderPolicy.assess(
                hypothesis,
                listOf(
                    CauseProbeEvidence(
                        hypothesisId = hypothesis.id,
                        evidenceId = "ev-1",
                        tool = "file.read",
                        target = "demo.py",
                        verdict =
                            CauseProbeVerdict.SUPPORTS
                    )
                )
            )

        assertEquals(
            CauseLadderStage.PROBED,
            assessment.stage
        )
        assertEquals(
            listOf("ev-1"),
            assessment.supportingEvidenceIds
        )
    }

    @Test
    fun twoIndependentSupportingProbeSignaturesCanVerifyCause() {
        val hypothesis = requireNotNull(
            VerifiedCauseLadderPolicy.propose(
                link(true),
                "Tool schema was invalid",
                "gemma"
            )
        )

        val assessment =
            VerifiedCauseLadderPolicy.assess(
                hypothesis,
                listOf(
                    CauseProbeEvidence(
                        hypothesis.id,
                        "ev-1",
                        "file.read",
                        "schema.json",
                        CauseProbeVerdict.SUPPORTS
                    ),
                    CauseProbeEvidence(
                        hypothesis.id,
                        "ev-2",
                        "python.syntax_check",
                        "demo.py",
                        CauseProbeVerdict.SUPPORTS
                    )
                )
            )

        assertEquals(
            CauseLadderStage.VERIFIED,
            assessment.stage
        )
        assertEquals(2, assessment.supportingEvidenceIds.size)
    }

    @Test
    fun duplicateProbeSignatureCannotFakeIndependentVerification() {
        val hypothesis = requireNotNull(
            VerifiedCauseLadderPolicy.propose(
                link(false),
                "Missing file caused the failure",
                "chatgpt"
            )
        )

        val assessment =
            VerifiedCauseLadderPolicy.assess(
                hypothesis,
                listOf(
                    CauseProbeEvidence(
                        hypothesis.id,
                        "ev-1",
                        "file.read",
                        "demo.py",
                        CauseProbeVerdict.SUPPORTS
                    ),
                    CauseProbeEvidence(
                        hypothesis.id,
                        "ev-2",
                        "file.read",
                        "demo.py",
                        CauseProbeVerdict.SUPPORTS
                    )
                )
            )

        assertEquals(
            CauseLadderStage.PROBED,
            assessment.stage
        )
        assertEquals(2, assessment.supportingEvidenceIds.size)
    }

    @Test
    fun supportingAndRejectingEvidenceRemainContested() {
        val hypothesis = requireNotNull(
            VerifiedCauseLadderPolicy.propose(
                link(false),
                "Dependency changed",
                "gemma"
            )
        )

        val assessment =
            VerifiedCauseLadderPolicy.assess(
                hypothesis,
                listOf(
                    CauseProbeEvidence(
                        hypothesis.id,
                        "ev-support",
                        "file.read",
                        "config.json",
                        CauseProbeVerdict.SUPPORTS
                    ),
                    CauseProbeEvidence(
                        hypothesis.id,
                        "ev-reject",
                        "system.info",
                        "",
                        CauseProbeVerdict.REJECTS
                    )
                )
            )

        assertEquals(
            CauseLadderStage.CONTESTED,
            assessment.stage
        )
        assertEquals(
            listOf("ev-support"),
            assessment.supportingEvidenceIds
        )
        assertEquals(
            listOf("ev-reject"),
            assessment.rejectingEvidenceIds
        )
    }

    @Test
    fun rejectingEvidenceRejectsHypothesis() {
        val hypothesis = requireNotNull(
            VerifiedCauseLadderPolicy.propose(
                link(false),
                "The target disappeared",
                "chatgpt"
            )
        )

        val assessment =
            VerifiedCauseLadderPolicy.assess(
                hypothesis,
                listOf(
                    CauseProbeEvidence(
                        hypothesis.id,
                        "ev-1",
                        "file.read",
                        "demo.py",
                        CauseProbeVerdict.REJECTS
                    )
                )
            )

        assertEquals(
            CauseLadderStage.REJECTED,
            assessment.stage
        )
    }

    @Test
    fun invalidOrForeignProbeDoesNotPromoteHypothesis() {
        val hypothesis = requireNotNull(
            VerifiedCauseLadderPolicy.propose(
                link(false),
                "Something changed",
                "chatgpt"
            )
        )

        val assessment =
            VerifiedCauseLadderPolicy.assess(
                hypothesis,
                listOf(
                    CauseProbeEvidence(
                        "other-hypothesis",
                        "ev-1",
                        "file.read",
                        "demo.py",
                        CauseProbeVerdict.SUPPORTS
                    ),
                    CauseProbeEvidence(
                        hypothesis.id,
                        "ev-2",
                        "not.a.real.tool",
                        "demo.py",
                        CauseProbeVerdict.SUPPORTS
                    )
                )
            )

        assertEquals(
            CauseLadderStage.HYPOTHESIS,
            assessment.stage
        )
        assertTrue(assessment.supportingEvidenceIds.isEmpty())
    }
}
