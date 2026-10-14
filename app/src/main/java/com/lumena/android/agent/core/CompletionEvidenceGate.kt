package com.lumena.android.agent.core

/**
 * Locally enforced verification-before-completion.
 *
 * A model's confident text, imported memories, and a toolUsed flag are not
 * execution evidence. Only ContextKernel's locally recorded TOOL_RESULT
 * entries can demonstrate that an operation actually ran.
 *
 * This gate complements the typed GoalContract (it cannot prove arbitrary
 * semantic outcomes from a successful tool call).
 */
internal object CompletionEvidenceGate {
    const val VERSION = "verification-before-completion-v1"

    private fun currentInstruction(state: AgentControlState): String =
        state.task.currentInstruction.ifBlank { state.task.goal }

    /**
     * A standalone "show!" has no target, even when the model guesses one.
     * If the task has a concrete prior visual goal, policy routing restores
     * that goal instead; do not block the legitimate visual continuation.
     */
    fun hasUnresolvedShowTarget(state: AgentControlState): Boolean {
        val root = state.task.goal
        return ShortTurnCue.isBareShowRequest(currentInstruction(state)) &&
            (root.isBlank() || ShortTurnCue.isBareShowRequest(root)) &&
            state.task.goalContract.coverage == GoalContractCoverage.NONE
    }

    fun hasSuccessfulLocalToolResult(state: AgentControlState): Boolean =
        state.task.kernel.evidence.any { it.ok }

    /**
     * The status DONE is disallowed for an operational request without a
     * successful locally recorded result, even if a model says "completed".
     * Caller must also enforce GoalContract's task-specific obligations and
     * ContextKernel's in-flight/failure/verification blockers.
     */
    fun missingExecutionProof(state: AgentControlState): String? {
        val operational =
            state.intent != TaskIntent.GENERAL ||
                ShortTurnCue.isExecutionDirective(currentInstruction(state))
        if (!operational || hasSuccessfulLocalToolResult(state)) return null
        return "NO_VERIFIED_TOOL_RESULT: no successful local TOOL_RESULT was recorded. " +
            "A model response, plan, or toolUsed flag alone is not completion proof. " +
            "Run an allowed tool and verify the result, or report partial."
    }

    fun unresolvedShowReport(): String =
        "Потрібне уточнення: що саме показати? " +
            "Конкретного об'єкта немає в активній задачі; показ не виконано."

    fun unexecutedDirectiveReport(): String =
        "Задачу не виконано: немає підтвердженого результату локального інструмента. " +
            "Уточни, що саме потрібно зробити, або продовж попередню задачу."
}
