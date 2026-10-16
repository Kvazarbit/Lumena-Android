package com.lumena.android.agent.local

import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.EffectiveTaskPolicy
import com.lumena.android.agent.core.EffectiveTaskPolicyCompiler
import com.lumena.android.agent.core.TaskTaint
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.TrustGate

object ToolGate {
    fun approvalKey(request: ToolRequest): String = buildString {
        append(ToolRegistry.canonicalize(request.tool))
        request.args.toSortedMap().forEach { (key, value) ->
            append('|').append(key).append('=').append(value.trim())
        }
    }

    fun plan(
        decision: PlannerDecision,
        externalSource: Boolean = false,
        taskPolicy: EffectiveTaskPolicy? = null,
        taint: TaskTaint = TaskTaint(),
        ownerText: String = ""
    ): PlannedTool {
        val call = AgentDecision.ToolCall(
            tool = decision.request.tool,
            args = decision.request.args,
            reason = decision.reason
        )
        val validation =
            ToolRegistry.validate(
                call,
                externalSource
            )
        val policyDecision =
            taskPolicy?.let {
                EffectiveTaskPolicyCompiler
                    .validateTool(
                        it,
                        call
                    )
            }
        val canonical =
            validation.canonicalTool
                ?: decision.request.tool
        val policyAllowed =
            policyDecision?.allowed != false
        // Trust Gate: after untrusted text entered the task, network egress
        // outside what this task saw or the owner typed waits for the owner.
        val trustReview =
            if (validation.allowed && policyAllowed) {
                TrustGate.review(
                    taint = taint,
                    tool = canonical,
                    args = decision.request.args,
                    ownerText = ownerText
                )
            } else {
                null
            }

        return PlannedTool(
            request = ToolRequest(
                tool = canonical,
                args = decision.request.args,
                requestId = decision.request.requestId
            ),
            reason =
                policyDecision?.reason
                    ?: validation.error
                    ?: decision.reason,
            allowed =
                validation.allowed &&
                    policyAllowed,
            requiresConfirmation =
                validation.requiresConfirmation ||
                    trustReview != null,
            trustReview = trustReview?.render()
        )
    }
}
