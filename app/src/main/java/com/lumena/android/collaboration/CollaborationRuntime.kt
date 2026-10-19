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
    fun localAttested(c: Context, route: String): Boolean = route.isNotBlank() &&
        (prefs(c).getBoolean("local_" + CollaborationProtocol.hash(route), false) || prefs(c).getString("local_route", null) == route)
    fun attestLocal(c: Context, route: String, attested: Boolean) {
        if (route.isNotBlank()) {
            val edit = prefs(c).edit().putBoolean("local_" + CollaborationProtocol.hash(route), attested)
            if (prefs(c).getString("local_route", null) == route) edit.remove("local_route")
            check(edit.commit())
        }
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

    suspend fun completionProblem(c: Context, task: TaskState, text: String, bridge: ToolExecutor?): String? {
        if (task.projectId == null || !enabled(c)) return null
        if (ClaimCheck.extract(text).none { it.kind == ClaimCheck.Kind.PASS }) return null
        return try {
            val project = withContext(Dispatchers.IO) { ProjectWorkspaceStore.project(c, task.projectId) }
                ?: return "DONE відхилено: проєкт недоступний."
            val current = bridge?.let { CollaborationStateCard.observe(it, project.root) } ?: SourceObservation.UNKNOWN
            val entries = withContext(Dispatchers.IO) { CollaborationLedger(c).entries().filter { it.scope.taskId == task.id } }
            CompletionClaimReview.problem(text, entries, project, BuildConfig.SOURCE_COMMIT, current)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { "DONE відхилено: журнал перевірки недоступний. Зобов'язання залишається відкритим." }
    }

    fun forTask(c: Context, task: TaskState, model: String, url: String, token: String, delegate: ToolExecutor,
                privateProjectIds: List<String> = emptyList()): ToolExecutor {
        val project = runCatching { ProjectWorkspaceStore.project(c, task.projectId) }.getOrNull()
            ?.takeIf { it.root == task.projectRoot }
        if (task.projectRoot != null && (project == null || task.projectGoalEpoch != project.goalEpoch)) {
            return object : ToolExecutor {
                override suspend fun execute(toolRequest: ToolRequest) = CollaborationLedger.blocked(
                    "PROJECT_GOAL_CHANGED: версія мети задачі відсутня або змінилася. Почни новий запит у проєкті; старий дозвіл не переноситься.", false)
            }
        }
        // The same inference boundary also applies with journal capture disabled.
        val guarded = executor(c, scope(project, task.id, "local", model, "LOCAL", url, token)
            .copy(obligationId = task.projectObligationId ?: task.id), delegate,
            transportUrl = url, privateProjectIds = privateProjectIds)
        return com.lumena.android.agent.local.ProjectFilePreconditions.wrap(task, guarded, delegate)
    }

    fun executor(c: Context, scope: CollaborationScope, delegate: ToolExecutor, delivery: String? = null,
                 transportUrl: String = "", privateProjectIds: List<String> = emptyList()): ToolExecutor = object : ToolExecutor {
        override suspend fun execute(incoming: ToolRequest): ToolResult {
            // The model cannot choose which tests/helpers are considered immutable.
            val toolRequest = if (incoming.tool == "python.tests") {
                val boundary = withContext(Dispatchers.IO) {
                    ProjectWorkspaceStore.project(c, scope.projectId)?.verificationPaths.orEmpty()
                }
                incoming.copy(args = incoming.args + ("verification_paths" to org.json.JSONArray(boundary).toString()))
            } else incoming
            if (CollaborationProtocol.writer(toolRequest.tool)) {
                val blocked = try { withContext(Dispatchers.IO) { ImportedContextArchive.blocked(c, toolRequest) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { true }
                if (blocked) return CollaborationLedger.blocked("ARCHIVED_UNKNOWN: перевір невідомі наслідки в імпортованому архіві й підтвердь огляд у Проєктах. Читання доступне.", false)
            }
            val privateIds = (privateProjectIds + scope.projectId).filter { it != "UNASSIGNED" }.distinct()
            if (toolRequest.tool == "ollama.generate" && privateIds.isNotEmpty()) {
                val remote = try { withContext(Dispatchers.IO) { privateIds.all { id ->
                    ProjectWorkspaceStore.project(c, id)?.let { remoteAllowed(c, it) } == true
                } } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { false }
                val nestedModel = toolRequest.args["model"].orEmpty()
                val attested = localAttested(c, route("ollama", ProjectToolPrivacy.BRIDGE_OLLAMA, nestedModel))
                if (!ProjectToolPrivacy.inferenceAllowed(transportUrl, nestedModel, attested, remote))
                    return CollaborationLedger.blocked("PROJECT_CONTEXT_PRIVATE: допоміжна модель теж потребує підтвердженого локального маршруту або дозволу зовнішньої передачі.", false)
            }
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
