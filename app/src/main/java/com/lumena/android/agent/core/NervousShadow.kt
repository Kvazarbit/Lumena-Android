package com.lumena.android.agent.core

data class NervousShadowCandidate(
    val ruleId: String,
    val statement: String,
    val stage: String = "SHADOW",
    val active: Boolean = false,
    val verifiedEvidenceIds: List<String>,
    val distinctContexts: Int,
    val causalGrade: CausalGrade
)

/**
 * Converts verified nervous evidence into advisory SHADOW hypotheses only.
 *
 * This object intentionally has no dependency on ConstitutionGenomeStore and
 * no method that can activate a rule.
 */
object NervousShadowPolicy {
    fun candidate(
        events: List<NervousEvent>,
        incidentCode: String,
        ruleId: String,
        statement: String,
        minVerifiedEvidence: Int = 3,
        minDistinctContexts: Int = 2
    ): NervousShadowCandidate? {
        require(ruleId.isNotBlank())
        require(statement.isNotBlank())
        require(minVerifiedEvidence >= 1)
        require(minDistinctContexts >= 1)

        val verified = events
            .filter {
                it.incidentCode == incidentCode &&
                    it.promotionEligible() &&
                    it.causalGrade !in setOf(
                        CausalGrade.CONTESTED,
                        CausalGrade.SUPERSEDED
                    )
            }
            .distinctBy { it.id }

        val contexts = verified.mapNotNull { it.contextKey }.toSet()
        if (
            verified.size < minVerifiedEvidence ||
            contexts.size < minDistinctContexts
        ) {
            return null
        }

        // Diversity of contexts is a precondition for a transfer experiment,
        // not proof of transfer. TRANSFERRED requires an explicit test in a
        // new context against a baseline, which this counter cannot provide.
        val grade = CausalGrade.VERIFIED_EFFECT

        return NervousShadowCandidate(
            ruleId = ruleId,
            statement = statement,
            verifiedEvidenceIds = verified.map { it.id },
            distinctContexts = contexts.size,
            causalGrade = grade
        )
    }
}
