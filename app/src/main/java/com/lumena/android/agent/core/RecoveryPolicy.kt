package com.lumena.android.agent.core

/**
 * Executable semantic-recovery policy.
 *
 * Mechanical retries stay at the dependency layer (bridge / model client).
 * This policy decides only what the agent may do after an observed failure.
 * It is intentionally pure so state-machine/property tests can exercise it.
 */
enum class FailureClass {
    TRANSIENT_TRANSPORT,
    TIMEOUT,
    RATE_LIMIT,
    PROVIDER_CHALLENGE,
    AUTH_OR_CONFIG,
    INVALID_INPUT,
    STATE_DRIFT,
    CONTEXT_PRESSURE,
    RESOURCE_PRESSURE,
    MODEL_RUNTIME,
    POLICY_DENIED,
    UNKNOWN_EFFECT,
    DEPENDENCY_EXHAUSTED,
    OTHER
}

enum class EffectClass {
    NONE,
    READ_ONLY,
    MUTATING_OR_EXECUTABLE
}

sealed interface RecoveryDecision {
    data class RetryVariant(val guidance: String) : RecoveryDecision
    data class TryAlternative(val guidance: String) : RecoveryDecision
    data class DegradePartial(val reason: String) : RecoveryDecision
    data class Stop(val reason: String) : RecoveryDecision
}

data class RecoveryContext(
    val failureClass: FailureClass,
    val effectClass: EffectClass,
    val actionFamily: String,
    val familyFailures: Int,
    val semanticRecoverySpent: Int,
    val maxFamilyFailures: Int,
    val maxSemanticRecoveries: Int
)

object RecoveryPolicy {
    fun actionFamily(call: AgentDecision.ToolCall): String =
        ToolRegistry.canonicalize(call.tool)

    fun effectClass(tool: String): EffectClass =
        if (ToolRegistry.get(tool)?.risk == ToolRisk.READ_ONLY) {
            EffectClass.READ_ONLY
        } else {
            EffectClass.MUTATING_OR_EXECUTABLE
        }

    fun classifyToolFailure(
        tool: String,
        errorCode: String? = null,
        suppliedClass: String? = null,
        error: String? = null,
        stderr: String = "",
        stdout: String = "",
        outcomeUnknown: Boolean = false
    ): FailureClass = FailureClassifier.tool(
        tool = tool,
        errorCode = errorCode,
        suppliedClass = suppliedClass,
        error = error,
        stderr = stderr,
        stdout = stdout,
        outcomeUnknown = outcomeUnknown
    )

    fun decide(ctx: RecoveryContext): RecoveryDecision {
        if (ctx.failureClass == FailureClass.UNKNOWN_EFFECT) {
            return RecoveryDecision.Stop(
                "Tool outcome is unknown. Do not replay a possible mutation; inspect the checkpoint/current state first."
            )
        }

        if (ctx.failureClass == FailureClass.POLICY_DENIED) {
            return RecoveryDecision.Stop("Policy denied the action.")
        }

        if (ctx.failureClass == FailureClass.AUTH_OR_CONFIG) {
            return RecoveryDecision.DegradePartial(
                "Required local/API configuration is unavailable; no blind retry is allowed."
            )
        }

        if (ctx.semanticRecoverySpent >= ctx.maxSemanticRecoveries) {
            return RecoveryDecision.DegradePartial(
                "Semantic recovery budget exhausted without verified progress."
            )
        }

        if (ctx.familyFailures >= ctx.maxFamilyFailures) {
            return RecoveryDecision.DegradePartial(
                "The same action family failed repeatedly; a rephrased retry must not reset the failure history."
            )
        }

        return when (ctx.failureClass) {
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
                    "The local dependency already owns mechanical retries. Use current evidence to choose a safe alternate route or report partial."
                )

            FailureClass.INVALID_INPUT ->
                RecoveryDecision.TryAlternative(
                    "Correct the input from verified state; do not repeat the unchanged action."
                )

            else ->
                RecoveryDecision.TryAlternative(
                    "Use a meaningfully different evidence-producing action; do not repeat the unchanged failure."
                )
        }
    }
}
