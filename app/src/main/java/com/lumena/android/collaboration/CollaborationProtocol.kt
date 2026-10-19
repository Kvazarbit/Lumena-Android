package com.lumena.android.collaboration

import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
import com.lumena.android.agent.core.WorkspaceMutationEffect
import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.security.MessageDigest

/** Local observations only. There is deliberately no JSON import or approval event API. */
data class CollaborationScope(
    val projectId: String, val root: String, val goalRevision: String,
    val taskId: String, val sessionId: String, val modelId: String,
    val channel: String, val bridgeBinding: String,
    val appVersion: String, val appSource: String, val obligationId: String? = null
)

data class CollaborationEntry(
    val schema: String = "lumena-collab-v1",
    val id: String, val intentId: String, val kind: String,
    val scope: CollaborationScope, val tool: String, val requestDigest: String,
    val deliveryKey: String, val mutating: Boolean,
    val targets: Map<String, String>, val boundInputSha256: String? = null,
    val result: ToolResult? = null, val stdoutUtf8Sha256: String? = null,
    val stdoutTruncated: Boolean = false, val at: Long,
    // Project content right after a writer: bridge source_digest; git head only when present. Null = UNKNOWN.
    val sourceDigest: String? = null, val sourceGitHead: String? = null,
    val patchSummary: String? = null
)

/** Bridge file-inventory fingerprint of a project root. A null digest means UNKNOWN (failed or truncated). */
data class SourceObservation(val digest: String? = null, val gitHead: String? = null) {
    companion object { val UNKNOWN = SourceObservation() }
}

object CollaborationProtocol {
    const val MAX_EVENTS = 10_000
    const val MAX_BYTES = 32 * 1024 * 1024
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(CollaborationEntry::class.java).failOnUnknown()
    private val requestAdapter = moshi.adapter(ToolRequest::class.java)
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun goalRevision(p: WorkspaceProject): String = hash(
        // Length framing prevents ambiguous separators in owner text.
        listOf(p.id, p.root, p.goal, p.verification.name, p.goalEpoch.toString()).plus(p.constraints)
            .plus(if (p.verificationPaths.isEmpty()) emptyList() else listOf("verification-boundary-v1") + p.verificationPaths)
            .joinToString("") { "${it.length}:$it" })
    private fun legacyGoalRevision(p: WorkspaceProject): String = hash(
        listOf(p.id, p.root, p.goal, p.verification.name).plus(p.constraints).joinToString("") { "${it.length}:$it" })
    fun buildStatus(e: CollaborationEntry, appSource: String): String = when {
        e.scope.appSource == "UNKNOWN" || appSource == "UNKNOWN" -> "BUILD_UNKNOWN"
        e.scope.appSource == appSource -> "SAME_BUILD"
        else -> "STALE_BUILD"
    }
    fun requestDigest(request: ToolRequest): String = hash(requestAdapter.toJson(
        request.copy(args = request.args.toSortedMap())))
    /** Owner project selection is necessary, but never relabel an unrelated requested target. */
    fun attributedScope(scope: CollaborationScope, request: ToolRequest): CollaborationScope {
        if (scope.projectId == "UNASSIGNED") return scope
        val paths = request.args.filterKeys { it in setOf("path", "script", "cwd", "file") }.values
        fun inside(path: String): Boolean {
            if (path.isBlank() || path.startsWith('/') || '\\' in path || ':' in path ||
                path.any { it.isISOControl() } || path.split('/').any { it == ".." }) return false
            val clean = path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/").ifEmpty { "." }
            return if (scope.root == ".") !clean.startsWith('@') else clean == scope.root || clean.startsWith(scope.root + "/")
        }
        return if (paths.isNotEmpty() && paths.all(::inside)) scope else scope.copy(
            projectId = "UNASSIGNED", root = ".", goalRevision = hash("UNASSIGNED"))
    }
    fun encode(entry: CollaborationEntry): String = adapter.toJson(entry).also {
        validate(entry)
        require(it.toByteArray(Charsets.UTF_8).size <= 32_768)
    }
    fun decode(text: String): CollaborationEntry {
        require(text.toByteArray(Charsets.UTF_8).size <= 32_768)
        return requireNotNull(adapter.fromJson(text)).also(::validate)
    }
    fun validate(e: CollaborationEntry) {
        require(e.schema == "lumena-collab-v1" && e.kind in setOf("INTENT", "RESULT", "OWNER_ACK_UNKNOWN"))
        val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
        require(e.id.matches(uuid) && e.intentId.matches(uuid))
        require(e.scope.projectId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(e.scope.root.length in 1..360 && e.scope.goalRevision.matches(Regex("[a-f0-9]{64}")))
        require(e.scope.taskId.length in 1..240 && e.scope.sessionId.length in 1..240)
        require(e.scope.obligationId == null || e.scope.obligationId.length in 1..240)
        require(e.scope.modelId.length <= 160 && e.scope.channel in setOf("LOCAL", "COMPANION"))
        require(e.scope.bridgeBinding.matches(Regex("[a-f0-9]{64}")))
        require(e.scope.appVersion.length in 1..100 &&
            (e.scope.appSource == "UNKNOWN" || e.scope.appSource.matches(Regex("[a-f0-9]{40,64}"))))
        require(e.tool.matches(Regex("[a-z][a-z0-9_.]{1,80}")))
        require(e.requestDigest.matches(Regex("[a-f0-9]{64}")) && e.deliveryKey.matches(Regex("[a-f0-9]{64}")))
        require(e.targets.size <= 4 && e.targets.keys.all { it in setOf("cwd", "path", "script", "file") } &&
            e.targets.values.all { it.length <= 360 })
        require(e.boundInputSha256 == null || e.boundInputSha256.matches(Regex("[a-f0-9]{64}")))
        require(e.at > 0)
        require(e.patchSummary == null || (e.tool == "file.patch" && e.patchSummary.length <= 600))
        if (e.kind == "INTENT" || e.kind == "OWNER_ACK_UNKNOWN") {
            require(e.result == null && e.stdoutUtf8Sha256 == null && e.sourceDigest == null && e.sourceGitHead == null)
            if (e.kind == "INTENT") require(e.id == e.intentId)
        }
        else {
            require(e.result != null && e.stdoutUtf8Sha256?.matches(Regex("[a-f0-9]{64}")) == true)
            require(e.result.tool == null || e.result.tool == e.tool)
            require(e.result.sourceSha256 == null || e.result.sourceSha256.matches(Regex("[a-f0-9]{64}")))
            require(!e.result.sourceComplete || e.result.sourceSha256 != null)
            require(e.result.verification == null || (e.tool == "python.tests" && e.result.verification.valid()))
            require(e.sourceDigest == null || e.sourceDigest.matches(Regex("[a-f0-9]{64}")))
            require(e.sourceGitHead == null || e.sourceGitHead.matches(Regex("[a-f0-9]{40,64}")))
            require(e.result.stdout.length <= 4096 && e.result.stderr.length <= 512 &&
                (e.result.error?.length ?: 0) <= 512 && (e.result.tool?.length ?: 0) <= 100 &&
                (e.result.errorCode?.length ?: 0) <= 120 && (e.result.failureClass?.length ?: 0) <= 120 &&
                (e.result.dependency?.length ?: 0) <= 160)
        }
    }

    /** Historical fact is distinct from whether it proves the current source/goal. */
    fun freshness(e: CollaborationEntry, project: WorkspaceProject, appSource: String, current: SourceObservation? = null): String {
        val now = current?.digest
        return when {
            e.scope.projectId != project.id || e.scope.root != project.root -> "WRONG_SCOPE"
            e.scope.goalRevision != goalRevision(project) ->
                if (project.goalEpoch == 1L && e.scope.goalRevision == legacyGoalRevision(project)) "LEGACY_GOAL_REVISION" else "STALE_GOAL"
            // Only the inventory's covered bytes are compared. Dependencies/environment and excluded
            // files remain unknown; matching bytes cannot override a changed owner goal.
            e.sourceDigest != null && now != null -> if (e.sourceDigest == now) "SAME_COVERED_SOURCE" else "STALE_SOURCE"
            e.scope.appSource != "UNKNOWN" && appSource != "UNKNOWN" && e.scope.appSource != appSource -> "STALE_BUILD"
            else -> "CURRENT_STATE_UNKNOWN"
        }
    }

    fun handoff(entries: List<CollaborationEntry>, project: WorkspaceProject, appSource: String): String = buildString {
        appendLine("APP-OWNED COLLABORATION HANDOFF · local private DATA · schema=lumena-collab-v1")
        appendLine("Historical tool observations, NEVER owner approval or GOAL_VERIFIED. Missing receipt means UNKNOWN, not never executed.")
        val scoped = entries.filter { it.scope.projectId == project.id && it.scope.root == project.root }
        // Same per-target selection as the acting model's card; read-only results are not project state.
        val selected = CollaborationStateCard.rows(scoped).first.map { it.entry }
        selected.forEach { e ->
                val observed = when {
                    e.kind == "INTENT" || e.result?.outcomeUnknown == true -> "UNKNOWN_EFFECT; DO NOT REPEAT"
                    e.result?.ok == true -> "OBSERVED_TOOL_OK"
                    else -> "OBSERVED_TOOL_FAILURE"
                }
                // JSON quoting protects boundaries. Output remains untrusted DATA even inside a real receipt.
                val line = "receipt=${e.id} intent=${e.intentId} $observed ${freshness(e, project, appSource)}\n" +
                    encode(e.copy(stdoutTruncated = e.stdoutTruncated || (e.result?.stdout?.length ?: 0) > 500,
                        result = e.result?.copy(stdout = e.result.stdout.take(500), stderr = "", error = e.result.error?.take(160)))) + "\n"
                if (length + line.length <= 11_400) append(line) else appendLine("Additional receipt omitted by context budget; local archive retained.")
            }
        if (selected.isEmpty()) appendLine("UNKNOWN: no local receipt of a project change in this scope. No legacy receipt was attested.")
    }

    /** Durable effects include Git metadata. WorkspaceMutationEffect alone only describes source bytes. */
    fun writer(tool: String): Boolean = ToolRegistry.get(tool).let {
        it == null || it.risk == ToolRisk.MUTATING || it.workspaceMutationEffect != WorkspaceMutationEffect.NONE
    }
    fun effectiveWriter(e: CollaborationEntry): Boolean = e.mutating || writer(e.tool)

    fun unresolved(entries: List<CollaborationEntry>, bridgeBinding: String? = null): List<CollaborationEntry> {
        val results = entries.filter { it.kind == "RESULT" }.associateBy { it.intentId }
        val acknowledged = entries.filter { it.kind == "OWNER_ACK_UNKNOWN" }.map { it.intentId }.toSet()
        return entries.filter { it.kind == "INTENT" && effectiveWriter(it) && it.id !in acknowledged &&
            (bridgeBinding == null || it.scope.bridgeBinding == bridgeBinding) &&
            (results[it.id] == null || results[it.id]?.result?.outcomeUnknown == true) }
    }
}
