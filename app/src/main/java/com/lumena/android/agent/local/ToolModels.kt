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

data class PlannedTool(
    val request: ToolRequest,
    val reason: String,
    val allowed: Boolean,
    val requiresConfirmation: Boolean
)
