package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.FailureBudget
import com.lumena.android.agent.core.FailureTracker
import com.lumena.android.agent.core.LoopDetector
import com.lumena.android.agent.core.LoopState
import com.lumena.android.agent.local.PlannedTool
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate

data class PendingWorkflowTool(
    val plan: PlannedTool,
    val history: List<OllamaMessage>,
    val taskPlan: List<String> = emptyList()
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
    suspend fun run(
        history: List<OllamaMessage>,
        maxSteps: Int = 8,
        onProgress: (String) -> Unit = {}
    ): WorkflowOutcome {
        var current = history
        val loopDetector = LoopDetector(maxIdenticalAttempts = 3)
        val failures = FailureTracker(FailureBudget(maxTotalSteps = maxSteps))
        var announcedPlan = false

        repeat(maxSteps) { index ->
            failures.recordStep()?.let {
                return WorkflowOutcome.Failed("Agent stopped: step limit reached ($maxSteps).")
            }

            onProgress("Thinking · step ${index + 1}/$maxSteps")
            val reply = ollama.chat(model, current).getOrElse { error ->
                failures.recordModelFailure()
                return WorkflowOutcome.Failed(
                    "Model call failed: ${error.message ?: error::class.simpleName}. The task state was kept; you can retry."
                )
            }
            failures.recordModelSuccess()

            when (val parsed = LocalWorkflowAgent.parse(reply)) {
                is AgentReply.Message -> {
                    val next = current + OllamaMessage("assistant", parsed.text)
                    return WorkflowOutcome.Finished(parsed.text, next)
                }

                is AgentReply.Tool -> {
                    if (!announcedPlan && parsed.plan.isNotEmpty()) {
                        announcedPlan = true
                        onProgress(
                            parsed.plan.mapIndexed { i, step -> "${i + 1}. $step" }
                                .joinToString(prefix = "Plan\n", separator = "\n")
                        )
                    }

                    val coreCall = AgentDecision.ToolCall(
                        tool = parsed.decision.request.tool,
                        args = parsed.decision.request.args,
                        reason = parsed.decision.reason
                    )
                    when (val loop = loopDetector.record(coreCall)) {
                        is LoopState.Detected -> {
                            return WorkflowOutcome.Failed(
                                "Agent stopped a repeated action loop after ${loop.count} identical attempts: ${parsed.decision.request.tool}"
                            )
                        }
                        is LoopState.Ok -> Unit
                    }

                    val plan = ToolGate.plan(parsed.decision)
                    if (!plan.allowed) {
                        return WorkflowOutcome.Failed("Blocked tool request: ${plan.request.tool}")
                    }

                    onProgress("Step ${index + 1}: ${plan.request.tool} · ${plan.reason}")

                    if (plan.requiresConfirmation) {
                        return WorkflowOutcome.NeedsConfirmation(
                            PendingWorkflowTool(
                                plan = plan,
                                history = current + OllamaMessage("assistant", parsed.raw),
                                taskPlan = parsed.plan
                            )
                        )
                    }

                    val localBridge = bridge
                        ?: return WorkflowOutcome.Failed("Bridge token is required for ${plan.request.tool}")
                    val result = localBridge.execute(plan.request)
                    failures.recordToolResult(coreCall, result.ok)?.let { violation ->
                        return WorkflowOutcome.Failed("Agent stopped by failure budget: $violation")
                    }
                    onProgress(
                        if (result.ok) "✓ ${plan.request.tool}" else "✗ ${plan.request.tool}: ${result.error ?: result.stderr.take(300)}"
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

        return WorkflowOutcome.Failed("Agent reached the local step limit ($maxSteps). The task state was preserved.")
    }

    suspend fun approve(
        pending: PendingWorkflowTool,
        onProgress: (String) -> Unit = {}
    ): WorkflowOutcome {
        val localBridge = bridge
            ?: return WorkflowOutcome.Failed("Bridge token is required for ${pending.plan.request.tool}")
        onProgress("Running ${pending.plan.request.tool}…")
        val result = localBridge.execute(pending.plan.request)
        onProgress(
            if (result.ok) "✓ ${pending.plan.request.tool}" else "✗ ${pending.plan.request.tool}: ${result.error ?: result.stderr.take(300)}"
        )
        val next = pending.history + LocalWorkflowAgent.toolResultMessage(
            tool = pending.plan.request.tool,
            ok = result.ok,
            stdout = result.stdout,
            stderr = result.stderr,
            error = result.error
        )
        return run(next, maxSteps = 8, onProgress = onProgress)
    }
}
