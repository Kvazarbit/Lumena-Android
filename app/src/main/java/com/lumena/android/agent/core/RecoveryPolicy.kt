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
        val event = FailureEvent(
            source = FailureSource.TOOL,
            failureClass = ctx.failureClass,
            retryable = null,
            effectClass = ctx.effectClass,
            dependency = ctx.actionFamily,
            evidence = "legacy RecoveryContext adapter",
            actionFamily = ctx.actionFamily,
            attempt = maxOf(1, ctx.familyFailures),
            outcomeUnknown = ctx.failureClass == FailureClass.UNKNOWN_EFFECT
        )
        return ConstitutionKernel.decide(
            event = event,
            state = RecoveryState(
                familyFailures = ctx.familyFailures,
                semanticRecoverySpent = ctx.semanticRecoverySpent,
                maxFamilyFailures = ctx.maxFamilyFailures,
                maxSemanticRecoveries = ctx.maxSemanticRecoveries
            )
        )
    }
}
