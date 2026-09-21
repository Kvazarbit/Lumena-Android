package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentControlState
import com.lumena.android.agent.core.AgentController
import com.lumena.android.agent.core.ContextKernel
import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.ControllerInstruction
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
import com.lumena.android.agent.core.TaskIntentRouter
import com.lumena.android.agent.local.PlannedTool
import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class PendingWorkflowTool(
    val plan: PlannedTool,
    val history: List<OllamaMessage>,
    val control: AgentControlState,
    val images: List<WorkflowImage> = emptyList()
) {
    val taskPlan: List<String>
        get() = control.plan
}

sealed interface WorkflowOutcome {
    data class Finished(
        val text: String,
        val history: List<OllamaMessage>,
        val control: AgentControlState,
        val images: List<WorkflowImage> = emptyList()
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
    private val bridge: ToolExecutor?,
    private val model: String,
    private val controller: AgentController = AgentController(),
    private val relevantMemoryProvider: (TaskState) -> List<String> = { emptyList() },
    private val onToolExperience: (TaskState, ToolRequest, ToolResult, Long) -> Unit = { _, _, _, _ -> },
    private val checkpoint: suspend (AgentControlState) -> Unit = {}
) {
    suspend fun run(
        history: List<OllamaMessage>,
        task: TaskState,
        control: AgentControlState? = null,
        initialImages: List<WorkflowImage> = emptyList(),
        onProgress: (String) -> Unit = {},
        onModelText: (String) -> Unit = {},
        onToolTelemetry: (String) -> Unit = {},
        isApprovedForTask: (String, ToolRequest) -> Boolean = { _, _ -> false },
        onState: (AgentControlState) -> Unit = {}
    ): WorkflowOutcome {
        var current = history
        var state = control ?: controller.initial(task)
        val collectedImages = initialImages.toMutableList()
        var protocolTurns = 0
        publish(state, onState)

        val intentProfile = TaskIntentRouter.route(state.task.goal)
        if (!state.preflightCompleted && state.task.step == 0) {
            onProgress(
                "INTENT · ${intentProfile.intent} · confidence=${intentProfile.confidence}"
            )

            val preflight = intentProfile.preflight
            if (preflight == null) {
                state = state.copy(preflightCompleted = true)
                publish(state, onState)
            } else {
                val canonical = ToolRegistry.canonicalize(preflight.tool)
                val spec = ToolRegistry.get(canonical)
                if (spec?.risk != ToolRisk.READ_ONLY) {
                    val stopped = state.copy(
                        preflightCompleted = true,
                        task = state.task.copy(
                            status = TaskStatus.FAILED,
                            errors = (state.task.errors +
                                "Unsafe preflight rejected: $canonical").takeLast(8)
                        )
                    )
                    publish(stopped, onState)
                    return WorkflowOutcome.Failed(
                        "Agent policy rejected a non-read-only preflight: $canonical",
                        current,
                        stopped
                    )
                }

                val localBridge = bridge
                if (localBridge == null) {
                    if (preflight.mandatory) {
                        val stopped = state.copy(
                            preflightCompleted = true,
                            task = state.task.copy(
                                status = TaskStatus.FAILED,
                                errors = (state.task.errors +
                                    "Bridge token is required for mandatory $canonical preflight").takeLast(8)
                            )
                        )
                        publish(stopped, onState)
                        return WorkflowOutcome.Failed(
                            "Bridge token is required for this local task.",
                            current,
                            stopped
                        )
                    }

                    state = state.copy(
                        preflightCompleted = true,
                        recoveryHint = "Optional $canonical preflight was skipped because the local bridge is unavailable. Do not invent local state."
                    )
                    publish(state, onState)
                } else {
                    val request = ToolRequest(
                        tool = canonical,
                        args = preflight.args,
                        requestId = buildRequestId(state)
                    )
                    onProgress(
                        "PREFLIGHT · $canonical\n${preflight.reason.take(500)}"
                    )

                    state = markInFlight(state, request)
                    publish(state, onState)
                    val startedNs = System.nanoTime()
                    val rawResult = executeWithTelemetry(
                        localBridge,
                        request,
                        onToolTelemetry
                    )
                    val (result, displayImages) = normalizeDisplayResult(
                        request,
                        rawResult
                    )
                    displayImages.forEach { image ->
                        if (collectedImages.none { it.thumbnailUrl == image.thumbnailUrl }) {
                            collectedImages += image
                        }
                    }
                    recordExperience(state.task, request, result, startedNs, onProgress)

                    val transition = controller.afterTool(
                        state = state,
                        call = AgentDecision.ToolCall(
                            tool = canonical,
                            args = request.args,
                            reason = preflight.reason
                        ),
                        ok = result.ok,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        error = result.error,
                        outcomeUnknown = result.outcomeUnknown
                    )
                    state = transition.state.copy(preflightCompleted = true)
                    publish(state, onState)
                    onProgress(
                        toolResultTrace(
                            request.tool,
                            result.stdout,
                            result.stderr,
                            result.error,
                            result.ok
                        )
                    )

                    current = current + LocalWorkflowAgent.toolResultMessage(
                        tool = request.tool,
                        ok = result.ok,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        error = result.error
                    )

                    transition.stopReason?.let { reason ->
                        return WorkflowOutcome.Failed(reason, current, state)
                    }
                }
            }
        } else if (!state.preflightCompleted) {
            // A restored/in-progress task must not replay a new preflight over
            // already executed work.
            state = state.copy(preflightCompleted = true)
            publish(state, onState)
        }

        while (state.task.canContinue) {
            protocolTurns++
            if (protocolTurns > 20) {
                val stopped = state.copy(
                    task = state.task.copy(
                        status = TaskStatus.FAILED,
                        errors = (state.task.errors + "Protocol turn cap reached").takeLast(8)
                    )
                )
                publish(stopped, onState)
                return WorkflowOutcome.Failed(
                    "Agent stopped: protocol turn cap reached.",
                    current,
                    stopped
                )
            }

            onProgress(
                if (state.plan.isEmpty() && state.task.step == 0) {
                    "MODEL REQUEST · planning"
                } else {
                    "MODEL REQUEST · step ${state.task.step + 1}/${state.task.maxSteps}"
                }
            )
            onModelText("")
            val relevantMemory = relevantMemoryProvider(state.task)
            if (relevantMemory.isNotEmpty()) {
                onProgress(
                    relevantMemory
                        .take(8)
                        .joinToString(
                            prefix = "MEMORY CONTEXT · ${relevantMemory.size}\n",
                            separator = "\n"
                        ) { "- ${it.take(700)}" }
                )
            }
            val modelMessages = withDynamicContext(current, state, relevantMemory)
            val replyResult = modelClient.chatStreaming(model, modelMessages) { partial ->
                onModelText(partial)
            }
            coroutineContext.ensureActive()

            if (replyResult.isFailure) {
                val error = replyResult.exceptionOrNull()
                if (error is CancellationException) throw error
                when (val recovery = controller.onModelFailure(
                    state,
                    error?.message ?: error?.javaClass?.simpleName.orEmpty()
                )) {
                    is ControllerInstruction.AskModelAgain -> {
                        state = recovery.state
                        publish(state, onState)
                        current = current + OllamaMessage("user", recovery.feedback)
                        onProgress(
                            "MODEL RETRY · ${state.modelFailures}\n${recovery.feedback.take(2_000)}"
                        )
                        continue
                    }
                    is ControllerInstruction.Stop -> {
                        publish(recovery.state, onState)
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
                    publish(state, onState)

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
                        publish(stopped, onState)
                        return WorkflowOutcome.Failed(
                            "Blocked tool request: ${planned.request.tool} · ${planned.reason}",
                            current,
                            stopped
                        )
                    }

                    onProgress(toolCallTrace(planned))
                    val assistantToolMessage = OllamaMessage("assistant", reply.take(12_000))
                    val toolHistory = current + assistantToolMessage

                    val approvedForTask =
                        instruction.requiresConfirmation &&
                            isApprovedForTask(state.task.id, planned.request)

                    if (instruction.requiresConfirmation && !approvedForTask) {
                        val pending = PendingWorkflowTool(
                            plan = planned,
                            history = toolHistory,
                            control = state,
                            images = collectedImages.toList()
                        )
                        publish(state, onState)
                        return WorkflowOutcome.NeedsConfirmation(pending)
                    }

                    if (approvedForTask) {
                        onProgress("APPROVAL CACHE · exact action approved for this task")
                    }

                    val localBridge = bridge ?: return WorkflowOutcome.Failed(
                        "Bridge token is required for ${planned.request.tool}",
                        current,
                        state
                    )

                    onProgress("TOOL RUNNING · ${planned.request.tool}")
                    state = markInFlight(state, planned.request)
                    publish(state, onState)
                    val startedNs = System.nanoTime()
                    val rawResult = executeWithTelemetry(
                        localBridge,
                        planned.request,
                        onToolTelemetry
                    )
                    val (result, displayImages) = normalizeDisplayResult(
                        planned.request,
                        rawResult
                    )
                    displayImages.forEach { image ->
                        if (collectedImages.none { it.thumbnailUrl == image.thumbnailUrl }) {
                            collectedImages += image
                        }
                    }
                    recordExperience(state.task, planned.request, result, startedNs, onProgress)

                    val transition = controller.afterTool(
                        state = state,
                        call = instruction.call,
                        ok = result.ok,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        error = result.error,
                        outcomeUnknown = result.outcomeUnknown
                    )
                    state = transition.state
                    publish(state, onState)
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
                    publish(state, onState)
                    onProgress("FINISH\n${instruction.text.take(4_000)}")
                    val next = current + OllamaMessage("assistant", instruction.text)
                    return WorkflowOutcome.Finished(
                        instruction.text,
                        next,
                        state,
                        images = collectedImages.toList()
                    )
                }

                is ControllerInstruction.AskModelAgain -> {
                    state = instruction.state
                    publish(state, onState)
                    current = current +
                        OllamaMessage("assistant", reply.take(4_000)) +
                        OllamaMessage("user", instruction.feedback)

                    onProgress(
                        "PROTOCOL CORRECTION · retry ${state.protocolRetries}\n${instruction.feedback.take(2_000)}"
                    )
                }

                is ControllerInstruction.Stop -> {
                    publish(instruction.state, onState)
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
        publish(stopped, onState)
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
        onToolTelemetry: (String) -> Unit = {},
        isApprovedForTask: (String, ToolRequest) -> Boolean = { _, _ -> false },
        onState: (AgentControlState) -> Unit = {}
    ): WorkflowOutcome {
        val localBridge = bridge ?: return WorkflowOutcome.Failed(
            "Bridge token is required for ${pending.plan.request.tool}",
            pending.history,
            pending.control
        )

        onProgress(toolCallTrace(pending.plan))
        onProgress("TOOL RUNNING · ${pending.plan.request.tool}")
        val executing = markInFlight(pending.control, pending.plan.request)
        publish(executing, onState)
        val startedNs = System.nanoTime()
        val rawResult = executeWithTelemetry(
            localBridge,
            pending.plan.request,
            onToolTelemetry
        )
        val (result, approvedImages) = normalizeDisplayResult(
            pending.plan.request,
            rawResult
        )
        recordExperience(pending.control.task, pending.plan.request, result, startedNs, onProgress)

        val call = AgentDecision.ToolCall(
            tool = pending.plan.request.tool,
            args = pending.plan.request.args,
            reason = pending.plan.reason,
            plan = pending.control.plan
        )
        val transition = controller.afterTool(
            state = executing,
            call = call,
            ok = result.ok,
            stdout = result.stdout,
            stderr = result.stderr,
            error = result.error,
            outcomeUnknown = result.outcomeUnknown
        )
        publish(transition.state, onState)

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
            initialImages = (pending.images + approvedImages)
                .distinctBy { it.thumbnailUrl }
                .take(8),
            onProgress = onProgress,
            onModelText = onModelText,
            onToolTelemetry = onToolTelemetry,
            isApprovedForTask = isApprovedForTask,
            onState = onState
        )
    }

    private fun normalizeDisplayResult(
        request: ToolRequest,
        result: ToolResult
    ): Pair<ToolResult, List<WorkflowImage>> {
        ContextKernel.resultFailure(request.tool, result.ok, result.exitCode, result.tool, result.stdout)?.let {
            return result.copy(ok = false, error = it) to emptyList()
        }
        if (
            ToolRegistry.canonicalize(request.tool) != "image.search" ||
            !result.ok
        ) {
            return result to emptyList()
        }

        val images = ImageSearchResultParser.parse(result.stdout)
        if (images.isEmpty()) {
            return result.copy(
                ok = false,
                error = "image.search returned no displayable safe image previews"
            ) to emptyList()
        }

        return result to images
    }

    private suspend fun executeWithTelemetry(
        localBridge: ToolExecutor,
        request: ToolRequest,
        onToolTelemetry: (String) -> Unit
    ): ToolResult = coroutineScope {
        val risk = ToolRegistry.get(request.tool)?.risk
        val requestId = request.requestId
        val processBacked = request.tool in setOf(
            "python.run",
            "python.syntax_check",
            "python.tests",
            "ollama.pull"
        )
        if (
            risk != ToolRisk.EXECUTABLE ||
            !processBacked ||
            requestId.isNullOrBlank()
        ) {
            return@coroutineScope localBridge.execute(request)
        }

        val monitor = launch {
            delay(750)
            while (isActive) {
                val status = localBridge.execute(
                    ToolRequest(
                        tool = "process.status",
                        args = mapOf("requestId" to requestId)
                    )
                )
                if (status.ok && status.stdout.isNotBlank()) {
                    onToolTelemetry(status.stdout)
                }
                delay(2_000)
            }
        }

        try {
            localBridge.execute(request)
        } finally {
            monitor.cancelAndJoin()
            onToolTelemetry("")
        }
    }

    private fun withDynamicContext(
        history: List<OllamaMessage>,
        state: AgentControlState,
        relevantMemory: List<String>
    ): List<OllamaMessage> {
        val staticSystem = history.firstOrNull { it.role == "system" }?.content
            ?: LocalWorkflowAgent.systemPrompt
        val dynamic = controller.dynamicContext(
            state,
            relevantMemory = relevantMemory
        )
        val compactGoal = state.task.goal
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(1_000)
        val compactLastResult = state.task.lastResult
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.trim()
            ?.take(800)
        val recap = buildString {
            appendLine("CRITICAL TASK RECAP")
            appendLine("goal=$compactGoal")
            appendLine("status=${state.task.status}")
            appendLine("step=${state.task.step}/${state.task.maxSteps}")
            state.task.lastTool?.let { appendLine("last_tool=$it") }
            compactLastResult?.let { appendLine("last_result=$it") }
            state.verificationReason?.let {
                appendLine("verification_required=${it.replace(Regex("[\\r\\n]+"), " ").take(500)}")
            }
        }.trimEnd()

        val mergedSystem = staticSystem + "\n\n" + dynamic + "\n\n" + recap
        return buildList {
            add(OllamaMessage("system", mergedSystem))
            addAll(history.filterNot { it.role == "system" })
        }
    }

    private suspend fun publish(state: AgentControlState, onState: (AgentControlState) -> Unit) {
        // Persist before acknowledging state or dispatching the next external effect.
        checkpoint(state)
        onState(state)
    }

    private fun markInFlight(state: AgentControlState, request: ToolRequest): AgentControlState = state.copy(
        task = state.task.copy(status = TaskStatus.EXECUTING, kernel = ContextKernel.before(
            state.task.kernel, AgentDecision.ToolCall(request.tool, request.args))))

    private fun recordExperience(task: TaskState, request: ToolRequest, result: ToolResult,
                                 startedNs: Long, onProgress: (String) -> Unit) {
        if (result.outcomeUnknown) {
            onProgress("UNKNOWN OUTCOME · результат не врахований як успіх або невдача")
            return
        }
        try {
            onToolExperience(task, request, result, ((System.nanoTime() - startedNs) / 1_000_000).coerceAtLeast(0))
        } catch (error: Exception) {
            onProgress("EXPERIENCE NOT SAVED · ${error.message.orEmpty().take(300)}")
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
