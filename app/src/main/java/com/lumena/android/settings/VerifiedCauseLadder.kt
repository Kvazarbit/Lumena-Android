package com.lumena.android.settings

import com.lumena.android.agent.core.ToolRegistry

enum class CauseLadderStage {
    UNKNOWN,
    STRUCTURED,
    HYPOTHESIS,
    PROBED,
    VERIFIED,
    CONTESTED,
    REJECTED
}

enum class CauseProbeVerdict {
    SUPPORTS,
    REJECTS,
    INCONCLUSIVE
}

data class CauseHypothesis(
    val id: String,
    val causalLinkId: String,
    val claimHash: String,
    val proposedByModelId: String,
    val structuredFailureClass: String? = null,
    val structuredErrorCode: String? = null,
    val structuredDependency: String? = null
)

data class CauseProbeEvidence(
    val hypothesisId: String,
    val evidenceId: String,
    val tool: String,
    val target: String,
    val verdict: CauseProbeVerdict
)

data class CauseAssessment(
    val stage: CauseLadderStage,
    val supportingEvidenceIds: List<String> = emptyList(),
    val rejectingEvidenceIds: List<String> = emptyList()
)

/**
 * Pure evidence policy for Phase 3.
 *
 * A model-proposed explanation is only a HYPOTHESIS. It cannot become causal
 * evidence by repetition or confidence. Promotion requires real, registered
 * tool evidence. VERIFIED requires two independent supporting probe signatures.
 *
 * This object has no execution or permission API and stores only claim hashes,
 * never raw model prose.
 */
object VerifiedCauseLadderPolicy {
    fun baseStage(
        link: FractalCausalLink
    ): CauseLadderStage =
        when (link.causeKnowledge) {
            FractalCausalCauseKnowledge.UNKNOWN_NOT_CAPTURED ->
                CauseLadderStage.UNKNOWN

            FractalCausalCauseKnowledge.STRUCTURED_TOOL_FAILURE ->
                CauseLadderStage.STRUCTURED
        }

    fun propose(
        link: FractalCausalLink,
        claim: String,
        modelId: String
    ): CauseHypothesis? {
        val cleanClaim = claim.trim()
        val cleanModel = modelId.trim()
        if (cleanClaim.isBlank() || cleanModel.isBlank()) return null

        val claimHash =
            FractalExperienceCanvasPolicy
                .hash(cleanClaim)
                .take(24)

        return CauseHypothesis(
            id = "cause-hyp-" +
                FractalExperienceCanvasPolicy
                    .hash(
                        link.id + "|" +
                            claimHash + "|" +
                            cleanModel
                    )
                    .take(20),
            causalLinkId = link.id,
            claimHash = claimHash,
            proposedByModelId = cleanModel.take(160),
            structuredFailureClass =
                link.causeFailureClass,
            structuredErrorCode =
                link.causeErrorCode,
            structuredDependency =
                link.causeDependency
        )
    }

    fun assess(
        hypothesis: CauseHypothesis,
        probes: List<CauseProbeEvidence>
    ): CauseAssessment {
        val valid = probes
            .asSequence()
            .filter {
                it.hypothesisId == hypothesis.id &&
                    it.evidenceId.isNotBlank() &&
                    ToolRegistry.get(it.tool) != null
            }
            .map {
                it.copy(
                    tool = ToolRegistry.canonicalize(it.tool),
                    evidenceId = it.evidenceId.take(160),
                    target = it.target
                        .replace(Regex("[\\r\\n\\t]+"), " ")
                        .trim()
                        .take(240)
                )
            }
            .distinctBy {
                it.evidenceId + "|" +
                    it.tool + "|" +
                    it.target + "|" +
                    it.verdict.name
            }
            .toList()

        val support = valid.filter {
            it.verdict == CauseProbeVerdict.SUPPORTS
        }
        val reject = valid.filter {
            it.verdict == CauseProbeVerdict.REJECTS
        }

        val supportingIds = support
            .map { it.evidenceId }
            .distinct()
            .take(32)
        val rejectingIds = reject
            .map { it.evidenceId }
            .distinct()
            .take(32)

        val stage = when {
            support.isNotEmpty() && reject.isNotEmpty() ->
                CauseLadderStage.CONTESTED

            reject.isNotEmpty() ->
                CauseLadderStage.REJECTED

            independentSupportingSignatures(support) >= 2 ->
                CauseLadderStage.VERIFIED

            support.isNotEmpty() ->
                CauseLadderStage.PROBED

            else ->
                CauseLadderStage.HYPOTHESIS
        }

        return CauseAssessment(
            stage = stage,
            supportingEvidenceIds = supportingIds,
            rejectingEvidenceIds = rejectingIds
        )
    }

    private fun independentSupportingSignatures(
        probes: List<CauseProbeEvidence>
    ): Int =
        probes
            .map {
                ToolRegistry.canonicalize(it.tool) +
                    "|" + it.target
            }
            .distinct()
            .size
}
