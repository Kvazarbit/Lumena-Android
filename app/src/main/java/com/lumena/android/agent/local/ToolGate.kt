package com.lumena.android.agent.local

import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.ToolRegistry

object ToolGate {
    fun approvalKey(request: ToolRequest): String = buildString {
        append(ToolRegistry.canonicalize(request.tool))
        request.args.toSortedMap().forEach { (key, value) ->
            append('|').append(key).append('=').append(value.trim())
        }
    }

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
            request = ToolRequest(
                tool = canonical,
                args = decision.request.args,
                requestId = decision.request.requestId
            ),
            reason = validation.error ?: decision.reason,
            allowed = validation.allowed,
            requiresConfirmation = validation.requiresConfirmation
        )
    }
}
