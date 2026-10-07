package com.lumena.android.settings

import com.lumena.android.agent.core.ToolRisk
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.local.ToolResult

/**
 * Phase-1 cognitive ledger: what Lumena expected from an intentional tool call,
 * what was actually observed, and the bounded structural delta between them.
 *
 * These records are descriptive/advisory only. They never grant permission,
 * never prove the whole user goal, and never turn model prose into evidence.
 */
enum class ExpectationSource {
    DETERMINISTIC_TOOL_CONTRACT,
    MODEL_PROPOSAL,
    IMPORTED_STRATEGY
}

enum class ExpectedOutcomeStatus {
    SUCCESS
}

enum class ObservedOutcomeStatus {
    SUCCESS,
    FAILURE,
    UNKNOWN
}

enum class VerificationStatus {
    TOOL_RESULT_ONLY,
    OUTCOME_UNKNOWN,
    INDEPENDENT_VERIFICATION_MISSING,
    VERIFIED_POSTCONDITION
}

enum class OutcomeDeltaKind {
    MATCH,
    UNEXPECTED_FAILURE,
    UNEXPECTED_SUCCESS,
    WRONG_TARGET_STATE,
    PARTIAL_EFFECT,
    DEPENDENCY_CHANGED,
    SCHEMA_MISMATCH,
    OUTCOME_UNKNOWN,
    VERIFICATION_MISSING
}

data class ExpectedOutcome(
    val source: ExpectationSource = ExpectationSource.DETERMINISTIC_TOOL_CONTRACT,
    val tool: String,
    val target: String = "",
    val expectedStatus: ExpectedOutcomeStatus = ExpectedOutcomeStatus.SUCCESS,
    val expectedEffectClass: String,
    val expectedPostcondition: String,
    val confidence: Double? = null,
    val acceptanceCriterionId: String? = null
)

data class ObservedOutcome(
    val tool: String,
    val target: String = "",
    val status: ObservedOutcomeStatus,
    val verificationStatus: VerificationStatus = VerificationStatus.TOOL_RESULT_ONLY,
    val failureClass: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean? = null,
    val dependency: String? = null
)

data class OutcomeDelta(
    val kind: OutcomeDeltaKind,
    val expectedStatus: ExpectedOutcomeStatus,
    val observedStatus: ObservedOutcomeStatus,
    val reasonCode: String
)

data class OutcomeDeltaStats(
    val expectations: Int = 0,
    val deltas: Int = 0,
    val matches: Int = 0,
    val unexpectedFailures: Int = 0,
    val unexpectedSuccesses: Int = 0,
    val schemaMismatches: Int = 0,
    val outcomeUnknown: Int = 0,
    val verificationMissing: Int = 0
)

object ExperienceOutcomeDeltaPolicy {
    fun expected(
        tool: String,
        target: String
    ): ExpectedOutcome? {
        val canonical = ToolRegistry.canonicalize(tool)
        val spec = ToolRegistry.get(canonical) ?: return null
        val effect = when (spec.risk) {
            ToolRisk.READ_ONLY -> "READ_ONLY_OBSERVATION"
            ToolRisk.MUTATING -> "MUTATING_EFFECT"
            ToolRisk.EXECUTABLE -> "EXECUTABLE_EFFECT"
        }
        return ExpectedOutcome(
            tool = canonical,
            target = sanitize(target, 220),
            expectedEffectClass = effect,
            expectedPostcondition = "KNOWN_SUCCESSFUL_TOOL_OUTCOME"
        )
    }

    fun observed(
        tool: String,
        target: String,
        result: ToolResult,
        verifiedOk: Boolean
    ): ObservedOutcome {
        val status = when {
            result.outcomeUnknown -> ObservedOutcomeStatus.UNKNOWN
            verifiedOk -> ObservedOutcomeStatus.SUCCESS
            else -> ObservedOutcomeStatus.FAILURE
        }
        return ObservedOutcome(
            tool = ToolRegistry.canonicalize(tool),
            target = sanitize(target, 220),
            status = status,
            verificationStatus =
                if (status == ObservedOutcomeStatus.UNKNOWN) {
                    VerificationStatus.OUTCOME_UNKNOWN
                } else {
                    VerificationStatus.TOOL_RESULT_ONLY
                },
            failureClass = sanitizeNullable(result.failureClass, 120),
            errorCode = sanitizeNullable(result.errorCode, 120),
            retryable = result.retryable,
            dependency = sanitizeNullable(result.dependency, 160)
        )
    }

    fun compare(
        expected: ExpectedOutcome,
        observed: ObservedOutcome
    ): OutcomeDelta {
        val kind = when {
            expected.tool != observed.tool ||
                expected.target != observed.target ->
                OutcomeDeltaKind.WRONG_TARGET_STATE

            observed.status == ObservedOutcomeStatus.UNKNOWN ->
                OutcomeDeltaKind.OUTCOME_UNKNOWN

            observed.verificationStatus ==
                VerificationStatus.INDEPENDENT_VERIFICATION_MISSING ->
                OutcomeDeltaKind.VERIFICATION_MISSING

            observed.status == ObservedOutcomeStatus.SUCCESS ->
                if (expected.expectedStatus == ExpectedOutcomeStatus.SUCCESS) {
                    OutcomeDeltaKind.MATCH
                } else {
                    OutcomeDeltaKind.UNEXPECTED_SUCCESS
                }

            else -> when {
                observed.failureClass == "INVALID_INPUT" ||
                    observed.errorCode?.contains("SCHEMA", ignoreCase = true) == true ||
                    observed.errorCode?.contains("REQUIRED", ignoreCase = true) == true ->
                    OutcomeDeltaKind.SCHEMA_MISMATCH

                observed.failureClass == "DEPENDENCY_CHANGED" ->
                    OutcomeDeltaKind.DEPENDENCY_CHANGED

                else ->
                    OutcomeDeltaKind.UNEXPECTED_FAILURE
            }
        }

        return OutcomeDelta(
            kind = kind,
            expectedStatus = expected.expectedStatus,
            observedStatus = observed.status,
            reasonCode = when (kind) {
                OutcomeDeltaKind.MATCH -> "EXPECTED_SUCCESS_OBSERVED"
                OutcomeDeltaKind.UNEXPECTED_FAILURE -> "EXPECTED_SUCCESS_BUT_TOOL_FAILED"
                OutcomeDeltaKind.UNEXPECTED_SUCCESS -> "EXPECTED_NON_SUCCESS_BUT_TOOL_SUCCEEDED"
                OutcomeDeltaKind.SCHEMA_MISMATCH -> "VERIFIED_INPUT_OR_SCHEMA_FAILURE"
                OutcomeDeltaKind.DEPENDENCY_CHANGED -> "VERIFIED_DEPENDENCY_CHANGE"
                OutcomeDeltaKind.OUTCOME_UNKNOWN -> "TOOL_EFFECT_UNKNOWN"
                OutcomeDeltaKind.WRONG_TARGET_STATE -> "TARGET_POSTCONDITION_MISMATCH"
                OutcomeDeltaKind.PARTIAL_EFFECT -> "ONLY_PART_OF_POSTCONDITION_OBSERVED"
                OutcomeDeltaKind.VERIFICATION_MISSING -> "POSTCONDITION_NOT_VERIFIED"
            }
        )
    }

    fun stats(
        state: CoordinatorEpisodeState
    ): OutcomeDeltaStats {
        val events = state.events
        return OutcomeDeltaStats(
            expectations = events.count { it.expectedOutcome != null },
            deltas = events.count { it.outcomeDelta != null },
            matches = events.count {
                it.outcomeDelta?.kind == OutcomeDeltaKind.MATCH
            },
            unexpectedFailures = events.count {
                it.outcomeDelta?.kind == OutcomeDeltaKind.UNEXPECTED_FAILURE
            },
            unexpectedSuccesses = events.count {
                it.outcomeDelta?.kind == OutcomeDeltaKind.UNEXPECTED_SUCCESS
            },
            schemaMismatches = events.count {
                it.outcomeDelta?.kind == OutcomeDeltaKind.SCHEMA_MISMATCH
            },
            outcomeUnknown = events.count {
                it.outcomeDelta?.kind == OutcomeDeltaKind.OUTCOME_UNKNOWN
            },
            verificationMissing = events.count {
                it.outcomeDelta?.kind == OutcomeDeltaKind.VERIFICATION_MISSING
            }
        )
    }

    private fun sanitizeNullable(
        value: String?,
        maxChars: Int
    ): String? =
        value
            ?.let { sanitize(it, maxChars) }
            ?.takeIf(String::isNotBlank)

    private fun sanitize(
        value: String,
        maxChars: Int
    ): String =
        value
            .replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(maxChars)
}
