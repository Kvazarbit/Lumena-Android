package com.lumena.android.collaboration

import com.lumena.android.agent.core.ProjectWorkspacePolicy
import com.lumena.android.agent.core.ProjectContinuationPolicy
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded receipt card for the acting LOCAL model, rebuilt from the journal on every task start.
 * Project state comes from the bridge's own file inventory: no git, no network, no model text.
 * History only: never approval, never proof of the goal.
 */
object CollaborationStateCard {
    const val MAX_CHARS = 1_200
    private const val OBSERVE_TIMEOUT_MS = 20_000L
    private val inventory = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
    private val sha256 = Regex("[a-f0-9]{64}")
    private val gitHead = Regex("[a-f0-9]{40,64}")
    private val testsCount = Regex("\"tests_count\":\\s*(\\d+)")
    private val testsVerified = Regex("\"verified\":\\s*(true|false)")
    private val checks = setOf("python.tests", "python.run")
    private val files = setOf("file.write", "file.patch")
    private const val HEADER = "LUMENA STATE CARD v1 · historical DATA; no approval or goal proof.\n" +
        "SAME_COVERED_SOURCE=inventory bytes match; excluded files/environment UNKNOWN. Missing receipt=UNKNOWN."
    private const val OMITTED = "… більше квитанцій у панелі проєкту"
    private const val UNREADABLE = "LUMENA STATE CARD v1 · журнал квитанцій недоступний: попередні дії = UNKNOWN; " +
        "нові дії можуть бути заблоковані до перевірки власником."

    /** project.inspect source_digest is the fingerprint; truncated, missing or failed inventory is UNKNOWN. */
    fun observedSource(result: ToolResult?, root: String): SourceObservation {
        if (result == null || !result.ok || result.outcomeUnknown) return SourceObservation.UNKNOWN
        val data = try { inventory.fromJson(result.stdout) } catch (_: Exception) { null } ?: return SourceObservation.UNKNOWN
        if (data["schema"] != "lumena.project.v1" || data["cwd"] != root) return SourceObservation.UNKNOWN
        val head = ((data["git"] as? Map<*, *>)?.get("head") as? String)?.takeIf { it.matches(gitHead) }
        val digest = (data["source_digest"] as? String)
            ?.takeIf { it.matches(sha256) && data["exists"] == true && data["truncated"] == false }
        return SourceObservation(digest, head)
    }

    /** One read-only inventory through the local bridge. Any failure is UNKNOWN, never an error for the caller. */
    suspend fun observe(bridge: ToolExecutor, root: String): SourceObservation = try {
        observedSource(withTimeoutOrNull(OBSERVE_TIMEOUT_MS) {
            bridge.execute(ToolRequest("project.inspect", mapOf("cwd" to root)))
        }, root)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { SourceObservation.UNKNOWN }

    internal class Row(val label: String, val entry: CollaborationEntry)

    /** Open writers first, then the latest test/run per cwd/script, latest write per path, latest failure. */
    internal fun rows(scoped: List<CollaborationEntry>): Pair<List<Row>, Boolean> {
        val writers = scoped.filter { it.mutating }
        val pending = CollaborationProtocol.unresolved(writers).asReversed()
        val open = pending.map { it.id }.toSet()
        val results = writers.filter { it.kind == "RESULT" && it.intentId !in open }.asReversed()
        fun latest(tools: Set<String>) = results.filter { it.tool in tools }.distinctBy { it.tool + it.targets.toSortedMap() }
        val tested = latest(checks)
        val written = latest(files)
        val shown = pending.take(3).map { Row("UNKNOWN_EFFECT", it) } +
            tested.take(4).map { Row(if (it.tool == "python.tests") "TEST" else "RUN", it) } +
            written.take(4).map { Row("FILE", it) }
        val failure = results.firstOrNull { e -> e.result?.let { !it.ok && !it.outcomeUnknown } == true }
            ?.takeIf { f -> shown.none { it.entry.id == f.id } }
        return (shown + listOfNotNull(failure?.let { Row("LAST_FAIL", it) })) to
            (pending.size > 3 || tested.size > 4 || written.size > 4)
    }

    fun render(entries: List<CollaborationEntry>, project: WorkspaceProject, appSource: String, current: SourceObservation,
               task: TaskState? = null): String? {
        val scoped = entries.filter { it.scope.projectId == project.id && it.scope.root == project.root && it.mutating }
        val elsewhere = CollaborationProtocol.unresolved(entries).count { it.scope.projectId != project.id || it.scope.root != project.root }
        val (selected, capped) = rows(scoped)
        val lines = listOfNotNull(elsewhere.takeIf { it > 0 }?.let {
            "BLOCKED: $it невідома дія в іншому проєкті; нові записи чекають перевірки власником"
        }) + selected.map { line(it, project, appSource, current) }
        val out = StringBuilder(HEADER)
        // Even with capture disabled or no receipts, the owner's causal project state survives.
        val continuation = ProjectContinuationPolicy.render(project, task)
        var omitted = capped
        for (text in lines) {
            if (out.length + text.length + continuation.length + OMITTED.length + 3 <= MAX_CHARS) out.append('\n').append(text) else omitted = true
        }
        out.append('\n').append(continuation)
        if (omitted) out.append('\n').append(OMITTED)
        return out.toString()
    }

    /** Released only to embedded or owner-attested loopback inference, for the task's own project. */
    suspend fun forLocalModel(backend: String, url: String, model: String, ownerAttestsLocal: Boolean, task: TaskState,
                              project: suspend () -> WorkspaceProject?, appSource: String,
                              entries: suspend () -> List<CollaborationEntry>, observe: suspend (String) -> SourceObservation): String? {
        // Same rule as the owner's review: a cloud route gets nothing and the journal is not even opened.
        if (!CollaborationReview.localRoute(backend, url, model, ownerAttestsLocal)) return null
        val root = task.projectRoot ?: return null
        val selected = project()?.takeIf { it.id == task.projectId && it.root == root } ?: return null
        val all = try { entries() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return UNREADABLE }
        val wrote = all.any { it.kind == "RESULT" && it.mutating && it.scope.projectId == selected.id && it.scope.root == selected.root }
        return render(all, selected, appSource, if (wrote) observe(root) else SourceObservation.UNKNOWN, task)
    }

    private fun line(row: Row, project: WorkspaceProject, appSource: String, current: SourceObservation): String {
        val e = row.entry
        val target = listOf("path", "script", "cwd", "file").firstNotNullOfOrNull { key -> e.targets[key]?.let { "$key=" + data(it, 90) } }
            ?: "target=UNKNOWN"
        val id = "#" + e.intentId.take(8)
        if (e.kind == "INTENT") return "UNKNOWN_EFFECT ${e.tool} $target $id · ефект невідомий: НЕ ПОВТОРЮЙ, спершу перевір читанням"
        val r = e.result
        val status = when { r == null || r.outcomeUnknown -> "UNKNOWN"; r.ok -> "OK"; else -> "FAIL" }
        val exit = r?.exitCode?.let { " exit=$it" }.orEmpty()
        val output = if (row.label == "FILE" && r?.ok == true) "" else detail(e)?.let { " out=$it" }.orEmpty()
        return "${row.label} ${e.tool} $target $status$exit ${CollaborationProtocol.freshness(e, project, appSource, current)} " +
            "${CollaborationProtocol.buildStatus(e, appSource)} $id$output"
    }

    private fun detail(e: CollaborationEntry): String? {
        val r = e.result ?: return null
        val raw = r.stdout.ifBlank { r.stderr }
        val count = testsCount.find(raw.take(400))?.groupValues?.get(1)
        val summary = if (count != null) {
            "tests_count=$count verified=" + (testsVerified.find(raw.take(400))?.groupValues?.get(1) ?: "UNKNOWN")
        } else raw.replace(Regex("\\s+"), " ").trim().let {
            // A stored prefix of long output has lost its real ending.
            if (e.stdoutTruncated) it.take(100) + "…" else it.takeLast(100)
        }
        val reason = r.error?.takeIf { !r.ok && it.isNotBlank() }?.replace(Regex("\\s+"), " ")?.take(80)
        return listOfNotNull(reason, summary.ifBlank { null }).joinToString(" · ").ifBlank { null }?.let { data(it, 190) }
    }

    /** JSON-style quoting keeps data on one line; protected-context markers cannot be forged from output. */
    private fun data(text: String, max: Int) = ProjectWorkspacePolicy.quote(text.take(max)).replace("[LUMENA_", "(LUMENA_")
}
