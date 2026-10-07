package com.lumena.android.agent.local

data class ToolRequest(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val requestId: String? = null
)

data class ToolResult(
    val ok: Boolean,
    val tool: String? = null,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
    val outcomeUnknown: Boolean = false,
    val errorCode: String? = null,
    val failureClass: String? = null,
    val retryable: Boolean? = null,
    val dependency: String? = null
)

interface ToolExecutor {
    suspend fun execute(toolRequest: ToolRequest): ToolResult
}

data class PlannerDecision(
    val request: ToolRequest,
    val reason: String
)

/**
 * Pre-execution causal probe metadata.
 *
 * Only a hash of model-proposed hypothesis prose is carried past the model
 * turn. It is never permission and never evidence by itself.
 */
data class CauseProbeExecutionIntent(
    val hypothesisHash: String,
    val onSuccess: String? = null,
    val onFailure: String? = null
)

/** Model's pre-execution outcome prediction, carried to the result. */
data class OutcomePrediction(
    val expectOk: Boolean,
    val confidence: Double
)

data class PlannedTool(
    val request: ToolRequest,
    val reason: String,
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val causeProbeIntent: CauseProbeExecutionIntent? = null,
    val prediction: OutcomePrediction? = null
)
