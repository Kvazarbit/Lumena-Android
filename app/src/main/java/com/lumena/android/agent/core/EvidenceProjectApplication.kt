package com.lumena.android.agent.core

import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import java.nio.file.Paths
import java.security.MessageDigest

data class EvidenceApplicationUpdate(
    val state: EvidenceGraphState,
    val accepted: Boolean,
    val reason: String? = null,
    val bindingId: String? = null
)

/**
 * Links already-verified evidence claims to concrete project work without
 * granting execution authority.
 *
 * Binding a claim to a target is advisory metadata only. Actual mutations still
 * require the normal ToolRegistry -> ToolGate -> confirmation path. Outcomes
 * advance only after successful local TOOL_RESULT evidence.
 */
object EvidenceProjectApplicationPolicy {
    private val artifactTools = setOf(
        "project.create",
        "dir.create",
        "file.write",
        "file.patch"
    )

    private val verificationTools = setOf(
        "python.syntax_check",
        "python.tests"
    )

    fun bind(
        state: EvidenceGraphState,
        claimKey: String,
        projectId: String,
        target: String,
        now: Long,
        staleAfterMs: Long =
            EvidenceGraphReducer.DEFAULT_STALE_AFTER_MS
    ): EvidenceApplicationUpdate {
        if (
            claimKey.isBlank() ||
            projectId.isBlank() ||
            target.isBlank() ||
            now <= 0
        ) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "INVALID_BINDING"
            )
        }

        val claim = state.claims.firstOrNull {
            it.claimKey.trim() == claimKey.trim()
        } ?: return EvidenceApplicationUpdate(
            state = state,
            accepted = false,
            reason = "CLAIM_NOT_FOUND"
        )

        val effective =
            EvidenceGraphReducer.effectiveVerificationState(
                claim = claim,
                now = now,
                staleAfterMs = staleAfterMs
            )

        if (
            effective !in setOf(
                EvidenceVerificationState.RETRIEVED,
                EvidenceVerificationState.CORROBORATED
            )
        ) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "CLAIM_NOT_APPLICABLE:$effective"
            )
        }

        if (claim.outcome == EvidenceProjectOutcome.REJECTED) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "CLAIM_REJECTED"
            )
        }

        val normalizedTarget = normalizeTarget(target)
        if (normalizedTarget.isBlank()) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "INVALID_TARGET"
            )
        }

        val bindingId = hash(
            claim.claimKey.trim().lowercase() + "|" +
                projectId.trim().lowercase() + "|" +
                normalizedTarget
        ).take(24)

        val existing = state.applications.firstOrNull {
            it.id == bindingId
        }
        if (existing != null) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = true,
                bindingId = existing.id
            )
        }

        val binding = EvidenceApplicationBinding(
            id = bindingId,
            claimKey = claim.claimKey,
            projectId = projectId.trim().take(160),
            target = normalizedTarget.take(800),
            status = EvidenceApplicationStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )

        return EvidenceApplicationUpdate(
            state = state.copy(
                applications = (
                    state.applications + binding
                    )
                    .distinctBy { it.id }
                    .sortedBy { it.id }
            ),
            accepted = true,
            bindingId = binding.id
        )
    }

    fun observeToolResult(
        state: EvidenceGraphState,
        bindingId: String,
        taskProjectId: String,
        request: ToolRequest,
        result: ToolResult,
        evidenceId: String?,
        now: Long
    ): EvidenceApplicationUpdate {
        val binding = state.applications.firstOrNull {
            it.id == bindingId
        } ?: return EvidenceApplicationUpdate(
            state = state,
            accepted = false,
            reason = "BINDING_NOT_FOUND"
        )

        if (
            taskProjectId.isBlank() ||
            taskProjectId.trim() != binding.projectId
        ) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "PROJECT_SCOPE_MISMATCH",
                bindingId = binding.id
            )
        }

        if (
            !result.ok ||
            result.outcomeUnknown ||
            evidenceId.isNullOrBlank() ||
            now <= 0
        ) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = when {
                    result.outcomeUnknown -> "UNKNOWN_EFFECT"
                    !result.ok -> "TOOL_RESULT_FAILED"
                    evidenceId.isNullOrBlank() -> "MISSING_EVIDENCE_ID"
                    else -> "INVALID_TIMESTAMP"
                },
                bindingId = binding.id
            )
        }

        val canonical = ToolRegistry.canonicalize(request.tool)
        val spec = ToolRegistry.get(canonical)
            ?: return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "UNKNOWN_TOOL",
                bindingId = binding.id
            )

        return when {
            canonical in artifactTools &&
                spec.risk == ToolRisk.MUTATING ->
                applyArtifactResult(
                    state = state,
                    binding = binding,
                    request = request,
                    tool = canonical,
                    evidenceId = evidenceId,
                    now = now
                )

            canonical in verificationTools &&
                spec.risk == ToolRisk.EXECUTABLE ->
                applyVerificationResult(
                    state = state,
                    binding = binding,
                    request = request,
                    tool = canonical,
                    evidenceId = evidenceId,
                    now = now
                )

            else -> EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "TOOL_NOT_PROJECT_PROOF",
                bindingId = binding.id
            )
        }
    }

    private fun applyArtifactResult(
        state: EvidenceGraphState,
        binding: EvidenceApplicationBinding,
        request: ToolRequest,
        tool: String,
        evidenceId: String,
        now: Long
    ): EvidenceApplicationUpdate {
        if (
            binding.status !in setOf(
                EvidenceApplicationStatus.PENDING,
                EvidenceApplicationStatus.APPLIED
            )
        ) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "BINDING_NOT_PENDING",
                bindingId = binding.id
            )
        }

        val actualTarget = mutationTarget(
            tool = tool,
            request = request
        )
        if (
            actualTarget == null ||
            actualTarget != binding.target
        ) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "TARGET_MISMATCH",
                bindingId = binding.id
            )
        }

        val outcome = EvidenceGraphReducer.applyProjectOutcome(
            state = state,
            claimKey = binding.claimKey,
            outcome = EvidenceProjectOutcome.APPLIED_TO_PROJECT,
            proof = EvidenceOutcomeProof(
                kind = EvidenceOutcomeProofKind.PROJECT_ARTIFACT,
                evidenceId = evidenceId,
                at = now
            )
        )

        // The claim-level outcome is an aggregate over all project
        // applications and is intentionally monotonic. A later independent
        // binding can therefore be PENDING even when an earlier binding has
        // already raised the shared claim to VERIFIED_BY_TEST. Applying the
        // new artifact must advance that binding without regressing the
        // aggregate claim back to APPLIED_TO_PROJECT.
        val aggregateState = when {
            outcome.accepted -> outcome.state
            outcome.reason == "OUTCOME_REGRESSION" &&
                state.claims.firstOrNull {
                    it.claimKey == binding.claimKey
                }?.outcome ==
                    EvidenceProjectOutcome.VERIFIED_BY_TEST ->
                state

            else -> {
                return EvidenceApplicationUpdate(
                    state = state,
                    accepted = false,
                    reason = outcome.reason ?: "OUTCOME_REJECTED",
                    bindingId = binding.id
                )
            }
        }

        val updated = binding.copy(
            status = EvidenceApplicationStatus.APPLIED,
            updatedAt = maxOf(binding.updatedAt, now),
            artifactEvidenceIds = (
                binding.artifactEvidenceIds + evidenceId
                )
                .distinct()
                .takeLast(32)
        )

        return EvidenceApplicationUpdate(
            state = aggregateState.copy(
                applications = replaceBinding(
                    aggregateState.applications,
                    updated
                )
            ),
            accepted = true,
            bindingId = binding.id
        )
    }

    private fun applyVerificationResult(
        state: EvidenceGraphState,
        binding: EvidenceApplicationBinding,
        request: ToolRequest,
        tool: String,
        evidenceId: String,
        now: Long
    ): EvidenceApplicationUpdate {
        if (
            binding.status !in setOf(
                EvidenceApplicationStatus.APPLIED,
                EvidenceApplicationStatus.VERIFIED
            )
        ) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "VERIFY_BEFORE_APPLY",
                bindingId = binding.id
            )
        }

        if (
            tool == "python.syntax_check" &&
            normalizeTarget(
                request.args["script"].orEmpty()
            ) != binding.target
        ) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = "TARGET_MISMATCH",
                bindingId = binding.id
            )
        }

        if (tool == "python.tests") {
            val cwd = request.args["cwd"].orEmpty().trim()
            if (cwd.isBlank()) {
                return EvidenceApplicationUpdate(
                    state = state,
                    accepted = false,
                    reason = "MISSING_TEST_SCOPE",
                    bindingId = binding.id
                )
            }
            if (!isFullProjectTestRequest(request)) {
                return EvidenceApplicationUpdate(
                    state = state,
                    accepted = false,
                    reason = "SELECTED_TEST_SCOPE",
                    bindingId = binding.id
                )
            }
            if (!testScopeContainsTarget(cwd, binding.target)) {
                return EvidenceApplicationUpdate(
                    state = state,
                    accepted = false,
                    reason = "TEST_SCOPE_MISMATCH",
                    bindingId = binding.id
                )
            }
        }

        val outcome = EvidenceGraphReducer.applyProjectOutcome(
            state = state,
            claimKey = binding.claimKey,
            outcome = EvidenceProjectOutcome.VERIFIED_BY_TEST,
            proof = EvidenceOutcomeProof(
                kind = EvidenceOutcomeProofKind.PROJECT_TEST,
                evidenceId = evidenceId,
                at = now
            )
        )
        if (!outcome.accepted) {
            return EvidenceApplicationUpdate(
                state = state,
                accepted = false,
                reason = outcome.reason ?: "OUTCOME_REJECTED",
                bindingId = binding.id
            )
        }

        val updated = binding.copy(
            status = EvidenceApplicationStatus.VERIFIED,
            updatedAt = maxOf(binding.updatedAt, now),
            testEvidenceIds = (
                binding.testEvidenceIds + evidenceId
                )
                .distinct()
                .takeLast(32)
        )

        return EvidenceApplicationUpdate(
            state = outcome.state.copy(
                applications = replaceBinding(
                    outcome.state.applications,
                    updated
                )
            ),
            accepted = true,
            bindingId = binding.id
        )
    }

    private fun mutationTarget(
        tool: String,
        request: ToolRequest
    ): String? {
        val raw = when (tool) {
            "project.create" -> request.args["name"]
            "dir.create",
            "file.write",
            "file.patch" -> request.args["path"]
            else -> null
        } ?: return null

        return normalizeTarget(raw)
            .takeIf { it.isNotBlank() }
    }

    internal fun isFullProjectTestRequest(
        request: ToolRequest
    ): Boolean =
        ToolRegistry.canonicalize(request.tool) ==
            "python.tests" &&
            isFullProjectTestArgs(request.args)

    internal fun isFullProjectTestArgs(
        args: Map<String, String>
    ): Boolean {
        val argv = args["argv"]
            .orEmpty()
            .trim()

        // Bridge default is "-q". Anything else may contain a file/node
        // selector, so it cannot prove every changed Python target in cwd.
        return argv.isBlank() || argv == "-q"
    }

    internal fun testScopeContainsTarget(
        cwd: String,
        target: String
    ): Boolean {
        val normalizedCwd = normalizeTarget(cwd)
        val normalizedTarget = normalizeTarget(target)
        if (
            normalizedCwd.isBlank() ||
            normalizedTarget.isBlank()
        ) {
            return false
        }

        return runCatching {
            val cwdPath = Paths.get(normalizedCwd).normalize()
            val targetPath = Paths.get(normalizedTarget).normalize()

            if (normalizedCwd == "." || cwdPath.toString().isBlank()) {
                true
            } else {
                targetPath.startsWith(cwdPath)
            }
        }.getOrDefault(false)
    }

    internal fun normalizeTarget(
        raw: String
    ): String {
        val clean = raw
            .replace('\u0000', ' ')
            .trim()
            .replace('\\', '/')
        if (clean.isBlank()) return ""

        if (
            clean.startsWith("http://", ignoreCase = true) ||
            clean.startsWith("https://", ignoreCase = true)
        ) {
            return clean
        }

        return runCatching {
            Paths.get(clean)
                .normalize()
                .toString()
                .replace('\\', '/')
        }.getOrDefault(clean)
    }

    private fun replaceBinding(
        bindings: List<EvidenceApplicationBinding>,
        replacement: EvidenceApplicationBinding
    ): List<EvidenceApplicationBinding> =
        (
            bindings.filterNot {
                it.id == replacement.id
            } + replacement
        ).sortedBy { it.id }

    private fun hash(
        value: String
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") {
                "%02x".format(it)
            }
}

data class EvidenceAutomaticBindingUpdate(
    val state: EvidenceGraphState,
    val bindingIds: List<String> = emptyList()
)

/**
 * Creates advisory evidence->target bindings only after an already-authorized
 * mutation produced a successful known TOOL_RESULT.
 *
 * It never initiates a mutation, grants permission, or crosses project scope.
 */
object EvidenceAutomaticProjectBindingPolicy {
    private const val MAX_BINDINGS_PER_MUTATION = 1

    fun bindForSuccessfulMutation(
        state: EvidenceGraphState,
        projectId: String,
        taskGoal: String,
        request: ToolRequest,
        result: ToolResult,
        now: Long
    ): EvidenceAutomaticBindingUpdate {
        val safeProject = projectId.trim()
        val safeGoal = taskGoal.trim()
        if (
            safeProject.isBlank() ||
            safeGoal.isBlank() ||
            now <= 0 ||
            !result.ok ||
            result.outcomeUnknown
        ) {
            return EvidenceAutomaticBindingUpdate(state)
        }

        val canonical = ToolRegistry.canonicalize(request.tool)
        val spec = ToolRegistry.get(canonical)
        if (spec?.risk != ToolRisk.MUTATING) {
            return EvidenceAutomaticBindingUpdate(state)
        }

        val target = mutationTarget(
            canonical = canonical,
            request = request
        ) ?: return EvidenceAutomaticBindingUpdate(state)

        if (
            !EvidenceProjectApplicationPolicy.testScopeContainsTarget(
                cwd = safeProject,
                target = target
            )
        ) {
            return EvidenceAutomaticBindingUpdate(state)
        }

        val candidates =
            EvidenceGraphReducer.relevantClaims(
                state = state,
                query = safeGoal,
                now = now,
                limit = 16
            )
                .asSequence()
                .filter { it.projectId == safeProject }
                .filter {
                    it.outcome != EvidenceProjectOutcome.REJECTED
                }
                .filter { claim ->
                    EvidenceGraphReducer
                        .effectiveVerificationState(
                            claim = claim,
                            now = now
                        ) in setOf(
                            EvidenceVerificationState.RETRIEVED,
                            EvidenceVerificationState.CORROBORATED
                        )
                }
                .take(MAX_BINDINGS_PER_MUTATION)
                .toList()

        if (candidates.isEmpty()) {
            return EvidenceAutomaticBindingUpdate(state)
        }

        var next = state
        val ids = mutableListOf<String>()
        candidates.forEach { claim ->
            val update =
                EvidenceProjectApplicationPolicy.bind(
                    state = next,
                    claimKey = claim.claimKey,
                    projectId = safeProject,
                    target = target,
                    now = now
                )
            if (update.accepted) {
                next = update.state
                update.bindingId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(ids::add)
            }
        }

        return EvidenceAutomaticBindingUpdate(
            state = next,
            bindingIds = ids.distinct().sorted()
        )
    }

    private fun mutationTarget(
        canonical: String,
        request: ToolRequest
    ): String? {
        val raw = when (canonical) {
            "project.create" -> request.args["name"]
            "dir.create",
            "file.write",
            "file.patch" -> request.args["path"]
            else -> null
        } ?: return null

        return EvidenceProjectApplicationPolicy
            .normalizeTarget(raw)
            .takeIf { it.isNotBlank() }
    }
}

/**
 * Pure router that maps a verified project tool result to already-existing
 * evidence bindings. It never creates a binding and therefore cannot turn
 * ordinary tool execution into evidence application by itself.
 */
object EvidenceProjectOutcomeRouter {
    fun matchingBindingIds(
        state: EvidenceGraphState,
        projectId: String,
        request: ToolRequest
    ): List<String> {
        val safeProject = projectId.trim()
        if (safeProject.isBlank()) return emptyList()

        val canonical = ToolRegistry.canonicalize(request.tool)
        val target = when (canonical) {
            "project.create" ->
                request.args["name"]
                    ?.let(EvidenceProjectApplicationPolicy::normalizeTarget)
            "dir.create",
            "file.write",
            "file.patch" ->
                request.args["path"]
                    ?.let(EvidenceProjectApplicationPolicy::normalizeTarget)
            "python.syntax_check" ->
                request.args["script"]
                    ?.let(EvidenceProjectApplicationPolicy::normalizeTarget)
            else -> null
        }

        return state.applications
            .asSequence()
            .filter { it.projectId == safeProject }
            .filter { binding ->
                when (canonical) {
                    "project.create",
                    "dir.create",
                    "file.write",
                    "file.patch",
                    "python.syntax_check" ->
                        !target.isNullOrBlank() &&
                            binding.target == target

                    "python.tests" -> {
                        val cwd = request.args["cwd"].orEmpty()
                        EvidenceProjectApplicationPolicy
                            .isFullProjectTestRequest(request) &&
                            EvidenceProjectApplicationPolicy
                                .testScopeContainsTarget(
                                    cwd = cwd,
                                    target = binding.target
                                )
                    }

                    else -> false
                }
            }
            .map { it.id }
            .distinct()
            .sorted()
            .toList()
    }
}

