package com.lumena.android.ollama

import com.lumena.android.agent.local.PlannedTool
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.chat.ChatBackend

data class PendingWorkflowTool(
    val plan: PlannedTool,
    val history: List<OllamaMessage>
)

sealed interface WorkflowOutcome {
    data class Finished(val text: String, val history: List<OllamaMessage>) : WorkflowOutcome
    data class NeedsConfirmation(val pending: PendingWorkflowTool) : WorkflowOutcome
    data class Failed(val message: String) : WorkflowOutcome
}

class WorkflowRunner(
    private val backend: ChatBackend,
    private val bridge: TermuxBridgeClient?,
    private val onEvent: (String) -> Unit = {}
) {
    suspend fun run(history: List<OllamaMessage>, maxSteps: Int = 6): WorkflowOutcome {
        var current = history

        repeat(maxSteps) {
            val reply = backend.complete(current).getOrElse { error ->
                return WorkflowOutcome.Failed("${backend.label}: ${error.message ?: error}")
            }

            when (val parsed = LocalWorkflowAgent.parse(reply)) {
                is AgentReply.Message -> {
                    val next = current + OllamaMessage("assistant", parsed.text)
                    return WorkflowOutcome.Finished(parsed.text, next)
                }

                is AgentReply.Tool -> {
                    val plan = ToolGate.plan(parsed.decision)
                    if (!plan.allowed) {
                        return WorkflowOutcome.Failed("Blocked unknown tool: ${plan.request.tool}")
                    }
                    if (plan.requiresConfirmation) {
                        return WorkflowOutcome.NeedsConfirmation(
                            PendingWorkflowTool(
                                plan = plan,
                                history = current + OllamaMessage("assistant", parsed.raw)
                            )
                        )
                    }
                    val localBridge = bridge
                        ?: return WorkflowOutcome.Failed("Bridge token is required for ${plan.request.tool}")

                    onEvent("Running ${plan.request.tool}…")
                    val result = localBridge.execute(plan.request)
                    onEvent(
                        if (result.ok) "✓ ${plan.request.tool} completed"
                        else "✕ ${plan.request.tool} failed${result.error?.let { ": $it" } ?: ""}"
                    )
                    current = current +
                        OllamaMessage("assistant", parsed.raw) +
                        LocalWorkflowAgent.toolResultMessage(
                            tool = plan.request.tool,
                            ok = result.ok,
                            stdout = result.stdout,
                            stderr = result.stderr,
                            error = result.error
                        )
                }
            }
        }

        return WorkflowOutcome.Failed("Agent reached the local step limit. Split the task into a smaller goal.")
    }

    suspend fun approve(pending: PendingWorkflowTool): WorkflowOutcome {
        val localBridge = bridge
            ?: return WorkflowOutcome.Failed("Bridge token is required for ${pending.plan.request.tool}")
        onEvent("Running ${pending.plan.request.tool}…")
        val result = localBridge.execute(pending.plan.request)
        onEvent(
            if (result.ok) "✓ ${pending.plan.request.tool} completed"
            else "✕ ${pending.plan.request.tool} failed${result.error?.let { ": $it" } ?: ""}"
        )
        val next = pending.history + LocalWorkflowAgent.toolResultMessage(
            tool = pending.plan.request.tool,
            ok = result.ok,
            stdout = result.stdout,
            stderr = result.stderr,
            error = result.error
        )
        return run(next)
    }
}
