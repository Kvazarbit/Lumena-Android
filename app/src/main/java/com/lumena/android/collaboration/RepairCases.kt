package com.lumena.android.collaboration

import com.lumena.android.agent.core.ProjectWorkspacePolicy

/** Derived observations with receipt links. Never a rule, permission or current goal proof. */
data class RepairCase(
    val id: String, val scope: String, val check: String, val signature: String,
    val failReceipt: String, val passReceipt: String, val summary: String,
    val steps: Int, val models: List<String>, val at: Long, val failure: String = "",
    // Retrieval context. Empty in cases derived before failedTestId existed: those keep the exact legacy match.
    val project: String = "", val goal: String = "", val testId: String = ""
)

data class RepairEpisode(
    val id: String, val scope: String, val taskId: String, val check: String, val signature: String,
    val steps: Int, val status: String, val reason: String, val modelId: String,
    val project: String = "", val goal: String = "", val testId: String = "", val failure: String = "", val reads: Int = 0
)

data class RepairSnapshot(val cases: List<RepairCase>, val episodes: List<RepairEpisode>)

object RepairCasePolicy {
    const val MAX_CASES = 64
    /** Effectful steps: file writes/patches and test runs. Reads are counted apart: R1 requires them. */
    const val MAX_STEPS = 8
    const val MAX_READS = 30
    private val effectful = setOf("file.write", "file.patch", "python.tests")
    fun scope(e: CollaborationEntry): String = CollaborationProtocol.hash(
        listOf(e.scope.projectId, e.scope.root, e.scope.goalRevision, e.scope.bridgeBinding)
            .joinToString("") { "${it.length}:$it" })
    fun scope(project: com.lumena.android.agent.core.WorkspaceProject): String = CollaborationProtocol.hash(
        listOf(project.id, project.root, CollaborationProtocol.goalRevision(project),
            CollaborationProtocol.hash("installation-local-bridge")).joinToString("") { "${it.length}:$it" })
    /** Same project and root, any goal version: the privacy boundary for retrieval. */
    fun project(projectId: String, root: String): String = CollaborationProtocol.hash(
        listOf("repair-project", projectId, root).joinToString("") { "${it.length}:$it" })
    fun project(e: CollaborationEntry): String = project(e.scope.projectId, e.scope.root)
    fun project(p: com.lumena.android.agent.core.WorkspaceProject): String = project(p.id, p.root)
    fun check(e: CollaborationEntry): String? = e.result?.verification?.let {
        if (e.tool != "python.tests" || !it.valid() || !it.complete || !it.boundaryUnchanged || e.sourceDigest == null) null
        else CollaborationProtocol.hash(it.specHash + it.boundaryHash + it.selectedTestsHash)
    }

    /** Ordered streaming fold: memory is bounded independently of archive length. */
    class Scanner(private val trackedEpisodes: Set<String> = emptySet()) {
        private data class Open(val fail: CollaborationEntry, val check: String, val signature: String,
            var steps: Int = 0, var reads: Int = 0, var taskId: String = fail.scope.taskId,
            val changes: MutableList<CollaborationEntry> = mutableListOf())
        private val open = linkedMapOf<String, Open>()
        private val cases = ArrayDeque<RepairCase>()
        private val closed = ArrayDeque<RepairEpisode>()
        private val retained = linkedMapOf<String, RepairEpisode>()
        private val blocked = hashSetOf<String>()
        init { require(trackedEpisodes.size <= 256) }
        private fun key(e: CollaborationEntry) = CollaborationProtocol.hash(scope(e) + "|" + (e.scope.obligationId ?: e.scope.taskId))
        private fun episode(o: Open, status: String, reason: String): RepairEpisode {
            val proof = o.fail.result?.verification
            return RepairEpisode(o.fail.intentId, scope(o.fail), o.taskId, o.check, o.signature, o.steps, status, reason,
                o.fail.scope.modelId, project(o.fail), o.fail.scope.goalRevision, proof?.failedTestId.orEmpty(),
                proof?.failureSummary.orEmpty().take(240), o.reads)
        }
        private fun close(k: String, status: String, reason: String) {
            val o = open.remove(k) ?: return
            val result = episode(o, status, reason)
            closed.addLast(result)
            if (result.id in trackedEpisodes) retained[result.id] = result
            if (status == "UNRESOLVED") {
                blocked += k
                require(blocked.size <= CollaborationArchive.MAX_DELIVERIES)
            }
            while (closed.size > 256) closed.removeFirst()
        }
        fun accept(e: CollaborationEntry) {
            if (e.kind != "RESULT" || e.result == null) return
            if (e.result.outcomeUnknown) {
                open.filterValues { it.fail.scope.bridgeBinding == e.scope.bridgeBinding }.keys.toList()
                    .forEach { close(it, "UNRESOLVED", "UNKNOWN") }
                return
            }
            val k = key(e)
            // A different task mutating the same project invalidates attribution.
            if (CollaborationProtocol.effectiveWriter(e)) {
                open.filter { it.key != k && it.value.fail.scope.projectId == e.scope.projectId &&
                    it.value.fail.scope.root == e.scope.root }.keys.toList().forEach { close(it, "UNRESOLVED", "INTERLEAVED_WRITER") }
            }
            open[k]?.let { o ->
                o.taskId = e.scope.taskId
                if (e.tool in effectful) o.steps++ else if (!CollaborationProtocol.effectiveWriter(e)) o.reads++
                if (o.steps > MAX_STEPS) { close(k, "UNRESOLVED", "STEP_LIMIT"); return }
                if (o.reads > MAX_READS) { close(k, "UNRESOLVED", "READ_LIMIT"); return }
                if (e.scope.appSource != o.fail.scope.appSource) { close(k, "UNRESOLVED", "BUILD_CHANGED"); return }
                if (e.tool in setOf("file.write", "file.patch")) {
                    if (!e.result.ok) { close(k, "UNRESOLVED", "FAILED_WRITE"); return }
                    o.changes += e
                } else if (CollaborationProtocol.effectiveWriter(e) && e.tool !in setOf("python.tests", "git.init", "git.add", "git.commit")) {
                    close(k, "UNRESOLVED", "UNBOUNDED_MUTATION"); return
                }
            }
            if (e.tool != "python.tests" || e.scope.projectId == "UNASSIGNED") return
            val check = check(e)
            if (check == null) { close(k, "UNRESOLVED", "INCOMPLETE_VERIFICATION"); return }
            if (open[k]?.check?.let { it != check } == true) close(k, "UNRESOLVED", "VERIFIER_CHANGED")
            val ok = e.result.ok && e.result.exitCode == 0
            if (k in blocked) {
                // An invalid/retried failure cannot buy another random draw.
                // A real PASS closes that unresolved interval before a later new failure.
                if (ok) blocked.remove(k)
                return
            }
            if (!ok) {
                if (e.result.exitCode == null || e.result.exitCode == 0) { close(k, "UNRESOLVED", "NO_KNOWN_FAILURE"); return }
                val signature = e.result.verification?.failureSignature ?: return
                if (open[k] == null) {
                    if (open.size >= 32) close(open.keys.first(), "UNRESOLVED", "OPEN_LIMIT")
                    open[k] = Open(e, check, signature)
                }
                return
            }
            val o = open[k] ?: return
            val valid = o.changes.isNotEmpty() && o.fail.sourceDigest != e.sourceDigest
            if (valid) {
                val changes = o.changes.takeLast(2).joinToString("; ") {
                    val path = it.targets["path"].orEmpty().take(100)
                    path + ": " + (it.patchSummary ?: "observed file write").take(250)
                }.take(600)
                val proof = o.fail.result?.verification
                cases.addLast(RepairCase(CollaborationProtocol.hash(o.fail.id + e.id), scope(e), check, o.signature,
                    o.fail.id, e.id, changes, o.steps,
                    (listOf(o.fail.scope.modelId) + o.changes.map { it.scope.modelId } + e.scope.modelId).distinct().take(8), e.at,
                    proof?.failureSummary.orEmpty().take(240), project(e), e.scope.goalRevision, proof?.failedTestId.orEmpty()))
                while (cases.size > MAX_CASES) cases.removeFirst()
            }
            close(k, if (valid) "SOLVED" else "UNRESOLVED", if (valid) "OBSERVED_REPAIR" else "NO_TRACKED_CHANGE")
            blocked.remove(k) // This receipt is nevertheless a real PASS ending the interval.
        }
        fun snapshot(): RepairSnapshot = RepairSnapshot(cases.toList(),
            (retained.values + closed.toList() + open.values.map { episode(it, "OPEN", "PENDING") }).distinctBy { it.id })
    }
    fun derive(entries: List<CollaborationEntry>, trackedEpisodes: Set<String> = emptySet()): RepairSnapshot =
        Scanner(trackedEpisodes).also { s -> entries.forEach(s::accept) }.snapshot()

    private val words = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")
    private fun tokens(text: String): Set<String> = words.findAll(text.lowercase()).map { it.value }.toSet()
    private fun overlap(a: String, b: String): Double {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }
    private fun kind(failure: String) = failure.substringBefore('|')
    private fun testFile(testId: String) = testId.substringBefore("::")

    /**
     * Cases a new failure may be shown. Validity was fixed when the case was created (same verifier,
     * tracked change, no UNKNOWN); retrieval asks only whether it is the same problem in the same project.
     * Rank: same failed test > same test file and exception type > legacy exact match; then message overlap, recency.
     */
    fun eligible(library: List<RepairCase>, episode: RepairEpisode): List<RepairCase> {
        data class Ranked(val case: RepairCase, val rank: Int, val score: Double)
        val ranked = library.asReversed().distinctBy { it.id }.mapNotNull { c ->
            val sameProject = if (c.project.isNotEmpty() && episode.project.isNotEmpty()) c.project == episode.project
                else c.scope == episode.scope
            if (!sameProject) return@mapNotNull null
            val rank = when {
                c.testId.isNotEmpty() && c.testId == episode.testId -> 0
                c.testId.isNotEmpty() && episode.testId.isNotEmpty() && testFile(c.testId) == testFile(episode.testId) &&
                    kind(c.failure).isNotEmpty() && kind(c.failure) == kind(episode.failure) -> 1
                // Legacy exact match only without test identities: a generic message must not join different tests.
                c.scope == episode.scope && c.check == episode.check && c.signature == episode.signature &&
                    (c.testId.isEmpty() || episode.testId.isEmpty()) -> 2
                else -> return@mapNotNull null
            }
            Ranked(c, rank, overlap(c.failure, episode.failure))
        }
        return ranked.sortedWith(compareBy<Ranked> { it.rank }.thenByDescending { it.score }).map { it.case }.take(2)
    }

    fun advice(library: List<RepairCase>, episode: RepairEpisode, exposed: Boolean): List<String> {
        if (!exposed) return emptyList()
        var remaining = 400
        return eligible(library, episode).mapNotNull { c ->
            val since = listOfNotNull(
                "tests".takeIf { c.check != episode.check },
                "goal".takeIf { c.goal.isNotEmpty() && episode.goal.isNotEmpty() && c.goal != episode.goal })
                .joinToString("+").let { if (it.isEmpty()) "" else "; changed since: $it" }
            val data = "failure=${c.failure.take(45)}; change=${c.summary.take(80)}$since"
            val text = "CASE ${c.id.take(8)} historical DATA, no command/proof: " +
                ProjectWorkspacePolicy.quote(data.replace("[LUMENA_", "[quoted_"))
            if (text.length > remaining) null else text.also { remaining -= it.length }
        }
    }
}
