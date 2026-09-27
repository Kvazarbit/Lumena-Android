package com.lumena.android.settings

import com.lumena.android.agent.core.FailureEvent
import com.lumena.android.agent.core.RecoveryState
import com.lumena.android.agent.core.ReflexCandidateSet
import com.lumena.android.agent.core.ReflexChoice
import com.lumena.android.agent.core.ReflexChoiceScore
import com.lumena.android.agent.core.ReflexKernel
import com.lumena.android.agent.core.ReflexOption
import com.lumena.android.agent.core.ReflexScore
import com.lumena.android.agent.core.ToolRegistry

data class ReflexExperienceRecommendation(
    val choice: ReflexChoice?,
    val evidence: ReflexScore,
    val matchedExampleIds: List<String>,
    val calibrated: Boolean = false
)

/**
 * Deterministic adapter from verified local coordinator examples to ReflexKernel
 * scores.
 *
 * This object has no tool executor and no permission API. It can only score
 * options already admitted by ReflexKernel.candidates(), whose authority anchor
 * comes from ConstitutionKernel.
 *
 * The current score is an evidence-strength heuristic, NOT a calibrated
 * probability. A future learned/calibrated model may replace this scorer without
 * changing the authority boundary.
 */
object ReflexExperienceRanker {
    const val TARGET_SUPPORT = 8
    const val MAX_MATCHED_IDS = 32

    fun rank(
        event: FailureEvent,
        state: RecoveryState,
        examples: List<CoordinatorExecutionExample>
    ): ReflexExperienceRecommendation =
        rank(
            event = event,
            candidates = ReflexKernel.candidates(event, state),
            examples = examples
        )

    fun rank(
        event: FailureEvent,
        candidates: ReflexCandidateSet,
        examples: List<CoordinatorExecutionExample>
    ): ReflexExperienceRecommendation {
        if (candidates.allowed == setOf(ReflexOption.STOP)) {
            return ReflexExperienceRecommendation(
                choice = ReflexKernel.rank(
                    candidates = candidates,
                    scores = listOf(
                        ReflexChoiceScore(ReflexOption.STOP, 1.0)
                    ),
                    confidence = 1.0,
                    evidenceCount = 0
                ),
                evidence = ReflexScore(
                    value = 1.0,
                    confidence = 1.0,
                    evidenceCount = 0
                ),
                matchedExampleIds = emptyList(),
                calibrated = false
            )
        }

        val family = event.actionFamily
            ?.takeIf { it.isNotBlank() }
            ?.let(ToolRegistry::canonicalize)
            ?: return noEvidence()

        val matched = examples
            .asSequence()
            .filter { it.kind in setOf(CoordinatorExampleKind.RECOVERY, CoordinatorExampleKind.FAILED_RECOVERY) }
            .filter { it.evidenceIds.isNotEmpty() }
            .filter { example ->
                val first = example.tools.firstOrNull()
                    ?.let(ToolRegistry::canonicalize)
                val last = example.tools.lastOrNull()
                    ?.let(ToolRegistry::canonicalize)
                first == family && last == family
            }
            .distinctBy { it.id }
            .toList()

        if (matched.isEmpty()) return noEvidence()

        // One task supplies at most one vote per option. Any failed attempt
        // in that task is retained, even if a later retry happened to work.
        val votes = matched.groupBy { example ->
            example.sourceSessionHash to if (example.tools.size <= 2) {
                ReflexOption.RETRY_VARIANT
            } else {
                ReflexOption.TRY_ALTERNATIVE
            }
        }
        val support = linkedMapOf<ReflexOption, Int>()
        val failures = linkedMapOf<ReflexOption, Int>()
        votes.forEach { (key, episodes) ->
            val option = key.second
            if (option in candidates.allowed) {
                val counter = if (episodes.any { it.kind == CoordinatorExampleKind.FAILED_RECOVERY }) failures else support
                counter[option] = (counter[option] ?: 0) + 1
            }
        }

        if (support.isEmpty()) {
            return ReflexExperienceRecommendation(
                choice = null,
                evidence = ReflexScore(
                    value = 0.0,
                    confidence = 0.0,
                    evidenceCount = votes.size
                ),
                matchedExampleIds = matched
                    .map { it.id }
                    .take(MAX_MATCHED_IDS),
                calibrated = false
            )
        }

        val usable = support.values.sum()
        val attempted = usable + failures.values.sum()
        val reliability = support.mapValues { (option, success) ->
            success.toDouble() / (success + (failures[option] ?: 0)).toDouble()
        }
        val best = support.values.maxOrNull() ?: 0
        val agreement = if (usable == 0) {
            0.0
        } else {
            best.toDouble() / usable.toDouble()
        }
        val saturation =
            (usable.toDouble() / TARGET_SUPPORT.toDouble())
                .coerceIn(0.0, 1.0)
        val strength = (agreement * saturation * usable.toDouble() / attempted.toDouble())
            .coerceIn(0.0, 1.0)

        val choice = ReflexKernel.rank(
            candidates = candidates,
            scores = support.map { (option, count) ->
                ReflexChoiceScore(
                    option = option,
                    score = (count.toDouble() / usable.toDouble()) * reliability.getValue(option)
                )
            },
            confidence = strength,
            evidenceCount = usable
        )

        return ReflexExperienceRecommendation(
            choice = choice,
            evidence = ReflexScore(
                value = strength,
                confidence = agreement,
                evidenceCount = votes.size
            ),
            matchedExampleIds = matched
                .map { it.id }
                .take(MAX_MATCHED_IDS),
            calibrated = false
        )
    }

    private fun noEvidence(): ReflexExperienceRecommendation =
        ReflexExperienceRecommendation(
            choice = null,
            evidence = ReflexScore(
                value = 0.0,
                confidence = 0.0,
                evidenceCount = 0
            ),
            matchedExampleIds = emptyList(),
            calibrated = false
        )
}
