package com.lumena.android.agent.local

import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.ToolRegistry

object ToolGate {
    fun plan(
        decision: PlannerDecision,
        externalSource: Boolean = false
    ): PlannedTool {
        val call = AgentDecision.ToolCall(
            tool = decision.request.tool,
            args = decision.request.args,
            reason = decision.reason
        )
        val validation = ToolRegistry.validate(call, externalSource)
        val canonical = validation.canonicalTool ?: decision.request.tool

        return PlannedTool(
            request = ToolRequest(canonical, decision.request.args),
            reason = validation.error ?: decision.reason,
            allowed = validation.allowed,
            requiresConfirmation = validation.requiresConfirmation
        )
    }
}
