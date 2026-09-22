package com.lumena.android.agent.core

/**
 * Small typed outputs for the future System-1-style decision layer.
 *
 * These types carry bounded judgments only. They never execute tools, grant
 * permissions, change retry budgets, or override ConstitutionKernel.
 */
data class ReflexBool(
    val value: Boolean,
    val confidence: Double,
    val evidenceCount: Int = 0
) {
    init {
        require(confidence in 0.0..1.0)
        require(evidenceCount >= 0)
    }
}

data class ReflexScore(
    val value: Double,
    val confidence: Double,
    val evidenceCount: Int = 0
) {
    init {
        require(value in 0.0..1.0)
        require(confidence in 0.0..1.0)
        require(evidenceCount >= 0)
    }
}

enum class ReflexOption {
    RETRY_VARIANT,
    TRY_ALTERNATIVE,
    DEGRADE_PARTIAL,
    ASK_PLANNER,
    STOP
}

data class ReflexChoiceScore(
    val option: ReflexOption,
    val score: Double
) {
    init {
        require(score in 0.0..1.0)
    }
}

data class ReflexChoice(
    val scores: List<ReflexChoiceScore>,
    val confidence: Double,
    val evidenceCount: Int = 0
) {
    init {
        require(scores.isNotEmpty())
        require(scores.map { it.option }.distinct().size == scores.size)
        require(confidence in 0.0..1.0)
        require(evidenceCount >= 0)
    }

    fun best(): ReflexOption = scores
        .sortedWith(
            compareByDescending<ReflexChoiceScore> { it.score }
                .thenBy { it.option.ordinal }
        )
        .first()
        .option
}

data class ReflexCandidateSet(
    val allowed: Set<ReflexOption>,
    val constitutionalAnchor: ReflexOption,
    val reason: String
) {
    init {
        require(allowed.isNotEmpty())
        require(constitutionalAnchor in allowed)
    }
}

/**
 * Deterministic authority-preserving boundary for reflex decisions.
 *
 * The candidate set is derived from the already-authoritative
 * ConstitutionKernel decision. The reflex layer may only choose the same
 * disposition or a more conservative/non-executing fallback:
 *
 * RETRY_VARIANT   -> retry / alternative / planner / partial / stop
 * TRY_ALTERNATIVE -> alternative / planner / partial / stop
 * DEGRADE_PARTIAL -> partial / stop
 * STOP            -> stop only
 *
 * ASK_PLANNER is advisory only: it means "escalate to the deliberative model"
 * and does not authorize any tool.
 */
object ReflexKernel {
    fun candidates(
        event: FailureEvent,
        state: RecoveryState
    ): ReflexCandidateSet {
        val anchorDecision = ConstitutionKernel.decide(event, state)
        val anchor = anchorDecision.toReflexOption()

        val allowed = when (anchor) {
            ReflexOption.RETRY_VARIANT -> linkedSetOf(
                ReflexOption.RETRY_VARIANT,
                ReflexOption.TRY_ALTERNATIVE,
                ReflexOption.ASK_PLANNER,
                ReflexOption.DEGRADE_PARTIAL,
                ReflexOption.STOP
            )

            ReflexOption.TRY_ALTERNATIVE -> linkedSetOf(
                ReflexOption.TRY_ALTERNATIVE,
                ReflexOption.ASK_PLANNER,
                ReflexOption.DEGRADE_PARTIAL,
                ReflexOption.STOP
            )

            ReflexOption.DEGRADE_PARTIAL -> linkedSetOf(
                ReflexOption.DEGRADE_PARTIAL,
                ReflexOption.STOP
            )

            ReflexOption.STOP -> linkedSetOf(
                ReflexOption.STOP
            )

            ReflexOption.ASK_PLANNER ->
                error("ConstitutionKernel never returns ASK_PLANNER")
        }

        return ReflexCandidateSet(
            allowed = allowed,
            constitutionalAnchor = anchor,
            reason = anchorDecision.describeForReflex()
        )
    }

    /**
     * Validate and rank a reflex score vector. Unknown/out-of-bound options are
     * rejected instead of being silently normalized into authority.
     */
    fun rank(
        candidates: ReflexCandidateSet,
        scores: List<ReflexChoiceScore>,
        confidence: Double,
        evidenceCount: Int = 0
    ): ReflexChoice {
        require(scores.isNotEmpty())
        require(scores.all { it.option in candidates.allowed }) {
            "Reflex choice contains an option outside the constitutional candidate set"
        }
        require(scores.map { it.option }.distinct().size == scores.size) {
            "Reflex choice contains duplicate options"
        }

        return ReflexChoice(
            scores = scores,
            confidence = confidence,
            evidenceCount = evidenceCount
        )
    }

    fun shouldUseReflex(
        candidates: ReflexCandidateSet,
        confidence: Double,
        threshold: Double,
        evidenceCount: Int
    ): ReflexBool {
        require(confidence in 0.0..1.0)
        require(threshold in 0.0..1.0)
        require(evidenceCount >= 0)

        val onlyHardStop =
            candidates.allowed == setOf(ReflexOption.STOP)

        return ReflexBool(
            value = onlyHardStop || (confidence >= threshold && evidenceCount > 0),
            confidence = confidence,
            evidenceCount = evidenceCount
        )
    }

    private fun RecoveryDecision.toReflexOption(): ReflexOption = when (this) {
        is RecoveryDecision.RetryVariant -> ReflexOption.RETRY_VARIANT
        is RecoveryDecision.TryAlternative -> ReflexOption.TRY_ALTERNATIVE
        is RecoveryDecision.DegradePartial -> ReflexOption.DEGRADE_PARTIAL
        is RecoveryDecision.Stop -> ReflexOption.STOP
    }

    private fun RecoveryDecision.describeForReflex(): String = when (this) {
        is RecoveryDecision.RetryVariant -> guidance
        is RecoveryDecision.TryAlternative -> guidance
        is RecoveryDecision.DegradePartial -> reason
        is RecoveryDecision.Stop -> reason
    }.take(700)
}
