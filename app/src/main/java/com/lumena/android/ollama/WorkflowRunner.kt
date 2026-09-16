package com.lumena.android.ollama

import com.lumena.android.agent.local.PlannedTool
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate

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
    private val ollama: OllamaClient,
    private val bridge: TermuxBridgeClient?,
    private val model: String
) {
    suspend fun run(history: List<OllamaMessage>, maxSteps: Int = 4): WorkflowOutcome {
        var current = history

        repeat(maxSteps) {
            val reply = ollama.chat(model, current).getOrElse { error ->
                return WorkflowOutcome.Failed(error.message ?: error.toString())
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
                    val result = localBridge.execute(plan.request)
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

        return WorkflowOutcome.Failed("Agent reached the local step limit. Try a smaller task.")
    }

    suspend fun approve(pending: PendingWorkflowTool): WorkflowOutcome {
        val localBridge = bridge
            ?: return WorkflowOutcome.Failed("Bridge token is required for ${pending.plan.request.tool}")
        val result = localBridge.execute(pending.plan.request)
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
