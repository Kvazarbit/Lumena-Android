package com.lumena.android.collaboration

import android.content.Context
import com.lumena.android.BuildConfig
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.lumena.android.settings.ProjectWorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Opt-in capture. The ordinary chat never receives this private context automatically. */
object CollaborationRuntime {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE)
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("capture", false)
    fun enable(c: Context, enabled: Boolean) { check(prefs(c).edit().putBoolean("capture", enabled).commit()) }
    fun companionProject(c: Context): String? = prefs(c).getString("companion_project", null)
    fun selectCompanionProject(c: Context, id: String?) {
        require(id == null || ProjectWorkspaceStore.project(c, id) != null)
        check(prefs(c).edit().putString("companion_project", id).commit())
    }
    /** Owner confirmed that this exact Ollama route is a local model, not a cloud proxy. Embedded needs no attestation. */
    fun localAttested(c: Context, route: String): Boolean = route.isNotBlank() && prefs(c).getString("local_route", null) == route
    fun attestLocal(c: Context, route: String, attested: Boolean) {
        if (route.isNotBlank()) check(prefs(c).edit().putString("local_route", route.takeIf { attested }).commit())
    }
    fun route(backend: String, url: String, model: String): String = "$backend|$url|$model"
    /** Consent stays on this installation; a portable project JSON cannot authorize egress. */
    fun remoteAllowed(c: Context, project: WorkspaceProject): Boolean = project.allowRemoteContext &&
        prefs(c).getBoolean("remote_" + CollaborationProtocol.goalRevision(project), false)
    fun allowRemoteFromOwner(c: Context, project: WorkspaceProject, allowed: Boolean) {
        check(prefs(c).edit().putBoolean("remote_" + CollaborationProtocol.goalRevision(project), allowed).commit())
    }

    /** Receipt card for the acting model; null for cloud routes, other projects or an empty journal. Never creates the journal. */
    suspend fun cardFor(c: Context, task: TaskState, backend: String, url: String, model: String, bridge: ToolExecutor?): String? =
        CollaborationStateCard.forLocalModel(backend, url, model, localAttested(c, route(backend, url, model)), task,
            project = { withContext(Dispatchers.IO) { ProjectWorkspaceStore.project(c, task.projectId) } },
            appSource = BuildConfig.SOURCE_COMMIT,
            entries = { withContext(Dispatchers.IO) {
                if (CollaborationLedger.exists(c)) CollaborationLedger(c).entries() else emptyList()
            } },
            observe = { root -> bridge?.let { CollaborationStateCard.observe(it, root) } ?: SourceObservation.UNKNOWN })

    fun scope(project: WorkspaceProject?, taskId: String, sessionId: String, modelId: String,
              channel: String, bridgeUrl: String, token: String): CollaborationScope = CollaborationScope(
        project?.id ?: "UNASSIGNED", project?.root ?: ".",
        project?.let(CollaborationProtocol::goalRevision) ?: CollaborationProtocol.hash("UNASSIGNED"),
        taskId.take(240), sessionId.take(240), modelId.take(160), channel,
        // Installation-wide writer lease: changing URL/token must not bypass UNKNOWN.
        CollaborationProtocol.hash("installation-local-bridge"),
        BuildConfig.VERSION_NAME, BuildConfig.SOURCE_COMMIT)

    fun forTask(c: Context, task: TaskState, model: String, url: String, token: String, delegate: ToolExecutor): ToolExecutor {
        val project = runCatching { ProjectWorkspaceStore.project(c, task.projectId) }.getOrNull()
            ?.takeIf { it.root == task.projectRoot }
        val journal = if (!enabled(c) && !CollaborationLedger.exists(c)) delegate
            else executor(c, scope(project, task.id, "local", model, "LOCAL", url, token), delegate)
        return com.lumena.android.agent.local.ProjectFilePreconditions.wrap(task, journal, delegate)
    }

    fun executor(c: Context, scope: CollaborationScope, delegate: ToolExecutor, delivery: String? = null): ToolExecutor = object : ToolExecutor {
        override suspend fun execute(toolRequest: ToolRequest): ToolResult {
            if (toolRequest.tool in setOf("health", "process.status", "python.preview")) return delegate.execute(toolRequest)
            if (!enabled(c)) {
                // Disabling capture cannot clear a previously recorded UNKNOWN writer barrier.
                if (CollaborationLedger.exists(c) &&
                    CollaborationProtocol.writer(toolRequest.tool)) {
                    val safe = try { withContext(Dispatchers.IO) {
                        val all = CollaborationLedger(c).entries()
                        CollaborationProtocol.unresolved(all, scope.bridgeBinding).isEmpty()
                    } } catch (_: Exception) { false }
                    if (!safe) return CollaborationLedger.blocked("Unresolved or unreadable collaboration writer; inspect local journal", false)
                }
                return delegate.execute(toolRequest)
            }
            return CollaborationLedger(c).execute(CollaborationProtocol.attributedScope(scope, toolRequest), toolRequest, delegate,
                delivery ?: toolRequest.requestId ?: UUID.randomUUID().toString()) { root -> CollaborationStateCard.observe(delegate, root) }
        }
    }
}
