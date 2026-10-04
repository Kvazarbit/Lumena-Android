package com.lumena.android.agent.core

sealed interface AgentDecision {
    data class Reply(val text: String) : AgentDecision

    data class ToolCall(
        val tool: String,
        val args: Map<String, String> = emptyMap(),
        val reason: String = "",
        val plan: List<String> = emptyList(),
        // Model prose remains a hypothesis only. WorkflowRunner hashes this
        // before any pending approval/session persistence.
        val causeHypothesis: String? = null,
        val causeProbeOnSuccess: String? = null,
        val causeProbeOnFailure: String? = null
    ) : AgentDecision

    data class Done(val summary: String) : AgentDecision
    data class Partial(val summary: String) : AgentDecision
}

enum class TaskStatus {
    NEW,
    PLANNING,
    WAITING_CONFIRMATION,
    EXECUTING,
    VERIFYING,
    WAITING_MODEL,
    DONE,
    PARTIAL,
    FAILED,
    CANCELLED
}

data class TaskState(
    val id: String,
    val projectId: String?,
    val goal: String,
    val status: TaskStatus = TaskStatus.NEW,
    val step: Int = 0,
    val maxSteps: Int = 4,
    val lastTool: String? = null,
    val lastResult: String? = null,
    val createdFiles: List<String> = emptyList(),
    val modifiedFiles: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val kernel: ContextKernelState = ContextKernelState(),
    val goalContract: GoalContract = GoalContract(),
    val currentInstruction: String = "",
    val effectivePolicy: EffectiveTaskPolicy =
        EffectiveTaskPolicy(),
    val executionOrigin: HistoricalRecordOrigin =
        HistoricalRecordOrigin.LOCAL_CURRENT
) {
    /**
     * maxSteps limits tool executions, not the final model conclusion.
     * At step == maxSteps the model still gets one last turn to return done/reply;
     * AgentController blocks any additional tool call at that point.
     */
    val canContinue: Boolean
        get() = status !in setOf(TaskStatus.DONE, TaskStatus.PARTIAL, TaskStatus.FAILED, TaskStatus.CANCELLED) && step <= maxSteps
}
