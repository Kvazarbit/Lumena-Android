package com.lumena.android.settings

import com.lumena.android.agent.core.ToolRegistry

/** A data-only replay corpus distilled from real, fully evidenced attempts.
 * No raw output, model prose, paths or executable code is included.
 * This checks memory/control regressions, not general model intelligence.
 */
data class CognitiveRegressionCase(
    val id: String,
    val sourceTaskHash: String,
    val tools: List<String>,
    val targetKeys: List<String>,
    val outcomes: List<Boolean>,
    val expectedKind: CoordinatorExampleKind,
    val evidenceIds: List<String>
)

data class CognitiveRegressionReport(
    val cases: Int,
    val passed: Int,
    val failedCaseIds: List<String>,
    val distinctTasks: Int,
    val families: List<String>
)

object CognitiveRegressionSuite {
    const val MAX_CASES = 32

    fun corpus(examples: List<CoordinatorExecutionExample>): List<CognitiveRegressionCase> = examples
        .filter {
            it.kind == CoordinatorExampleKind.FAILED_RECOVERY &&
                it.tools.size in 2..6 && it.tools.all { tool -> ToolRegistry.get(tool) != null } &&
                it.targets.size == it.tools.size && it.outcomes.size == it.tools.size &&
                !it.outcomes.first() && !it.outcomes.last() &&
                it.tools.first() == it.tools.last() && it.targets.first() == it.targets.last() &&
                it.evidenceIds.size == it.tools.size && it.sourceSessionHash.isNotBlank()
        }
        .sortedByDescending { it.updatedAt }
        .distinctBy { Triple(it.sourceSessionHash, it.tools, it.outcomes) }
        .take(MAX_CASES)
        .map {
            CognitiveRegressionCase(
                id = CoordinatorExperiencePolicy.hash("regression-v1|${it.id}").take(24),
                sourceTaskHash = it.sourceSessionHash,
                tools = it.tools,
                targetKeys = it.targets.map { target -> CoordinatorExperiencePolicy.hash(target).take(24) },
                outcomes = it.outcomes,
                expectedKind = it.kind,
                evidenceIds = it.evidenceIds
            )
        }

    /** A whole source task belongs to one split, regardless of example count. */
    fun holdout(sourceTaskHash: String): Boolean =
        CoordinatorExperiencePolicy.hash("cognitive-holdout-v1|$sourceTaskHash")
            .take(8).toLong(16) % 5 == 0L

    fun replay(cases: List<CognitiveRegressionCase>): CognitiveRegressionReport {
        val bounded = cases.distinctBy { it.id }.take(MAX_CASES)
        val failed = bounded.filterNot { case ->
            runCatching {
                require(case.tools.size in 2..6)
                require(case.targetKeys.size == case.tools.size && case.outcomes.size == case.tools.size)
                require(case.evidenceIds.size == case.tools.size)
                val state = case.tools.indices.fold(CoordinatorEpisodeState()) { state, index ->
                    CoordinatorExperiencePolicy.record(state, CoordinatorEpisodeEvent(
                        id = "${case.id}:$index", sessionId = case.sourceTaskHash,
                        taskId = case.sourceTaskHash, tool = case.tools[index],
                        target = case.targetKeys[index], ok = case.outcomes[index],
                        experienceId = case.evidenceIds[index], at = index + 1L, surprise = 0.5
                    ))
                }
                // Prove that compaction preserves the negative trace.
                CoordinatorExperiencePolicy.examples(state.copy(events = emptyList()), "", 64).any {
                    it.kind == case.expectedKind && it.tools == case.tools && it.outcomes == case.outcomes
                }
            }.getOrDefault(false)
        }
        return CognitiveRegressionReport(
            cases = bounded.size, passed = bounded.size - failed.size,
            failedCaseIds = failed.map { it.id },
            distinctTasks = bounded.map { it.sourceTaskHash }.distinct().size,
            families = bounded.mapNotNull { it.tools.firstOrNull() }.distinct().sorted()
        )
    }
}
