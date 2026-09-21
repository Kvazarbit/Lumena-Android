package com.lumena.android.settings

import java.security.MessageDigest
import kotlin.math.ln
import kotlin.math.sqrt
import com.lumena.android.agent.core.CoreDna

data class LandscapeObservation(
    val id: String,
    val taskId: String,
    val requestId: String,
    val scope: String,
    val scopeLabel: String,
    val intent: String,
    val operation: String,
    val tool: String,
    val target: String,
    val ok: Boolean,
    val elapsedMs: Long,
    val at: Long,
    val genomeEventId: String,
    val detail: String = ""
)

data class LandscapeState(
    val seedVersion: String = CoreDna.VERSION,
    val observations: List<LandscapeObservation> = emptyList(),
    val disabledRules: Set<String> = emptySet(),
    val activeRules: Set<String> = emptySet(),
    val autoPromote: Boolean = true,
    val revision: Long = 0
)

data class LandscapeNode(
    val id: String,
    val scope: String,
    val scopeLabel: String,
    val intent: String,
    val label: String,
    val successes: Int,
    val failures: Int,
    val score: Double,
    val confidence: Double,
    val medianMs: Long,
    val evidenceIds: List<String>,
    val latestFailed: Boolean,
    val ruleEligible: Boolean
)

data class LandscapeEdge(val from: String, val to: String, val successes: Int, val failures: Int)
enum class LandscapeRuleStatus { CANDIDATE, ACTIVE, CONTESTED, DISABLED, STALE, HELD }
data class LandscapeRule(
    val id: String,
    val nodeId: String,
    val kind: String,
    val text: String,
    val scope: String,
    val intent: String,
    val status: LandscapeRuleStatus,
    val evidenceIds: List<String>
)
data class LandscapeView(val nodes: List<LandscapeNode>, val edges: List<LandscapeEdge>, val rules: List<LandscapeRule>)

/** Learned advice cannot grant permissions, execute tools, or override controller invariants. */
object ExperienceLandscapePolicy {
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    const val MAX_OBSERVATIONS = 1024
    private val promotableTools = setOf(
        "python.syntax_check", "python.tests", "file.read", "file.list", "file.search",
        "git.status", "git.diff", "image.search", "http.get", "http.json",
        "context.snapshot", "workspace.list", "ollama.status", "system.info", "process.status"
    )

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)

    // Length prefixes avoid collisions caused by delimiters in argument values.
    fun operation(tool: String, args: Map<String, String>): String = hash(buildString {
        append(tool.length).append(':').append(tool)
        args.toSortedMap().forEach { (key, value) ->
            append(key.length).append(':').append(key).append(value.length).append(':').append(value)
        }
    })

    fun nodeId(event: LandscapeObservation) = hash("${event.scope}:${event.intent}:${event.operation}")

    fun record(state: LandscapeState, event: LandscapeObservation): LandscapeState {
        if (state.observations.any { it.id == event.id ||
                (it.taskId == event.taskId && it.requestId == event.requestId) }) return state
        return state.copy(observations = (state.observations + event.copy(elapsedMs = event.elapsedMs.coerceAtLeast(0)))
            .takeLast(MAX_OBSERVATIONS))
    }

    fun view(state: LandscapeState, now: Long): LandscapeView {
        val recent = state.observations.filter { it.at <= now && now - it.at <= WINDOW_MS }
        val grouped = recent.groupBy(::nodeId)
        val nodes = grouped.map { (id, events) ->
            val votes = events.groupBy { it.taskId }.values
            // A failure cannot be erased by retrying successfully in the same task.
            val successes = votes.count { task -> task.all { it.ok } }
            val failures = votes.size - successes
            val median = events.map { it.elapsedMs }.sorted().let { it[it.size / 2] }
            val posterior = (successes + 1.0) / (votes.size + 2.0)
            val last = events.sortedBy { it.at }.last()
            LandscapeNode(id, last.scope, last.scopeLabel, last.intent,
                "${last.tool} · ${last.target}", successes, failures,
                (100 * (2 * posterior - 1) - minOf(20.0, 3 * ln(1 + median / 1000.0))).coerceIn(-100.0, 100.0),
                votes.size / (votes.size + 4.0), median, events.map { it.id },
                events.any { it.taskId == last.taskId && !it.ok }, events.all { it.tool in promotableTools })
        }.sortedByDescending { it.score }

        data class Transition(val from: String, val to: String, val task: String, val ok: Boolean)
        val transitions = recent.groupBy { it.taskId }.values.flatMap { events ->
            events.sortedBy { it.at }.zipWithNext().mapNotNull { (a, b) ->
                if (a.scope != b.scope || a.intent != b.intent) null
                else Transition(nodeId(a), nodeId(b), a.taskId, a.ok && b.ok)
            }
        }
        val edges = transitions.groupBy { it.from to it.to }.map { (pair, samples) ->
            val votes = samples.groupBy { it.task }.values
            val successes = votes.count { task -> task.all { it.ok } }
            LandscapeEdge(pair.first, pair.second, successes, votes.size - successes)
        }
        val rules = nodes.flatMap { node ->
            listOf("PREFER", "RECHECK").mapNotNull { kind ->
                val id = hash("${node.id}:$kind")
                val support = if (kind == "PREFER") node.successes else node.failures
                val opposite = if (kind == "PREFER") node.failures else node.successes
                val known = id in state.activeRules || id in state.disabledRules
                if (!node.ruleEligible || (support < 3 && !known)) return@mapNotNull null
                val latestContradicts = if (kind == "PREFER") node.latestFailed else !node.latestFailed
                val eligible = support >= 8 && lowerBound(support, support + opposite) >= 0.65 && !latestContradicts
                val status = when {
                    id in state.disabledRules -> LandscapeRuleStatus.DISABLED
                    latestContradicts -> LandscapeRuleStatus.CONTESTED
                    eligible && (state.autoPromote || id in state.activeRules) -> LandscapeRuleStatus.ACTIVE
                    eligible -> LandscapeRuleStatus.HELD
                    known -> LandscapeRuleStatus.CONTESTED
                    else -> LandscapeRuleStatus.CANDIDATE
                }
                // Only trusted templates enter the prompt. Tool stdout and targets are evidence, never rules.
                val tool = grouped.getValue(node.id).last().tool
                val text = if (kind == "PREFER")
                    "$tool has repeated successful executions for one exact argument set in this scope; retrieve matching evidence before reuse. This does not prove task completion."
                else "$tool has repeated failures for one exact argument set in this scope; check prerequisites and current evidence before retrying."
                LandscapeRule(id, node.id, kind, text, node.scope, node.intent, status, node.evidenceIds)
            }
        }.toMutableList()
        // Retain visible explanations for rules whose supporting window expired or was evicted.
        (state.activeRules + state.disabledRules).filter { id -> rules.none { it.id == id } }.forEach { id ->
            rules += LandscapeRule(id, "", "ARCHIVED", "Supporting evidence is outside the retained window.",
                "", "", if (id in state.disabledRules) LandscapeRuleStatus.DISABLED else LandscapeRuleStatus.STALE, emptyList())
        }
        return LandscapeView(nodes, edges, rules)
    }

    fun reconcile(state: LandscapeState, now: Long): LandscapeState {
        val active = view(state, now).rules.filter { it.status == LandscapeRuleStatus.ACTIVE }.map { it.id }.toSet()
        return if (active == state.activeRules) state else state.copy(activeRules = active, revision = state.revision + 1)
    }

    fun restore(state: LandscapeState, historicalIds: Set<String>, now: Long): LandscapeState {
        // Rollback never resurrects stale or contradicted evidence and never cancels user exclusions.
        val eligible = view(state.copy(autoPromote = true), now).rules
            .filter { it.status == LandscapeRuleStatus.ACTIVE }.map { it.id }.toSet()
        return state.copy(activeRules = historicalIds.intersect(eligible), autoPromote = false,
            revision = state.revision + 1)
    }

    fun advice(state: LandscapeState, scope: String, intent: String, now: Long): List<String> =
        view(state, now).rules.filter { it.status == LandscapeRuleStatus.ACTIVE && it.scope == scope && it.intent == intent }
            .take(3).map { "LEARNED ADVICE (not permission) · ${it.text}" }

    private fun lowerBound(successes: Int, total: Int): Double {
        if (total == 0) return 0.0
        val z = 1.96
        val p = successes.toDouble() / total
        return (p + z * z / (2 * total) - z * sqrt((p * (1 - p) + z * z / (4 * total)) / total)) /
            (1 + z * z / total)
    }
}
