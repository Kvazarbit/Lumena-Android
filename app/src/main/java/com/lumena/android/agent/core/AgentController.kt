package com.lumena.android.agent.core

/**
 * Deterministic control plane around a fallible local model.
 *
 * The model proposes one next action. Lumena owns task state, limits, duplicate
 * detection, tool validation and the decision whether a task may be declared done.
 */
data class AgentControlState(
    val task: TaskState,
    val plan: List<String> = emptyList(),
    val toolUsed: Boolean = false,
    val protocolRetries: Int = 0,
    val modelFailures: Int = 0,
    val pythonFailures: Int = 0,
    val repeatedToolFailures: Map<String, Int> = emptyMap(),
    val lastToolSignature: String? = null,
    val identicalToolCalls: Int = 0,
    val verificationRequired: Boolean = false,
    val verificationReason: String? = null,
    val visualEvidenceReady: Boolean = false,
    val intent: TaskIntent = TaskIntent.GENERAL,
    val intentConfidence: Int = 0,
    val recommendedTools: List<String> = emptyList(),
    val intentGuidance: String? = null,
    val preflightCompleted: Boolean = false,
    val recoveryHint: String? = null
)

sealed interface ControllerInstruction {
    val state: AgentControlState

    data class Execute(
        val call: AgentDecision.ToolCall,
        val requiresConfirmation: Boolean,
        override val state: AgentControlState
    ) : ControllerInstruction

    data class Finish(
        val text: String,
        override val state: AgentControlState
    ) : ControllerInstruction

    data class AskModelAgain(
        val feedback: String,
        override val state: AgentControlState
    ) : ControllerInstruction

    data class Stop(
        val reason: String,
        override val state: AgentControlState
    ) : ControllerInstruction
}

data class ToolTransition(
    val state: AgentControlState,
    val stopReason: String? = null
)

class AgentController(
    private val parser: AgentResponseParser = AgentResponseParser(),
    private val budget: FailureBudget = FailureBudget()
) {
    fun initial(task: TaskState): AgentControlState {
        val profile = TaskIntentRouter.route(task.goal)
        val reserve = if (profile.preflight != null) 1 else 0
        return AgentControlState(
            task = task.copy(
                maxSteps = (task.maxSteps + reserve).coerceAtMost(budget.maxTotalSteps)
            ),
            intent = profile.intent,
            intentConfidence = profile.confidence,
            recommendedTools = profile.recommendedTools,
            intentGuidance = profile.guidance
        )
    }

    fun onModelFailure(state: AgentControlState, message: String): ControllerInstruction {
        val compactMessage = compactFailureMessage(message)
        val failures = state.modelFailures + 1
        val next = state.copy(
            modelFailures = failures,
            task = state.task.copy(
                status = TaskStatus.WAITING_MODEL,
                errors = (state.task.errors + compactMessage).takeLast(8)
            )
        )

        // Classify against the FULL error before truncating it for UI/state.
        // Long native llama.cpp logs can otherwise push the actual load-error
        // prefix out of compactMessage and cause pointless retries.
        val fullLower = message.lowercase()
        val nonRetryable = listOf(
            "embedded model load failed",
            "could not load this gguf model",
            "gguf model not found",
            "invalid android file descriptor",
            "metadata could not be parsed",
            "full model load failed",
            "allocation/mmap failed",
            "tensor layout is not accepted",
            "unauthorized",
            "http 401",
            "bridge token is required"
        ).any(fullLower::contains)

        return when {
            nonRetryable ->
                ControllerInstruction.Stop(compactMessage, fail(next, compactMessage))
            failures > budget.maxModelRetries ->
                ControllerInstruction.Stop("Model retry limit reached: $compactMessage", fail(next, compactMessage))
            else ->
                ControllerInstruction.AskModelAgain(
                    feedback = "The previous model call failed: ${compactMessage.take(600)}. Retry the SAME task from the verified state. Do not invent results.",
                    state = next
                )
        }
    }

    fun interpret(raw: String, state: AgentControlState): ControllerInstruction {
        return when (val decision = parser.parse(raw)) {
            is AgentDecision.ToolCall -> interpretTool(decision, state)
            is AgentDecision.Done -> interpretDone(decision, state)
            is AgentDecision.Reply -> interpretReply(decision, state)
        }
    }

    private fun interpretTool(
        decision: AgentDecision.ToolCall,
        state: AgentControlState
    ): ControllerInstruction {
        if (state.task.step >= minOf(state.task.maxSteps, budget.maxTotalSteps)) {
            return ControllerInstruction.Stop(
                "Agent step limit reached (${state.task.maxSteps}).",
                fail(state, "Step limit reached")
            )
        }

        val validation = ToolRegistry.validate(decision)
        if (!validation.allowed || validation.canonicalTool == null) {
            return protocolRetry(
                state,
                validation.error ?: "Unknown tool ${decision.tool}"
            )
        }

        val canonical = decision.copy(tool = validation.canonicalTool)
        val nextPlan = if (state.plan.isEmpty() && canonical.plan.isNotEmpty()) {
            canonical.plan
                .take(6)
                .map { step ->
                    step
                        .replace(Regex("^\\s*\\d+[.)]\\s*"), "")
                        .trim()
                        .take(180)
                }
                .filter { it.isNotBlank() }
        } else state.plan

        val signature = signature(canonical)
        val identical = if (signature == state.lastToolSignature) state.identicalToolCalls + 1 else 1
        if (identical >= 3) {
            val stopped = fail(
                state.copy(
                    plan = nextPlan,
                    lastToolSignature = signature,
                    identicalToolCalls = identical
                ),
                "Repeated identical tool call: ${canonical.tool}"
            )
            return ControllerInstruction.Stop(
                "Agent stopped a repeated-action loop after $identical identical calls: ${canonical.tool}",
                stopped
            )
        }

        val adaptiveMaxSteps = when {
            nextPlan.isNotEmpty() ->
                (nextPlan.size + 2).coerceIn(3, budget.maxTotalSteps)
            state.task.maxSteps < 3 ->
                3
            else ->
                state.task.maxSteps.coerceAtMost(budget.maxTotalSteps)
        }

        val next = state.copy(
            plan = nextPlan,
            protocolRetries = 0,
            modelFailures = 0,
            lastToolSignature = signature,
            identicalToolCalls = identical,
            task = state.task.copy(
                status = if (validation.requiresConfirmation) TaskStatus.WAITING_CONFIRMATION else TaskStatus.EXECUTING,
                maxSteps = maxOf(state.task.step + 1, adaptiveMaxSteps),
                lastTool = canonical.tool
            )
        )

        return ControllerInstruction.Execute(
            call = canonical,
            requiresConfirmation = validation.requiresConfirmation,
            state = next
        )
    }

    private fun interpretDone(
        decision: AgentDecision.Done,
        state: AgentControlState
    ): ControllerInstruction {
        if (requiresToolEvidence(state.intent) && !state.toolUsed) {
            return protocolRetry(
                state,
                "This operational task requires a real TOOL_RESULT before completion. Use a recommended tool from TASK RECIPE; do not claim unverified work."
            )
        }

        if (requiresVisualEvidence(state.task.goal) && !state.visualEvidenceReady) {
            return protocolRetry(
                state,
                "The user asked to find/show an image. Use image.search successfully before marking the task done. http.get/http.json or text links do not satisfy this goal."
            )
        }

        if (state.verificationRequired) {
            return protocolRetry(
                state,
                "Task cannot be marked done yet. ${state.verificationReason ?: "Verification is required."}"
            )
        }

        val finished = state.copy(
            protocolRetries = 0,
            modelFailures = 0,
            task = state.task.copy(
                status = TaskStatus.DONE,
                lastResult = decision.summary.take(4_000)
            )
        )
        return ControllerInstruction.Finish(decision.summary, finished)
    }

    private fun interpretReply(
        decision: AgentDecision.Reply,
        state: AgentControlState
    ): ControllerInstruction {
        val trimmed = decision.text.trim()
        val hasProtocolJsonShape =
            trimmed.contains("{") &&
                (
                    (
                        trimmed.contains("\"tool\"") &&
                            (trimmed.contains("\"args\"") || trimmed.contains("\"plan\""))
                    ) ||
                    trimmed.contains("\"done\"")
                )
        val looksLikeBrokenProtocol =
            hasProtocolJsonShape ||
                trimmed.contains("<tool_call>", ignoreCase = true)

        if (looksLikeBrokenProtocol) {
            return protocolRetry(
                state,
                "The previous output looked like a tool/protocol message but could not be parsed safely. Return one valid Lumena JSON tool call, or ordinary prose with no protocol fields."
            )
        }

        if (requiresToolEvidence(state.intent) && !state.toolUsed) {
            return protocolRetry(
                state,
                "This operational task requires a real TOOL_RESULT before replying. Use a recommended tool from TASK RECIPE; do not substitute prose for execution."
            )
        }

        if (requiresVisualEvidence(state.task.goal) && !state.visualEvidenceReady) {
            return protocolRetry(
                state,
                "The user asked to find/show an image. Use image.search successfully before replying. A text-only answer does not satisfy this goal."
            )
        }

        if (requiresVisualEvidence(state.task.goal) && state.visualEvidenceReady) {
            val finished = state.copy(
                task = state.task.copy(
                    status = TaskStatus.DONE,
                    lastResult = decision.text.take(4_000)
                )
            )
            return ControllerInstruction.Finish(decision.text, finished)
        }

        // A plain reply is acceptable for ordinary conversation before any tool work.
        if (!state.toolUsed && state.plan.isEmpty()) {
            val finished = state.copy(
                task = state.task.copy(
                    status = TaskStatus.DONE,
                    lastResult = decision.text.take(4_000)
                )
            )
            return ControllerInstruction.Finish(decision.text, finished)
        }

        // Once an agentic task has started, prose alone is not proof of completion.
        return protocolRetry(
            state,
            "An active tool task cannot finish with plain prose. Return exactly one next tool call, or {\"done\":true,\"summary\":\"...\"} after verification."
        )
    }

    fun afterTool(
        state: AgentControlState,
        call: AgentDecision.ToolCall,
        ok: Boolean,
        stdout: String,
        stderr: String,
        error: String?
    ): ToolTransition {
        val signature = signature(call)
        var pythonFailures = state.pythonFailures
        val repeatedFailures = state.repeatedToolFailures.toMutableMap()
        val recoveredFromPythonFailure =
            ok && call.tool.startsWith("python.") && state.pythonFailures > 0
        val recoveredFromRepeatedToolFailure =
            ok && (state.repeatedToolFailures[signature] ?: 0) > 0
        val recovered = recoveredFromPythonFailure || recoveredFromRepeatedToolFailure

        if (ok) {
            repeatedFailures.remove(signature)
            if (call.tool.startsWith("python.")) pythonFailures = 0
        } else {
            val count = (repeatedFailures[signature] ?: 0) + 1
            repeatedFailures[signature] = count
            if (count > budget.maxIdenticalToolFailures) {
                val reason = "Identical tool failure repeated $count times: ${call.tool}"
                return ToolTransition(fail(state, reason), reason)
            }
            if (call.tool.startsWith("python.")) {
                pythonFailures++
                if (pythonFailures > budget.maxPythonFailures) {
                    val reason = "Python failure budget exceeded (${budget.maxPythonFailures})"
                    return ToolTransition(fail(state, reason), reason)
                }
            }
        }

        var verificationRequired = state.verificationRequired
        var verificationReason = state.verificationReason
        if (ok && call.tool in setOf("file.write", "file.patch")) {
            val path = call.args["path"].orEmpty().lowercase()
            if (path.endsWith(".py")) {
                verificationRequired = true
                verificationReason = "Python code changed; run python.syntax_check, python.tests, or python.run successfully before finishing."
            }
        }
        if (ok && call.tool in setOf("python.syntax_check", "python.tests", "python.run")) {
            verificationRequired = false
            verificationReason = null
        }

        val resultText = buildString {
            append("ok=").append(ok)
            if (!error.isNullOrBlank()) append(" error=").append(error.take(700))
            if (stdout.isNotBlank()) append(" stdout=").append(stdout.take(1_500))
            if (stderr.isNotBlank()) append(" stderr=").append(stderr.take(1_000))
        }

        val nextStep = state.task.step + 1
        val recoveryMaxSteps = if (recovered) {
            maxOf(
                state.task.maxSteps,
                (nextStep + 2).coerceAtMost(budget.maxTotalSteps)
            )
        } else {
            state.task.maxSteps
        }

        val nextTask = state.task.copy(
            status = TaskStatus.WAITING_MODEL,
            step = nextStep,
            maxSteps = recoveryMaxSteps,
            lastTool = call.tool,
            lastResult = resultText,
            errors = if (ok) state.task.errors else (state.task.errors + resultText).takeLast(8)
        )

        return ToolTransition(
            state = state.copy(
                task = nextTask,
                toolUsed = true,
                protocolRetries = 0,
                modelFailures = 0,
                pythonFailures = pythonFailures,
                repeatedToolFailures = repeatedFailures,
                verificationRequired = verificationRequired,
                verificationReason = verificationReason,
                visualEvidenceReady = state.visualEvidenceReady ||
                    (ok && ToolRegistry.canonicalize(call.tool) == "image.search"),
                recoveryHint = RecoveryAdvisor.suggest(
                    task = state.task,
                    call = call,
                    ok = ok,
                    stdout = stdout,
                    stderr = stderr,
                    error = error
                )
            )
        )
    }

    fun dynamicContext(
        state: AgentControlState,
        relevantMemory: List<String> = emptyList()
    ): String {
        return ContextBuilder(
            maxMemoryItems = 6,
            maxChars = 4_500
        ).build(
            task = state.task,
            project = null,
            relevantMemory = relevantMemory,
            allowedTools = null,
            plan = state.plan,
            verificationRequirement = state.verificationReason,
            intent = state.intent,
            intentConfidence = state.intentConfidence,
            recommendedTools = state.recommendedTools,
            intentGuidance = state.intentGuidance,
            recoveryGuidance = state.recoveryHint
        )
    }

    private fun compactFailureMessage(
        message: String,
        maxChars: Int = 4_000
    ): String {
        val clean = message
            .replace('\u0000', ' ')
            .trim()
        if (clean.length <= maxChars) return clean

        val marker = "\n...[middle of technical log omitted]...\n"
        val available = (maxChars - marker.length).coerceAtLeast(0)
        val headChars = (available * 3) / 5
        val tailChars = available - headChars
        return clean.take(headChars) +
            marker +
            clean.takeLast(tailChars)
    }

    private fun protocolRetry(state: AgentControlState, problem: String): ControllerInstruction {
        val retries = state.protocolRetries + 1
        val next = state.copy(
            protocolRetries = retries,
            task = state.task.copy(
                status = TaskStatus.WAITING_MODEL
            )
        )
        return if (retries > 2) {
            ControllerInstruction.Stop(
                "Model protocol failed repeatedly: $problem",
                fail(next, problem)
            )
        } else {
            ControllerInstruction.AskModelAgain(
                feedback = "Protocol correction: $problem Continue the SAME task from verified state. Do not claim success unless a tool result proves it.",
                state = next
            )
        }
    }

    private fun requiresToolEvidence(intent: TaskIntent): Boolean =
        intent in setOf(
            TaskIntent.VISUAL_SEARCH,
            TaskIntent.OLLAMA_OPERATION,
            TaskIntent.CODE_WORK,
            TaskIntent.FILE_INSPECTION,
            TaskIntent.PUBLIC_WEB
        )

    private fun requiresVisualEvidence(goal: String): Boolean =
        VisualGoalRouter.route(goal) != null

    private fun fail(state: AgentControlState, reason: String): AgentControlState = state.copy(
        task = state.task.copy(
            status = TaskStatus.FAILED,
            errors = (state.task.errors + reason).takeLast(8)
        )
    )

    private fun signature(call: AgentDecision.ToolCall): String {
        val args = call.args.toSortedMap().entries.joinToString("&") { (k, v) -> "$k=${v.trim()}" }
        return "${ToolRegistry.canonicalize(call.tool)}|$args"
    }
}
