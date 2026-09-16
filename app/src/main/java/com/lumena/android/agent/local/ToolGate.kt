package com.lumena.android.agent.local

object ToolGate {
    private val readOnlyTools = setOf(
        "health",
        "file.read",
        "git.status",
        "git.diff",
        "git.log",
        "ollama.status"
    )

    private val executableTools = setOf(
        "python.run",
        "ollama.start",
        "ollama.pull"
    )

    private val knownTools = readOnlyTools + executableTools

    fun plan(decision: PlannerDecision): PlannedTool {
        val known = decision.request.tool in knownTools
        return PlannedTool(
            request = decision.request,
            reason = decision.reason,
            allowed = known,
            requiresConfirmation = known && decision.request.tool !in readOnlyTools
        )
    }
}
