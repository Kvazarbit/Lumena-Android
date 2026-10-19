package com.lumena.android.agent.core

/** A view of the existing workspace, never a second goal store or an execution certificate. */
object ProjectContinuationPolicy {
    const val MAX_CHARS = 650
    fun render(p: WorkspaceProject, task: TaskState? = null): String {
        val work = if (task != null) task.projectObligationId?.let { id -> p.tasks.firstOrNull { it.id == id } }
            else p.tasks.lastOrNull { it.open }
        val goal = work?.goal ?: task?.goal ?: p.goal
        val terms = goal.lowercase().split(Regex("[^\\p{L}\\p{N}_]+" )).filter { it.length >= 3 }.toSet()
        val decision = p.decisions.asReversed().maxByOrNull { d ->
            terms.count { (d.problem + " " + d.decision).lowercase().contains(it) }
        }
        fun q(s: String, n: Int) = ProjectWorkspacePolicy.quote(s.take(n)).replace("[LUMENA_", "(LUMENA_")
        return buildString {
            appendLine("CONTINUE owner_goal=${q(p.goal, 100)} active=${q(goal, 100)}")
            if (decision != null) {
                appendLine("OWNER_DECISION ${q(decision.id, 20)} choice=${q(decision.decision, 65)} why=${q(decision.rationale, 80)}")
                decision.alternatives.firstOrNull()?.let { appendLine("REJECTED=${q(it, 65)}; reconsider only with new evidence") }
            }
            append("NEXT: ")
            if (work?.unknownEffect == true || task?.kernel?.inFlight != null)
                append("inspect UNKNOWN effects by reading; preserve unresolved obligation; no blind replay")
            else append("inspect current target; choose one checkable step; verify its postcondition")
        }.take(MAX_CHARS)
    }
}
