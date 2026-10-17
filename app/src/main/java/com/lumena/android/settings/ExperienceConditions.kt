package com.lumena.android.settings

/** Structured context, never a model-written explanation or a goal-success label. */
data class ExperienceConditions(
    val problemClass: String = "UNKNOWN",
    val requiredPostconditions: List<String> = emptyList(),
    val environmentFingerprint: String? = null
) {
    fun validate() {
        require(problemClass == "UNKNOWN" ||
            com.lumena.android.agent.core.TaskIntent.values().any { it.name == problemClass })
        require(requiredPostconditions.size <= 32 && requiredPostconditions.all { name ->
            com.lumena.android.agent.core.CriterionKind.values().any { it.name == name }
        })
        require(environmentFingerprint == null || environmentFingerprint.matches(Regex("[a-f0-9]{64}")))
    }
}

/** Preserve original hashes in receipts; normalize only comparisons/projections. */
object ExperienceScope {
    private val legacyGlobal = CoordinatorExperiencePolicy.hash("global")
    fun normalized(hash: String?): String? = hash?.takeIf {
        it.isNotBlank() && it != legacyGlobal
    }
    fun visible(recordScope: String?, queryScope: String?): Boolean =
        normalized(recordScope) == null || normalized(recordScope) == normalized(queryScope)
}
