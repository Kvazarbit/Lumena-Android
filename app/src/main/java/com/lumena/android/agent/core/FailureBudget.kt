package com.lumena.android.agent.core

data class FailureBudget(
    val maxModelRetries: Int = 2,
    val maxIdenticalToolFailures: Int = 2,
    val maxPythonFailures: Int = 3,
    val maxSemanticRecoveries: Int = 2,
    val maxActionFamilyFailures: Int = 2,
    // Public-web source reading is read-only and individual sites commonly
    // challenge or time out. Allow a few distinct source alternatives without
    // relaxing mutation, model, protocol, or identical-call budgets.
    val maxWebSourceFailures: Int = 4,
    val maxTotalSteps: Int = 12
)

enum class BudgetViolation {
    MODEL_RETRY_LIMIT,
    IDENTICAL_TOOL_FAILURE_LIMIT,
    PYTHON_FAILURE_LIMIT,
    STEP_LIMIT
}

class FailureTracker(
    private val budget: FailureBudget = FailureBudget()
) {
    private var modelRetries = 0
    private var pythonFailures = 0
    private var steps = 0
    private val toolFailures = mutableMapOf<String, Int>()

    fun recordStep(): BudgetViolation? {
        steps++
        return if (steps > budget.maxTotalSteps) BudgetViolation.STEP_LIMIT else null
    }

    fun recordModelFailure(): BudgetViolation? {
        modelRetries++
        return if (modelRetries > budget.maxModelRetries) BudgetViolation.MODEL_RETRY_LIMIT else null
    }

    fun recordModelSuccess() {
        modelRetries = 0
    }

    fun recordToolResult(call: AgentDecision.ToolCall, ok: Boolean): BudgetViolation? {
        val signature = signature(call)
        if (ok) {
            toolFailures.remove(signature)
            if (ToolRegistry.canonicalize(call.tool).startsWith("python.")) pythonFailures = 0
            return null
        }

        val count = (toolFailures[signature] ?: 0) + 1
        toolFailures[signature] = count
        if (count > budget.maxIdenticalToolFailures) {
            return BudgetViolation.IDENTICAL_TOOL_FAILURE_LIMIT
        }

        if (ToolRegistry.canonicalize(call.tool).startsWith("python.")) {
            pythonFailures++
            if (pythonFailures > budget.maxPythonFailures) {
                return BudgetViolation.PYTHON_FAILURE_LIMIT
            }
        }

        return null
    }

    fun snapshot(): FailureSnapshot = FailureSnapshot(
        modelRetries = modelRetries,
        pythonFailures = pythonFailures,
        steps = steps,
        repeatedToolFailures = toolFailures.toMap()
    )

    private fun signature(call: AgentDecision.ToolCall): String {
        val args = call.args.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value.trim()}" }
        return "${ToolRegistry.canonicalize(call.tool)}|$args"
    }
}

data class FailureSnapshot(
    val modelRetries: Int,
    val pythonFailures: Int,
    val steps: Int,
    val repeatedToolFailures: Map<String, Int>
)
