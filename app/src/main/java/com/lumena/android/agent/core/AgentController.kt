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
    val protocolNormalizations: Int = 0,
    val lastNormalizationRule: String? = null,
    val modelFailures: Int = 0,
    val semanticRecoverySpent: Int = 0,
    val actionFamilyFailures: Map<String, Int> = emptyMap(),
    val pythonFailures: Int = 0,
    val repeatedToolFailures: Map<String, Int> = emptyMap(),
    val lastToolSignature: String? = null,
    val identicalToolCalls: Int = 0,
    val verificationRequired: Boolean = false,
    val verificationReason: String? = null,
    val pendingPythonPaths: Set<String> = emptySet(),
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
    val stopReason: String? = null,
    val partialReason: String? = null,
    val failureEvent: FailureEvent? = null,
    val reflexCandidates: ReflexCandidateSet? = null
)

class AgentController(
    private val normalizer: ProtocolNormalizer = ProtocolNormalizer(),
    private val parser: AgentResponseParser = AgentResponseParser(),
    private val budget: FailureBudget = FailureBudget()
) {
    fun initial(task: TaskState): AgentControlState {
        val profile = TaskIntentRouter.route(task.goal)
        val reserve = if (profile.preflight != null) 1 else 0
        val initialToolBudget = maxOf(
            task.maxSteps + reserve,
            profile.minimumToolSteps
        ).coerceAtMost(budget.maxTotalSteps)
        return AgentControlState(
            task = task.copy(
                maxSteps = initialToolBudget
            ),
            intent = profile.intent,
            pendingPythonPaths = task.kernel.pendingVerification,
            verificationRequired = task.kernel.pendingVerification.isNotEmpty(),
            verificationReason = task.kernel.pendingVerification.takeIf { it.isNotEmpty() }?.let {
                "Verify the previously changed Python targets: " + it.joinToString()
            },
            intentConfidence = profile.confidence,
            recommendedTools = profile.recommendedTools,
            intentGuidance = profile.guidance
        )
    }

    fun onModelFailure(state: AgentControlState, message: String): ControllerInstruction {
        val failures = state.modelFailures + 1
        val event = FailureEvents.fromModel(
            message = message,
            attempt = failures
        )
        val compactMessage = compactFailureMessage(event.evidence)
        val next = state.copy(
            modelFailures = failures,
            task = state.task.copy(
                status = TaskStatus.WAITING_MODEL,
                errors = (state.task.errors + compactMessage).takeLast(8)
            )
        )
        val decision = ConstitutionKernel.decide(
            event = event,
            state = RecoveryState(
                familyFailures = failures,
                semanticRecoverySpent = 0,
                maxFamilyFailures = budget.maxModelRetries,
                maxSemanticRecoveries = budget.maxSemanticRecoveries
            )
        )

        return when (decision) {
            is RecoveryDecision.RetryVariant ->
                ControllerInstruction.AskModelAgain(
                    feedback = "The previous model call failed [${event.failureClass}]: ${compactMessage.take(600)}. ${decision.guidance}",
                    state = next
                )

            is RecoveryDecision.TryAlternative ->
                ControllerInstruction.AskModelAgain(
                    feedback = "The previous model call failed [${event.failureClass}]: ${compactMessage.take(600)}. ${decision.guidance}",
                    state = next
                )

            is RecoveryDecision.DegradePartial ->
                ControllerInstruction.Stop(
                    "Model recovery cannot continue safely: ${decision.reason}",
                    fail(next, compactMessage)
                )

            is RecoveryDecision.Stop -> {
                val reason = if (event.retryable == false) {
                    compactMessage
                } else {
                    "Model retry limit reached: $compactMessage"
                }
                ControllerInstruction.Stop(reason, fail(next, compactMessage))
            }
        }
    }

    fun interpret(raw: String, state: AgentControlState): ControllerInstruction {
        return when (val normalized = normalizer.normalize(raw)) {
            is NormalizationResult.Canonical -> {
                val next = if (normalized.changed) {
                    state.copy(
                        protocolNormalizations = state.protocolNormalizations + 1,
                        lastNormalizationRule = normalized.rule?.name
                    )
                } else {
                    state
                }
                interpretDecision(parser.parse(normalized.json), next)
            }

            is NormalizationResult.PlainText ->
                interpretDecision(AgentDecision.Reply(normalized.text), state)

            is NormalizationResult.Failure -> {
                val hasSuccessfulToolEvidence =
                    state.toolUsed &&
                        state.task.lastResult
                            ?.trimStart()
                            ?.startsWith("ok=true") == true

                if (
                    normalized.kind == ProtocolFailureKind.UNSUPPORTED_SHAPE &&
                    hasSuccessfulToolEvidence &&
                    state.protocolRetries >= 1
                ) {
                    return preserveUnsupportedProtocolAsPartial(
                        state = state,
                        raw = raw,
                        reason = normalized.reason
                    )
                }

                val event = FailureEvents.fromProtocol(
                    failure = normalized,
                    attempt = state.protocolRetries + 1
                )
                val webContinuationHint =
                    if (
                        normalized.kind == ProtocolFailureKind.UNSUPPORTED_SHAPE &&
                        hasSuccessfulToolEvidence &&
                        state.intent == TaskIntent.PUBLIC_WEB &&
                        state.task.lastTool == "web.search"
                    ) {
                        " Do not echo or wrap the search-result JSON. " +
                            "Choose exactly one relevant URL from the verified TOOL_RESULT " +
                            "and return one {\"tool\":\"web.read\",\"args\":{\"url\":\"https://...\"}} object, " +
                            "or return partial JSON if source reading cannot continue."
                    } else {
                        ""
                    }

                protocolRetry(
                    state = state,
                    problem = "Protocol ${normalized.kind} [${event.failureClass}]: ${event.evidence}. " +
                        "Return exactly one valid tool/done/partial/reply JSON object." +
                        webContinuationHint,
                    observedEvent = event
                )
            }
        }
    }

    private fun interpretDecision(
        decision: AgentDecision,
        state: AgentControlState
    ): ControllerInstruction = when (decision) {
        is AgentDecision.ToolCall -> interpretTool(decision, state)
        is AgentDecision.Done -> interpretDone(decision, state)
        is AgentDecision.Partial -> ControllerInstruction.Finish(
            "Частково виконано.\n${decision.summary}",
            state.copy(
                task = state.task.copy(
                    status = TaskStatus.PARTIAL,
                    lastResult = decision.summary.take(4000)
                )
            )
        )
        is AgentDecision.Reply -> interpretReply(decision, state)
    }

    private fun interpretTool(
        decision: AgentDecision.ToolCall,
        state: AgentControlState
    ): ControllerInstruction {
        if (state.task.step >= minOf(state.task.maxSteps, budget.maxTotalSteps)) {
            // The last model turn is deliberately available at the tool limit. A
            // small local model may nevertheless propose one more cleanup/check
            // even though the latest TOOL_RESULT already completed the task.
            // Give it one constrained conclusion turn before reporting failure.
            if (state.toolUsed && state.protocolRetries == 0) {
                return ControllerInstruction.AskModelAgain(
                    feedback = "Tool budget is exhausted; the proposed ${ToolRegistry.canonicalize(decision.tool)} call was not executed. " +
                        "Use the existing verified TOOL_RESULT. If the goal is complete, return {\"done\":true,\"summary\":\"what was completed and verified\"}. " +
                        "If anything is incomplete or unverified, return {\"partial\":true,\"summary\":\"completed work and what remains\"}. Do not request another tool.",
                    state = state.copy(
                        protocolRetries = 1,
                        task = state.task.copy(status = TaskStatus.WAITING_MODEL)
                    )
                )
            }
            return ControllerInstruction.Stop(
                "Agent step limit reached (${state.task.maxSteps}).",
                fail(state, "Step limit reached")
            )
        }

        val validation = ToolRegistry.validate(decision)
        if (!validation.allowed || validation.canonicalTool == null) {
            val event = FailureEvents.policyDenied(
                reason = validation.error ?: "Unknown tool ${decision.tool}",
                actionFamily = ToolRegistry.canonicalize(decision.tool),
                effectClass = ToolRegistry.get(decision.tool)?.risk?.let {
                    if (it == ToolRisk.READ_ONLY) EffectClass.READ_ONLY
                    else EffectClass.MUTATING_OR_EXECUTABLE
                } ?: EffectClass.NONE,
                attempt = state.protocolRetries + 1,
                dependency = "tool-registry"
            )
            return protocolRetry(
                state,
                "${event.failureClass}: ${event.evidence}"
            )
        }

        val canonical = decision.copy(tool = validation.canonicalTool)

        if (state.recoveryHint != null &&
            state.semanticRecoverySpent >= semanticRecoveryLimit(state, canonical)
        ) {
            val report = "Частково виконано. Ліміт семантичного відновлення вичерпано без нових перевірених доказів."
            return ControllerInstruction.Finish(
                report,
                state.copy(
                    task = state.task.copy(
                        status = TaskStatus.PARTIAL,
                        lastResult = report,
                        errors = (state.task.errors + report).takeLast(8)
                    )
                )
            )
        }

        val actionFamily = RecoveryPolicy.actionFamily(canonical)
        val familyFailures = state.actionFamilyFailures[actionFamily] ?: 0
        if (familyFailures >= actionFamilyFailureLimit(state, canonical)) {
            return protocolRetry(
                state,
                "Action family $actionFamily already failed $familyFailures times in this task. " +
                    "Choose a different evidence-producing tool family or return partial; rephrasing arguments does not reset this history."
            )
        }

        if (state.task.kernel.inFlight != null) {
            return ControllerInstruction.Stop("Earlier tool outcome is unknown; inspect the checkpoint before continuing.",
                fail(state, "Unknown tool outcome"))
        }
        if (ContextKernel.repeatedObservation(state.task.kernel, canonical)) {
            return protocolRetry(state, "This exact observation returned the same result twice with no intervening mutation. " +
                "Use the recorded evidence, choose a different relevant check, or return done/partial. Do not repeat it.")
        }
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
                // Reserve turns for same-target verification, cleanup and the
                // final evidence check. These are commonly omitted from a weak
                // model's short initial plan.
                maxOf(state.task.maxSteps, nextPlan.size * 2 + 2).coerceIn(4, budget.maxTotalSteps)
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
        ContextKernel.completionBlocker(state.task.kernel)?.let { return protocolRetry(state, it) }
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
        if (trimmed.isEmpty()) {
            return protocolRetry(state, "The model returned no answer. Return one valid tool/done/partial/reply JSON object.")
        }
        val hasProtocolJsonShape =
            trimmed.contains("{") &&
                (
                    (
                        trimmed.contains("\"tool\"") &&
                            (trimmed.contains("\"args\"") || trimmed.contains("\"plan\""))
                    ) ||
                    trimmed.contains("\"done\"") ||
                    trimmed.contains("\"partial\"") ||
                    (trimmed.contains("\"name\"") && trimmed.contains("\"arguments\""))
                )
        val looksLikeBrokenProtocol =
            hasProtocolJsonShape ||
                (trimmed.startsWith("{") && Regex("\"[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*\"\\s*:").containsMatchIn(trimmed)) ||
                trimmed.contains("<tool_call>", ignoreCase = true)

        if (looksLikeBrokenProtocol) {
            return protocolRetry(
                state,
                "The previous output looked like a tool/protocol message but could not be parsed safely. Return exactly one valid tool/done/partial/reply JSON object. No proposed tool was executed."
            )
        }

        if (requiresToolEvidence(state.intent) && !state.toolUsed) {
            return recoverPlainReply(
                state, trimmed,
                "This operational task requires a real TOOL_RESULT before replying. Use a recommended tool from TASK RECIPE; do not substitute prose for execution."
            )
        }

        if (requiresVisualEvidence(state.task.goal) && !state.visualEvidenceReady) {
            return recoverPlainReply(
                state, trimmed,
                "The user asked to find/show an image. Use image.search successfully before replying. A text-only answer does not satisfy this goal."
            )
        }

        // Plain replies must not bypass interruption or same-target verification,
        // including the existing verified-image completion shortcut.
        val blocker = ContextKernel.completionBlocker(state.task.kernel)
            ?: if (state.verificationRequired) state.verificationReason ?: "Verification is required." else null
        if (blocker != null) return recoverPlainReply(state, trimmed, blocker)

        if (requiresVisualEvidence(state.task.goal) && state.visualEvidenceReady) {
            return interpretDone(AgentDecision.Done(trimmed), state)
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
        return recoverPlainReply(state, trimmed, "An active tool task needs a verified conclusion.")
    }

    private fun preserveUnsupportedProtocolAsPartial(
        state: AgentControlState,
        raw: String,
        reason: String
    ): ControllerInstruction.Finish {
        val report = buildString {
            append(
                "Частково виконано. Перевірений результат інструмента збережено, " +
                    "але модель повторно повернула JSON без однозначної канонічної дії."
            )
            state.task.lastTool?.let {
                append("\nОстанній інструмент: ").append(it)
            }
            state.task.lastResult
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    append("\nПеревірений TOOL_RESULT: ")
                    append(it.take(1800))
                }
            append("\nПричина протоколу: ").append(reason.take(600))
            raw.trim()
                .takeIf { it.isNotBlank() }
                ?.let {
                    append("\n\nНеперевірений вихід моделі:\n")
                    append(it.take(2000))
                }
        }
        return ControllerInstruction.Finish(
            report,
            state.copy(
                task = state.task.copy(
                    status = TaskStatus.PARTIAL,
                    lastResult = report,
                    errors = (
                        state.task.errors +
                            "Unsupported model protocol preserved as partial: $reason"
                        ).takeLast(8)
                )
            )
        )
    }

    private fun recoverPlainReply(state: AgentControlState, text: String, problem: String): ControllerInstruction {
        if (state.protocolRetries == 0) {
            val researchHint = if (state.intent == TaskIntent.PUBLIC_WEB)
                " Search snippets alone are not verification: read relevant source URLs with web.read and cite them."
            else ""
            return protocolRetry(state, "$problem$researchHint " +
                "Choose ONE next tool call, or {\"done\":true,\"summary\":\"verified result\"} only after completing checks, " +
                "or {\"partial\":true,\"summary\":\"what is known and what remains unverified\"}. JSON only.")
        }

        // Preserve useful model output without inventing completion evidence or
        // executing text. This is a partial report, never an automatic done.
        val blocker = ContextKernel.completionBlocker(state.task.kernel)
            ?: state.verificationReason.takeIf { state.verificationRequired }
        val report = buildString {
            append("Задачу не завершено: модель повторно відповіла поза службовим форматом. Успішне завершення не підтверджено.")
            if (blocker != null) append("\nНезавершена перевірка: ").append(blocker.take(600))
            append("\n\nНеперевірений текст моделі:\n").append(text.take(3000))
        }
        return ControllerInstruction.Finish(report, state.copy(task = state.task.copy(
            status = TaskStatus.PARTIAL,
            lastResult = report,
            errors = (state.task.errors + "Plain reply preserved as partial: $problem").takeLast(8)
        )))
    }

    fun afterTool(
        state: AgentControlState,
        call: AgentDecision.ToolCall,
        ok: Boolean,
        stdout: String,
        stderr: String,
        error: String?,
        outcomeUnknown: Boolean = false,
        errorCode: String? = null,
        failureClass: String? = null,
        retryable: Boolean? = null,
        dependency: String? = null
    ): ToolTransition {
        val canonicalTool = ToolRegistry.canonicalize(call.tool)
        val signature = signature(call)
        val actionFamily = RecoveryPolicy.actionFamily(call)

        if (outcomeUnknown) {
            val event = FailureEvents.fromToolOutcome(
                call = call,
                errorCode = errorCode,
                suppliedClass = failureClass,
                error = error,
                stderr = stderr,
                stdout = stdout,
                retryable = retryable,
                dependency = dependency,
                outcomeUnknown = true,
                attempt = (state.actionFamilyFailures[actionFamily] ?: 0) + 1
            )
            val reason = "Tool outcome unknown after transport failure. Inspect current state before replaying ${call.tool}."
            return ToolTransition(
                state = fail(state, reason),
                stopReason = reason,
                failureEvent = event
            )
        }
        var pythonFailures = state.pythonFailures
        val repeatedFailures = state.repeatedToolFailures.toMutableMap()
        val familyFailures = state.actionFamilyFailures.toMutableMap()
        val recoveredFromPythonFailure =
            ok && call.tool.startsWith("python.") && state.pythonFailures > 0
        val recoveredFromRepeatedToolFailure =
            ok && (state.repeatedToolFailures[signature] ?: 0) > 0
        val recovered = recoveredFromPythonFailure || recoveredFromRepeatedToolFailure

        var failureEvent: FailureEvent? = null
        if (ok) {
            repeatedFailures.remove(signature)
            if (call.tool.startsWith("python.")) pythonFailures = 0
        } else {
            val count = (repeatedFailures[signature] ?: 0) + 1
            repeatedFailures[signature] = count
            familyFailures[actionFamily] = (familyFailures[actionFamily] ?: 0) + 1
            failureEvent = FailureEvents.fromToolOutcome(
                call = call,
                errorCode = errorCode,
                suppliedClass = failureClass,
                error = error,
                stderr = stderr,
                stdout = stdout,
                retryable = retryable,
                dependency = dependency,
                outcomeUnknown = false,
                attempt = familyFailures[actionFamily] ?: 1
            )
            if (count > budget.maxIdenticalToolFailures) {
                val reason = "Identical tool failure repeated $count times: ${call.tool}"
                return ToolTransition(
                    state = fail(state.copy(task = state.task.copy(kernel = ContextKernel.record(
                        state.task.kernel, call, false, error ?: stderr))), reason),
                    stopReason = reason,
                    failureEvent = failureEvent
                )
            }
            if (call.tool.startsWith("python.")) {
                pythonFailures++
                if (pythonFailures > budget.maxPythonFailures) {
                    val reason = "Python failure budget exceeded (${budget.maxPythonFailures})"
                    return ToolTransition(
                        state = fail(state.copy(task = state.task.copy(kernel = ContextKernel.record(
                            state.task.kernel, call, false, error ?: stderr))), reason),
                        stopReason = reason,
                        failureEvent = failureEvent
                    )
                }
            }
        }

        var verificationRequired = state.verificationRequired
        var verificationReason = state.verificationReason
        val pendingPythonPaths = state.pendingPythonPaths.toMutableSet()
        if (ok && call.tool in setOf("file.write", "file.patch")) {
            val path = normalizePath(call.args["path"].orEmpty())
            if (path.endsWith(".py", ignoreCase = true)) {
                pendingPythonPaths += path
                verificationRequired = true
            }
        }
        if (ok && call.tool in setOf("python.syntax_check", "python.run")) {
            // A successful unrelated script proves nothing about the other
            // files changed by this task.
            pendingPythonPaths.remove(
                normalizePath(
                    call.args["script"].orEmpty()
                )
            )
            if (state.pendingPythonPaths.isNotEmpty()) {
                verificationRequired =
                    pendingPythonPaths.isNotEmpty()
            }
        }
        if (
            ok &&
            ToolRegistry.canonicalize(call.tool) ==
            "python.tests" &&
            EvidenceProjectApplicationPolicy
                .isFullProjectTestArgs(
                    call.args
                )
        ) {
            val cwd = call.args["cwd"]
                .orEmpty()
                .trim()
            pendingPythonPaths.removeAll { path ->
                EvidenceProjectApplicationPolicy
                    .testScopeContainsTarget(
                        cwd = cwd,
                        target = path
                    )
            }
            if (state.pendingPythonPaths.isNotEmpty()) {
                verificationRequired =
                    pendingPythonPaths.isNotEmpty()
            }
        }
        if (pendingPythonPaths.isNotEmpty()) {
            verificationReason = "Python code changed; successfully run python.syntax_check or python.run for each pending file: " +
                pendingPythonPaths.sorted().joinToString(", ")
        } else if (!verificationRequired) {
            verificationReason = null
        }

        val resultText = buildString {
            append("ok=").append(ok)
            if (!errorCode.isNullOrBlank()) append(" code=").append(errorCode.take(120))
            if (!failureClass.isNullOrBlank()) append(" class=").append(failureClass.take(120))
            if (!dependency.isNullOrBlank()) append(" dependency=").append(dependency.take(120))
            if (retryable != null) append(" retryable=").append(retryable)
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
            kernel = ContextKernel.record(state.task.kernel, call, ok, resultText).copy(pendingVerification = pendingPythonPaths),
            errors = if (ok) state.task.errors else (state.task.errors + resultText).takeLast(8)
        )

        val semanticSpent = state.semanticRecoverySpent + if (ok) 0 else 1
        val advisorHint = RecoveryAdvisor.suggest(
            task = state.task,
            call = call,
            ok = ok,
            stdout = stdout,
            stderr = stderr,
            error = error
        )
        val nextState = state.copy(
            task = nextTask,
            toolUsed = true,
            protocolRetries = 0,
            // modelFailures is a consecutive model-runtime counter. A valid model
            // reply resets it in interpretTool(); tool success/failure must not
            // redefine model runtime health.
            modelFailures = state.modelFailures,
            semanticRecoverySpent = semanticSpent,
            actionFamilyFailures = familyFailures,
            pythonFailures = pythonFailures,
            repeatedToolFailures = repeatedFailures,
            verificationRequired = verificationRequired,
            verificationReason = verificationReason,
            pendingPythonPaths = pendingPythonPaths,
            visualEvidenceReady = state.visualEvidenceReady ||
                (ok && ToolRegistry.canonicalize(call.tool) == "image.search"),
            recoveryHint = advisorHint
        )

        if (ok) return ToolTransition(state = nextState.copy(recoveryHint = null))

        val event = requireNotNull(failureEvent) {
            "Failed tool transition must carry a FailureEvent"
        }
        val familyCount = familyFailures[actionFamily] ?: 0
        val recoveryState = RecoveryState(
            familyFailures = familyCount,
            semanticRecoverySpent = semanticSpent,
            maxFamilyFailures = actionFamilyFailureLimit(nextState, call),
            maxSemanticRecoveries = semanticRecoveryLimit(nextState, call)
        )
        val decision = ConstitutionKernel.decide(
            event = event,
            state = recoveryState
        )
        val reflexCandidates = ReflexKernel.candidates(
            event = event,
            state = recoveryState
        )

        fun combinedHint(policy: String): String =
            listOf(policy, advisorHint)
                .filterNotNull()
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" ")
                .take(2_000)

        return when (decision) {
            is RecoveryDecision.RetryVariant ->
                ToolTransition(
                    state = nextState.copy(recoveryHint = combinedHint(decision.guidance)),
                    failureEvent = event,
                    reflexCandidates = reflexCandidates
                )

            is RecoveryDecision.TryAlternative ->
                ToolTransition(
                    state = nextState.copy(recoveryHint = combinedHint(decision.guidance)),
                    failureEvent = event,
                    reflexCandidates = reflexCandidates
                )

            is RecoveryDecision.DegradePartial -> {
                val report = buildString {
                    append("Частково виконано. ")
                    append(decision.reason)
                    append(" Остання помилка: ")
                    append((error ?: stderr.ifBlank { stdout }).take(900))
                }
                ToolTransition(
                    state = nextState.copy(
                        recoveryHint = null,
                        task = nextState.task.copy(
                            status = TaskStatus.PARTIAL,
                            lastResult = report
                        )
                    ),
                    partialReason = report,
                    failureEvent = event,
                    reflexCandidates = reflexCandidates
                )
            }

            is RecoveryDecision.Stop -> {
                val reason = decision.reason
                ToolTransition(
                    state = fail(nextState.copy(recoveryHint = null), reason),
                    stopReason = reason,
                    failureEvent = event,
                    reflexCandidates = reflexCandidates
                )
            }
        }
    }

    fun dynamicContext(
        state: AgentControlState,
        relevantMemory: List<String> = emptyList(),
        constitutionalGuidance: List<String> = emptyList(),
        verifiedEvidence: List<String> = emptyList()
    ): String {
        return ContextBuilder(
            maxMemoryItems = 6,
            maxChars = 4_500
        ).build(
            task = state.task,
            project = null,
            relevantMemory = relevantMemory,
            constitutionalGuidance = constitutionalGuidance,
            verifiedEvidence = verifiedEvidence,
            allowedTools = null,
            plan = state.plan,
            verificationRequirement = state.verificationReason,
            intent = state.intent,
            intentConfidence = state.intentConfidence,
            recommendedTools = state.recommendedTools,
            intentGuidance = state.intentGuidance,
            recoveryGuidance = state.recoveryHint,
            kernelContext = if (state.task.kernel.observed > 0 || state.task.kernel.inFlight != null)
                ContextKernel.capsule(state.task.kernel) else null
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

    private fun protocolRetry(
        state: AgentControlState,
        problem: String,
        observedEvent: FailureEvent? = null
    ): ControllerInstruction {
        val retries = state.protocolRetries + 1
        val event = (observedEvent ?: FailureEvent(
            source = FailureSource.PROTOCOL,
            failureClass = FailureClass.INVALID_INPUT,
            retryable = true,
            effectClass = EffectClass.NONE,
            dependency = "model-protocol",
            evidence = problem.take(8_000),
            actionFamily = null,
            attempt = retries,
            outcomeUnknown = false,
            code = "CONTROLLER_PROTOCOL"
        )).copy(attempt = retries)

        val decision = ConstitutionKernel.decide(
            event = event,
            state = RecoveryState(
                familyFailures = retries,
                semanticRecoverySpent = 0,
                maxFamilyFailures = budget.maxModelRetries,
                maxSemanticRecoveries = budget.maxSemanticRecoveries
            )
        )
        val next = state.copy(
            protocolRetries = retries,
            task = state.task.copy(
                status = TaskStatus.WAITING_MODEL
            )
        )

        return when (decision) {
            is RecoveryDecision.RetryVariant ->
                ControllerInstruction.AskModelAgain(
                    feedback = protocolRepairFeedback(
                        problem = problem,
                        guidance = decision.guidance,
                        retry = retries
                    ),
                    state = next
                )

            is RecoveryDecision.TryAlternative ->
                ControllerInstruction.AskModelAgain(
                    feedback = protocolRepairFeedback(
                        problem = problem,
                        guidance = decision.guidance,
                        retry = retries
                    ),
                    state = next
                )

            is RecoveryDecision.DegradePartial ->
                ControllerInstruction.Stop(
                    "Model protocol failed repeatedly: $problem",
                    fail(next, problem)
                )

            is RecoveryDecision.Stop ->
                ControllerInstruction.Stop(
                    "Model protocol failed repeatedly: $problem",
                    fail(next, problem)
                )
        }
    }

    private fun protocolRepairFeedback(
        problem: String,
        guidance: String,
        retry: Int
    ): String = buildString {
        appendLine("PROTOCOL_REPAIR_MODE retry=$retry")
        appendLine("The previous model output was not executable and NOTHING from it was run.")
        appendLine("Return EXACTLY ONE JSON object and no prose, markdown or extra JSON.")
        appendLine("Allowed roots:")
        appendLine("{\"tool\":\"registered.tool\",\"args\":{}}")
        appendLine("{\"done\":true,\"summary\":\"...\"}")
        appendLine("{\"partial\":true,\"summary\":\"...\"}")
        appendLine("{\"reply\":\"...\"}")
        appendLine("Do not echo the malformed output. Do not invent TOOL_RESULT.")
        appendLine("Continue the SAME task from verified state only.")
        appendLine("Problem: " + problem.take(1_200))
        guidance.takeIf { it.isNotBlank() }?.let {
            append("Recovery guidance: ")
            append(it.take(500))
        }
    }.take(2_800)

    private fun actionFamilyFailureLimit(
        state: AgentControlState,
        call: AgentDecision.ToolCall
    ): Int =
        if (
            state.intent == TaskIntent.PUBLIC_WEB &&
            ToolRegistry.canonicalize(call.tool) == "web.read"
        ) {
            budget.maxWebSourceFailures
        } else {
            budget.maxActionFamilyFailures
        }

    private fun semanticRecoveryLimit(
        state: AgentControlState,
        call: AgentDecision.ToolCall
    ): Int =
        if (
            state.intent == TaskIntent.PUBLIC_WEB &&
            ToolRegistry.canonicalize(call.tool) == "web.read"
        ) {
            budget.maxWebSourceFailures
        } else {
            budget.maxSemanticRecoveries
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

    private fun normalizePath(path: String): String =
        java.io.File(path.trim()).toPath().normalize().toString()
}
