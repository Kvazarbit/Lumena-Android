package com.lumena.android.ollama

import com.lumena.android.agent.core.*
import com.lumena.android.agent.local.*
import com.lumena.android.agent.runtime.ActiveTool
import com.lumena.android.agent.runtime.RunControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID

data class PendingWorkflowTool(val plan: PlannedTool, val history: List<OllamaMessage>, val control: AgentControlState) {
    val taskPlan: List<String> get() = control.plan
}
sealed interface WorkflowOutcome {
    data class Finished(val text: String, val history: List<OllamaMessage>, val control: AgentControlState) : WorkflowOutcome
    data class NeedsConfirmation(val pending: PendingWorkflowTool) : WorkflowOutcome
    data class Failed(val message: String, val history: List<OllamaMessage>, val control: AgentControlState) : WorkflowOutcome
    data class Stopped(val message: String, val history: List<OllamaMessage>, val control: AgentControlState) : WorkflowOutcome
}
class WorkflowRunner(
    private val ollama: OllamaClient,
    private val bridge: TermuxBridgeClient?,
    private val model: String,
    private val controller: AgentController = AgentController(),
    private val runtime: RunControl = RunControl(),
    private val onPulse: (ModelPulse) -> Unit = {},
    private val onToolOutput: (BridgeJobStatus) -> Unit = {},
    private val onToolStarted: (ActiveTool?) -> Unit = {},
    private val checkpoint: (List<OllamaMessage>, AgentControlState) -> Unit = { _, _ -> }
) {
    private fun stopped(history: List<OllamaMessage>, state: AgentControlState): WorkflowOutcome.Stopped {
        val next = state.copy(task = state.task.copy(status = TaskStatus.CANCELLED))
        checkpoint(history, next)
        return WorkflowOutcome.Stopped("Зупинено після поточного кроку. Виконані зміни збережено; наступні інструменти не запускались.", history, next)
    }

    suspend fun run(
        history: List<OllamaMessage>, task: TaskState, control: AgentControlState? = null,
        onProgress: (String) -> Unit = {}, onState: (AgentControlState) -> Unit = {}
    ): WorkflowOutcome {
        var current = history
        var state = control ?: controller.initial(task)
        var turns = 0
        while (state.task.status !in setOf(TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            currentCoroutineContext().ensureActive()
            if (runtime.shouldStop()) return stopped(current, state)
            if (++turns > 20) return failed("Protocol turn cap reached", current, state)
            state = state.copy(task = state.task.copy(status = TaskStatus.WAITING_MODEL))
            onState(state); checkpoint(current, state)
            onProgress("Модель: очікування відповіді · виконано ${state.task.step}/${state.task.maxSteps} дій")
            val reply = ollama.chat(model, withDynamicContext(current, state), onPulse)
            currentCoroutineContext().ensureActive()
            if (runtime.shouldStop()) return stopped(current, state)
            if (reply.isFailure) {
                val reason = reply.exceptionOrNull()?.message ?: "Model request failed"
                when (val recovery = controller.onModelFailure(state, reason)) {
                    is ControllerInstruction.AskModelAgain -> {
                        state = recovery.state
                        current = current + OllamaMessage("user", recovery.feedback)
                        checkpoint(current, state)
                        onProgress("Повтор звернення до моделі · ${state.modelFailures}: $reason")
                        continue
                    }
                    is ControllerInstruction.Stop -> return failed(recovery.reason, current, recovery.state)
                    else -> return failed(reason, current, state)
                }
            }
            val raw = reply.getOrThrow()
            when (val instruction = controller.interpret(raw, state)) {
                is ControllerInstruction.Execute -> {
                    state = instruction.state
                    onState(state)
                    if (state.plan.isNotEmpty() && state.task.step == 0)
                        onProgress(state.plan.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n", "План\n"))
                    val planned = ToolGate.plan(PlannerDecision(ToolRequest(instruction.call.tool, instruction.call.args), instruction.call.reason))
                    if (!planned.allowed) return failed(planned.reason, current, state)
                    current = current + OllamaMessage("assistant", raw)
                    checkpoint(current, state)
                    if (runtime.shouldStop()) return stopped(current, state)
                    if (instruction.requiresConfirmation)
                        return WorkflowOutcome.NeedsConfirmation(PendingWorkflowTool(planned, current, state))
                    if (bridge == null) return failed("Bridge token is required", current, state)
                    onProgress("Виконання ${state.task.step + 1}: ${planned.request.tool}")
                    val result = executeLocal(planned.request)
                    currentCoroutineContext().ensureActive()
                    val transition = controller.afterTool(state, instruction.call, result.ok, result.stdout, result.stderr, result.error)
                    state = transition.state
                    current = current + LocalWorkflowAgent.toolResultMessage(planned.request.tool, result.ok, result.stdout, result.stderr, result.error)
                    checkpoint(current, state); onState(state)
                    showResult(planned.request.tool, result, onProgress)
                    if (result.error?.startsWith("Transport error") == true)
                        return failed(result.error, current, state)
                    transition.stopReason?.let { return failed(it, current, state) }
                }
                is ControllerInstruction.Finish -> {
                    state = instruction.state
                    current = current + OllamaMessage("assistant", instruction.text)
                    checkpoint(current, state); onState(state)
                    return WorkflowOutcome.Finished(instruction.text, current, state)
                }
                is ControllerInstruction.AskModelAgain -> {
                    state = instruction.state
                    current = current + OllamaMessage("assistant", raw.take(4000)) + OllamaMessage("user", instruction.feedback)
                    checkpoint(current, state); onState(state)
                    onProgress("Уточнення формату відповіді · ${state.protocolRetries}")
                }
                is ControllerInstruction.Stop -> return failed(instruction.reason, current, instruction.state)
            }
        }
        return failed("Task cannot continue safely", current, state)
    }

    suspend fun approve(pending: PendingWorkflowTool, onProgress: (String) -> Unit = {}, onState: (AgentControlState) -> Unit = {}): WorkflowOutcome {
        currentCoroutineContext().ensureActive()
        if (runtime.shouldStop()) return stopped(pending.history, pending.control)
        val checked = ToolGate.plan(PlannerDecision(pending.plan.request, pending.plan.reason))
        if (!checked.allowed || bridge == null) return failed("Tool validation or bridge connection failed", pending.history, pending.control)
        val executing = pending.control.copy(task = pending.control.task.copy(status = TaskStatus.EXECUTING))
        checkpoint(pending.history, executing); onState(executing)
        onProgress("Виконання ${executing.task.step + 1}: ${checked.request.tool}")
        val result = executeLocal(checked.request)
        currentCoroutineContext().ensureActive()
        val call = AgentDecision.ToolCall(checked.request.tool, checked.request.args, checked.reason, pending.control.plan)
        val transition = controller.afterTool(executing, call, result.ok, result.stdout, result.stderr, result.error)
        val next = pending.history + LocalWorkflowAgent.toolResultMessage(checked.request.tool, result.ok, result.stdout, result.stderr, result.error)
        checkpoint(next, transition.state); onState(transition.state)
        showResult(checked.request.tool, result, onProgress)
        if (result.error?.startsWith("Transport error") == true) return failed(result.error, next, transition.state)
        if (runtime.shouldStop()) return stopped(next, transition.state)
        transition.stopReason?.let { return failed(it, next, transition.state) }
        return run(next, transition.state.task, transition.state, onProgress, onState)
    }

    private suspend fun executeLocal(request: ToolRequest): ToolResult = coroutineScope {
        val client = requireNotNull(bridge)
        val active = ActiveTool(UUID.randomUUID().toString(), request)
        runtime.activeTool = active
        onToolStarted(active)
        val poller = launch {
            while (true) {
                delay(750)
                try { onToolOutput(client.jobStatus(active.id)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    onToolOutput(BridgeJobStatus(active.id, "unavailable"))
                    break // old bridge or unavailable control plane; no endless polling
                }
            }
        }
        try {
            val result = client.execute(request, active.id)
            if (result.error?.startsWith("Transport error") != true) {
                runtime.activeTool = null
                onToolStarted(null)
            }
            result
        } finally { poller.cancel() }
    }
    private fun showResult(tool: String, result: ToolResult, onProgress: (String) -> Unit) {
        onProgress(buildString {
            append(if (result.ok) "✓ " else "✗ ").append(tool)
            result.exitCode?.let { append(" · exit ").append(it) }
            result.error?.let { append("\n").append(it.take(700)) }
            if (result.stdout.isNotBlank()) append("\nstdout:\n").append(result.stdout.takeLast(6000))
            if (result.stderr.isNotBlank()) append("\nstderr:\n").append(result.stderr.takeLast(3000))
        })
    }
    private fun failed(reason: String, history: List<OllamaMessage>, state: AgentControlState): WorkflowOutcome.Failed {
        val next = state.copy(task = state.task.copy(status = TaskStatus.FAILED, errors = (state.task.errors + reason).takeLast(8)))
        checkpoint(history, next)
        return WorkflowOutcome.Failed(reason, history, next)
    }
    private fun withDynamicContext(history: List<OllamaMessage>, state: AgentControlState): List<OllamaMessage> =
        listOf(OllamaMessage("system", LocalWorkflowAgent.systemPrompt + "\n\n" + controller.dynamicContext(state))) +
            history.filterNot { it.role == "system" }
}
