package com.lumena.android.collaboration

import android.content.Context
import com.lumena.android.BuildConfig
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
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
    fun scope(project: WorkspaceProject?, taskId: String, sessionId: String, modelId: String,
              channel: String, bridgeUrl: String, token: String): CollaborationScope = CollaborationScope(
        project?.id ?: "UNASSIGNED", project?.root ?: ".",
        project?.let(CollaborationProtocol::goalRevision) ?: CollaborationProtocol.hash("UNASSIGNED"),
        taskId.take(240), sessionId.take(240), modelId.take(160), channel,
        // Installation-wide writer lease: changing URL/token must not bypass UNKNOWN.
        CollaborationProtocol.hash("installation-local-bridge"),
        BuildConfig.VERSION_NAME, BuildConfig.SOURCE_COMMIT)

    fun forTask(c: Context, task: TaskState, model: String, url: String, token: String, delegate: ToolExecutor): ToolExecutor {
        if (!enabled(c) && !c.getDatabasePath(CollaborationLedger.DATABASE).exists()) return delegate
        val project = runCatching { ProjectWorkspaceStore.project(c, task.projectId) }.getOrNull()
            ?.takeIf { it.root == task.projectRoot }
        return executor(c, scope(project, task.id, "local", model, "LOCAL", url, token), delegate)
    }

    fun executor(c: Context, scope: CollaborationScope, delegate: ToolExecutor, delivery: String? = null): ToolExecutor = object : ToolExecutor {
        override suspend fun execute(toolRequest: ToolRequest): ToolResult {
            if (toolRequest.tool in setOf("health", "process.status", "python.preview")) return delegate.execute(toolRequest)
            if (!enabled(c)) {
                // Disabling capture cannot clear a previously recorded UNKNOWN writer barrier.
                if (c.getDatabasePath(CollaborationLedger.DATABASE).exists() &&
                    ToolRegistry.get(toolRequest.tool)?.risk != ToolRisk.READ_ONLY) {
                    val safe = try { withContext(Dispatchers.IO) {
                        val all = CollaborationLedger(c).entries()
                        CollaborationProtocol.unresolved(all, scope.bridgeBinding).isEmpty()
                    } } catch (_: Exception) { false }
                    if (!safe) return CollaborationLedger.blocked("Unresolved or unreadable collaboration writer; inspect local journal", false)
                }
                return delegate.execute(toolRequest)
            }
            return CollaborationLedger(c).execute(CollaborationProtocol.attributedScope(scope, toolRequest), toolRequest, delegate,
                delivery ?: toolRequest.requestId ?: UUID.randomUUID().toString())
        }
    }
}
