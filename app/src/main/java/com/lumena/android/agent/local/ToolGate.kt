package com.lumena.android.agent.local

object ToolGate {
    private val readOnlyTools = setOf(
        "health",
        "workspace.list",
        "file.read",
        "git.status",
        "git.diff",
        "git.log",
        "ollama.status"
    )

    private val mutatingTools = setOf(
        "file.write",
        "dir.create",
        "project.create",
        "git.add",
        "git.commit",
        "python.run",
        "ollama.start",
        "ollama.pull"
    )

    private val knownTools = readOnlyTools + mutatingTools

    fun plan(decision: PlannerDecision): PlannedTool {
        val known = decision.request.tool in knownTools
        return PlannedTool(
            request = decision.request,
            reason = decision.reason,
            allowed = known,
            requiresConfirmation = known && decision.request.tool in mutatingTools
        )
    }
}
