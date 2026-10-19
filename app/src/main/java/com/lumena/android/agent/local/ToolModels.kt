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
    val dependency: String? = null,
    val sourceSha256: String? = null,
    val sourceComplete: Boolean = false,
    val verification: VerificationEvidence? = null
)

/** Bridge-authored bounded test evidence, never parsed from model/program prose. */
data class VerificationEvidence(
    val specHash: String, val boundaryHash: String, val selectedTestsHash: String,
    val testsCount: Int, val complete: Boolean, val boundaryUnchanged: Boolean,
    val failureSignature: String? = null,
    val schema: String = "lumena.verification.v1", val failureSummary: String? = null,
    val testFiles: List<String> = emptyList(),
    /** First failing JUnit case `file::class::name`; retrieval key for repair cases, never a proof by itself. */
    val failedTestId: String? = null
) {
    fun valid(): Boolean = schema == "lumena.verification.v1" && testsCount in 1..100_000 &&
        listOf(specHash, boundaryHash, selectedTestsHash).all { it.matches(Regex("[a-f0-9]{64}")) } &&
        (failureSignature == null || failureSignature.matches(Regex("[a-f0-9]{64}"))) && (failureSummary?.length ?: 0) <= 240 &&
        (failedTestId == null || (failedTestId.length in 1..300 && failedTestId.none { it.isISOControl() })) &&
        testFiles.size <= 16 && testFiles.distinct().size == testFiles.size && testFiles.all {
            it.length in 1..200 && !it.startsWith('/') && '\\' !in it && ':' !in it && it.split('/').none { p -> p == ".." } }
}

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
    val prediction: OutcomePrediction? = null,
    /** Trust Gate explanation shown in the confirmation dialog, if any. */
    val trustReview: String? = null
)
