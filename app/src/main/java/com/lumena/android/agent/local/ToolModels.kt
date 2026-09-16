package com.lumena.android.agent.local

data class ToolRequest(
    val tool: String,
    val args: Map<String, String> = emptyMap()
)

data class ToolResult(
    val ok: Boolean,
    val tool: String? = null,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null
)

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
