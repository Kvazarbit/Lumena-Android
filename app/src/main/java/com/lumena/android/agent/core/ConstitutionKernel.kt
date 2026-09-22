package com.lumena.android.agent.core

/**
 * Bounded recovery state supplied by the controller.
 *
 * The same pure policy is used for protocol, model/runtime and tool failures.
 * It does not execute tools and cannot grant permissions.
 */
data class RecoveryState(
    val familyFailures: Int,
    val semanticRecoverySpent: Int,
    val maxFamilyFailures: Int,
    val maxSemanticRecoveries: Int
) {
    init {
        require(familyFailures >= 0)
        require(semanticRecoverySpent >= 0)
        require(maxFamilyFailures >= 0)
        require(maxSemanticRecoveries >= 0)
    }
}

/**
 * Executable constitutional recovery graph.
 *
 * Authority remains outside this object:
 * - ToolRegistry / ToolGate decide what may execute.
 * - this kernel only chooses a bounded recovery disposition after observed failure.
 */
object ConstitutionKernel {
    fun decide(
        event: FailureEvent,
        state: RecoveryState
    ): RecoveryDecision {
        if (event.outcomeUnknown || event.failureClass == FailureClass.UNKNOWN_EFFECT) {
            return RecoveryDecision.Stop(
                "Tool outcome is unknown. Do not replay a possible mutation; inspect the checkpoint/current state first."
            )
        }

        if (event.failureClass == FailureClass.POLICY_DENIED || event.source == FailureSource.POLICY) {
            return RecoveryDecision.Stop("Policy denied the action.")
        }

        if (event.source == FailureSource.PROTOCOL) {
            return if (event.attempt > state.maxFamilyFailures) {
                RecoveryDecision.Stop(
                    "Model protocol recovery budget exhausted after ${event.attempt} invalid protocol outputs."
                )
            } else {
                RecoveryDecision.TryAlternative(
                    "Correct the protocol locally when deterministic normalization is possible; otherwise ask the model for exactly one valid protocol object."
                )
            }
        }

        val modelOrigin =
            event.dependency == "model" &&
                event.source in setOf(
                    FailureSource.MODEL_RUNTIME,
                    FailureSource.CONTEXT,
                    FailureSource.RESOURCE,
                    FailureSource.TRANSPORT
                )

        if (modelOrigin) {
            if (event.retryable == false) {
                return RecoveryDecision.Stop(
                    "Model/runtime failure is classified as non-retryable at the controller layer."
                )
            }
            return if (event.attempt > state.maxFamilyFailures) {
                RecoveryDecision.Stop(
                    "Model retry budget exhausted after ${event.attempt} failed model calls."
                )
            } else {
                RecoveryDecision.TryAlternative(
                    "Retry the same task from verified state; do not invent results or reset task evidence."
                )
            }
        }

        if (event.failureClass == FailureClass.AUTH_OR_CONFIG) {
            return RecoveryDecision.DegradePartial(
                "Required local/API configuration is unavailable; no blind retry is allowed."
            )
        }

        if (state.semanticRecoverySpent >= state.maxSemanticRecoveries) {
            return RecoveryDecision.DegradePartial(
                "Semantic recovery budget exhausted without verified progress."
            )
        }

        if (state.familyFailures >= state.maxFamilyFailures) {
            return RecoveryDecision.DegradePartial(
                "The same action family failed repeatedly; a rephrased retry must not reset the failure history."
            )
        }

        return when (event.failureClass) {
            FailureClass.DEPENDENCY_EXHAUSTED,
            FailureClass.PROVIDER_CHALLENGE ->
                RecoveryDecision.RetryVariant(
                    "The provider path failed. One meaningfully different query/route is allowed; otherwise use another evidence source or report partial."
                )

            FailureClass.STATE_DRIFT ->
                RecoveryDecision.TryAlternative(
                    "Re-discover current state/path/root before retrying the failed operation."
                )

            FailureClass.TRANSIENT_TRANSPORT,
            FailureClass.TIMEOUT,
            FailureClass.RATE_LIMIT ->
                RecoveryDecision.TryAlternative(
                    "The dependency layer owns mechanical retries. Use current evidence to choose a safe alternate route or report partial."
                )

            FailureClass.INVALID_INPUT ->
                RecoveryDecision.TryAlternative(
                    "Correct the input from verified state; do not repeat the unchanged action."
                )

            FailureClass.CONTEXT_PRESSURE ->
                RecoveryDecision.TryAlternative(
                    "Reduce context pressure without dropping required constitutional or verification state."
                )

            FailureClass.RESOURCE_PRESSURE ->
                RecoveryDecision.TryAlternative(
                    "Reduce resource pressure or choose a lower-resource verified route; do not claim completion."
                )

            FailureClass.MODEL_RUNTIME ->
                RecoveryDecision.TryAlternative(
                    "Use a verified compatible model/runtime path or report partial."
                )

            FailureClass.AUTH_OR_CONFIG,
            FailureClass.POLICY_DENIED,
            FailureClass.UNKNOWN_EFFECT ->
                error("Handled before class dispatch")

            FailureClass.OTHER ->
                RecoveryDecision.TryAlternative(
                    "Use a meaningfully different evidence-producing action; do not repeat the unchanged failure."
                )
        }
    }
}
