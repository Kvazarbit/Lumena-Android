package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentControlState
import com.lumena.android.agent.core.AgentController
import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.ControllerInstruction
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.PlannedTool
import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import java.util.UUID

data class PendingWorkflowTool(
    val plan: PlannedTool,
    val history: List<OllamaMessage>,
    val control: AgentControlState
) {
    val taskPlan: List<String>
        get() = control.plan
}

sealed interface WorkflowOutcome {
    data class Finished(
        val text: String,
        val history: List<OllamaMessage>,
        val control: AgentControlState
    ) : WorkflowOutcome

    data class NeedsConfirmation(
        val pending: PendingWorkflowTool
    ) : WorkflowOutcome

    data class Failed(
        val message: String,
        val history: List<OllamaMessage>,
        val control: AgentControlState
    ) : WorkflowOutcome
}

class WorkflowRunner(
    private val modelClient: ChatModelClient,
    private val bridge: TermuxBridgeClient?,
    private val model: String,
    private val controller: AgentController = AgentController()
) {
    suspend fun run(
        history: List<OllamaMessage>,
        task: TaskState,
        control: AgentControlState? = null,
        onProgress: (String) -> Unit = {},
        onModelText: (String) -> Unit = {},
        onState: (AgentControlState) -> Unit = {}
    ): WorkflowOutcome {
        var current = history
        var state = control ?: controller.initial(task)
        var protocolTurns = 0
        onState(state)

        while (state.task.canContinue) {
            protocolTurns++
            if (protocolTurns > 20) {
                val stopped = state.copy(
                    task = state.task.copy(
                        status = TaskStatus.FAILED,
                        errors = (state.task.errors + "Protocol turn cap reached").takeLast(8)
                    )
                )
                onState(stopped)
                return WorkflowOutcome.Failed(
                    "Agent stopped: protocol turn cap reached.",
                    current,
                    stopped
                )
            }

            onProgress("MODEL REQUEST · step ${state.task.step + 1}/${state.task.maxSteps}")
            onModelText("")
            val modelMessages = withDynamicContext(current, state)
            val replyResult = modelClient.chatStreaming(model, modelMessages) { partial ->
                onModelText(partial)
            }

            if (replyResult.isFailure) {
                val error = replyResult.exceptionOrNull()
                when (val recovery = controller.onModelFailure(
                    state,
                    error?.message ?: error?.javaClass?.simpleName.orEmpty()
                )) {
                    is ControllerInstruction.AskModelAgain -> {
                        state = recovery.state
                        onState(state)
                        current = current + OllamaMessage("user", recovery.feedback)
                        onProgress(
                            "MODEL RETRY · ${state.modelFailures}\n${recovery.feedback.take(2_000)}"
                        )
                        continue
                    }
                    is ControllerInstruction.Stop -> {
                        onState(recovery.state)
                        return WorkflowOutcome.Failed(recovery.reason, current, recovery.state)
                    }
                    else -> {
                        return WorkflowOutcome.Failed(
                            "Unexpected model recovery state.",
                            current,
                            state
                        )
                    }
                }
            }

            val reply = replyResult.getOrThrow()
            onProgress("MODEL REPLY\n${reply.take(6_000)}")

            when (val instruction = controller.interpret(reply, state)) {
                is ControllerInstruction.Execute -> {
                    state = instruction.state
                    onState(state)

                    if (state.plan.isNotEmpty() && state.task.step == 0) {
                        onProgress(
                            state.plan.mapIndexed { i, step -> "${i + 1}. $step" }
                                .joinToString(prefix = "PLAN\n", separator = "\n")
                        )
                    }

                    val requestId = buildRequestId(state)
                    val planned = ToolGate.plan(
                        PlannerDecision(
                            request = ToolRequest(
                                tool = instruction.call.tool,
                                args = instruction.call.args,
                                requestId = requestId
                            ),
                            reason = instruction.call.reason.ifBlank {
                                "Agent requested ${instruction.call.tool}"
                            }
                        )
                    )

                    if (!planned.allowed) {
                        val stopped = state.copy(
                            task = state.task.copy(
                                status = TaskStatus.FAILED,
                                errors = (state.task.errors + planned.reason).takeLast(8)
                            )
                        )
                        onState(stopped)
                        return WorkflowOutcome.Failed(
                            "Blocked tool request: ${planned.request.tool} · ${planned.reason}",
                            current,
                            stopped
                        )
                    }

                    onProgress(toolCallTrace(planned))
                    val assistantToolMessage = OllamaMessage("assistant", reply.take(12_000))
                    val toolHistory = current + assistantToolMessage

                    if (instruction.requiresConfirmation) {
                        val pending = PendingWorkflowTool(
                            plan = planned,
                            history = toolHistory,
                            control = state
                        )
                        onState(state)
                        return WorkflowOutcome.NeedsConfirmation(pending)
                    }

                    val localBridge = bridge ?: return WorkflowOutcome.Failed(
                        "Bridge token is required for ${planned.request.tool}",
                        current,
                        state
                    )

                    onProgress("TOOL RUNNING · ${planned.request.tool}")
                    val result = localBridge.execute(planned.request)

                    val transition = controller.afterTool(
                        state = state,
                        call = instruction.call,
                        ok = result.ok,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        error = result.error
                    )
                    state = transition.state
                    onState(state)
                    onProgress(toolResultTrace(planned.request.tool, result.stdout, result.stderr, result.error, result.ok))

                    current = toolHistory + LocalWorkflowAgent.toolResultMessage(
                        tool = planned.request.tool,
                        ok = result.ok,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        error = result.error
                    )

                    transition.stopReason?.let { reason ->
                        return WorkflowOutcome.Failed(reason, current, state)
                    }
                }

                is ControllerInstruction.Finish -> {
                    state = instruction.state
                    onState(state)
                    onProgress("FINISH\n${instruction.text.take(4_000)}")
                    val next = current + OllamaMessage("assistant", instruction.text)
                    return WorkflowOutcome.Finished(instruction.text, next, state)
                }

                is ControllerInstruction.AskModelAgain -> {
                    state = instruction.state
                    onState(state)
                    current = current +
                        OllamaMessage("assistant", reply.take(4_000)) +
                        OllamaMessage("user", instruction.feedback)

                    onProgress(
                        "PROTOCOL CORRECTION · retry ${state.protocolRetries}\n${instruction.feedback.take(2_000)}"
                    )
                }

                is ControllerInstruction.Stop -> {
                    onState(instruction.state)
                    onProgress("STOP\n${instruction.reason.take(2_000)}")
                    return WorkflowOutcome.Failed(
                        instruction.reason,
                        current,
                        instruction.state
                    )
                }
            }
        }

        val stopped = state.copy(
            task = state.task.copy(
                status = TaskStatus.FAILED,
                errors = (state.task.errors + "Task can no longer continue").takeLast(8)
            )
        )
        onState(stopped)
        return WorkflowOutcome.Failed(
            "Agent stopped because the task cannot continue safely.",
            current,
            stopped
        )
    }

    suspend fun approve(
        pending: PendingWorkflowTool,
        onProgress: (String) -> Unit = {},
        onModelText: (String) -> Unit = {},
        onState: (AgentControlState) -> Unit = {}
    ): WorkflowOutcome {
        val localBridge = bridge ?: return WorkflowOutcome.Failed(
            "Bridge token is required for ${pending.plan.request.tool}",
            pending.history,
            pending.control
        )

        onProgress(toolCallTrace(pending.plan))
        onProgress("TOOL RUNNING · ${pending.plan.request.tool}")
        val result = localBridge.execute(pending.plan.request)

        val call = AgentDecision.ToolCall(
            tool = pending.plan.request.tool,
            args = pending.plan.request.args,
            reason = pending.plan.reason,
            plan = pending.control.plan
        )
        val transition = controller.afterTool(
            state = pending.control,
            call = call,
            ok = result.ok,
            stdout = result.stdout,
            stderr = result.stderr,
            error = result.error
        )
        onState(transition.state)

        val next = pending.history + LocalWorkflowAgent.toolResultMessage(
            tool = pending.plan.request.tool,
            ok = result.ok,
            stdout = result.stdout,
            stderr = result.stderr,
            error = result.error
        )

        onProgress(
            toolResultTrace(
                pending.plan.request.tool,
                result.stdout,
                result.stderr,
                result.error,
                result.ok
            )
        )

        transition.stopReason?.let { reason ->
            return WorkflowOutcome.Failed(reason, next, transition.state)
        }

        return run(
            history = next,
            task = transition.state.task,
            control = transition.state,
            onProgress = onProgress,
            onModelText = onModelText,
            onState = onState
        )
    }

    private fun withDynamicContext(
        history: List<OllamaMessage>,
        state: AgentControlState
    ): List<OllamaMessage> {
        val staticSystem = history.firstOrNull { it.role == "system" }?.content
            ?: LocalWorkflowAgent.systemPrompt
        val mergedSystem = staticSystem + "\n\n" + controller.dynamicContext(state)
        return buildList {
            add(OllamaMessage("system", mergedSystem))
            addAll(history.filterNot { it.role == "system" })
        }
    }

    private fun buildRequestId(state: AgentControlState): String =
        "${state.task.id}-${state.task.step + 1}-${UUID.randomUUID()}"

    private fun toolCallTrace(planned: PlannedTool): String = buildString {
        append("TOOL CALL · ").append(planned.request.tool).append('\n')
        append("Reason · ").append(planned.reason)
        if (planned.request.args.isNotEmpty()) {
            append("\nArgs")
            planned.request.args.toSortedMap().forEach { (key, value) ->
                append("\n  ").append(key).append(" = ").append(value.take(1_500))
            }
        }
    }

    private fun toolResultTrace(
        tool: String,
        stdout: String,
        stderr: String,
        error: String?,
        ok: Boolean
    ): String = buildString {
        append("TOOL RESULT · ").append(tool)
        append(if (ok) " · OK" else " · FAILED")

        if (!error.isNullOrBlank()) {
            append("\nError\n").append(error.take(2_500))
        }
        if (stdout.isNotBlank()) {
            append("\nStdout\n").append(stdout.take(4_000))
        }
        if (stderr.isNotBlank()) {
            append("\nStderr\n").append(stderr.take(3_000))
        }
    }
}
