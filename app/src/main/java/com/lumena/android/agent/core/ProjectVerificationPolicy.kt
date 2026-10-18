package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

object ProjectGoalTargets {
    private val files = Regex("(?:@?[a-zA-Z0-9_.-]+/)*[a-zA-Z0-9_.-]+\\.(?:py|kt|java|json|md|txt|csv|js|ts|html|css|toml|ya?ml)\\b", RegexOption.IGNORE_CASE)
    fun explicit(goal: String): List<String> = files.findAll(goal).map { it.value }.distinct().take(12).toList()
    fun mutationTarget(goal: String): String? = files.findAll(goal).firstOrNull { match ->
        val prefix = goal.substring(0, match.range.first).takeLast(80)
        Regex("(?:створи|створити|виправ|редагуй|онови|create|write|edit|fix|patch)\\s+(?:(?:файл|скрипт|file|script)\\s+)?[\\\"'`]?\\s*$", RegexOption.IGNORE_CASE)
            .containsMatchIn(prefix)
    }?.value
    fun matches(wanted: String?, actual: String): Boolean {
        if (wanted == null) return true
        val clean = wanted.trim().removePrefix("./")
        return actual == clean || actual.endsWith("/$clean")
    }
}

/** Project checks refer to observed effects and must run again after another mutation. */
object ProjectVerificationPolicy {
    private val json = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    fun afterTool(contract: GoalContract, task: TaskState, call: AgentDecision.ToolCall,
                  ok: Boolean, kernel: ContextKernelState, stdout: String): GoalContract {
        val root = task.projectRoot ?: return contract
        val tool = ToolRegistry.canonicalize(call.tool)
        val event = kernel.evidence.lastOrNull() ?: return contract
        var criteria = contract.criteria
        val changed = ToolRegistry.get(tool)?.workspaceMutationEffect in setOf(
            WorkspaceMutationEffect.DECLARED_TARGET, WorkspaceMutationEffect.UNDECLARED)
        if (changed && tool !in setOf("python.tests", "dir.create")) {
            criteria = criteria.map {
                if (it.kind == CriterionKind.PROJECT_VERIFICATION) it.copy(status = CriterionStatus.PENDING, evidence = emptyList()) else it
            }
            val subject = if (task.projectVerification == ProjectVerification.PYTHON_TESTS ||
                ToolRegistry.get(tool)?.workspaceMutationEffect == WorkspaceMutationEffect.UNDECLARED) "tests:$root"
                else "read:${call.args["path"].orEmpty()}"
            val id = "project-verification:" + ContextKernel.hash(subject)
            if (criteria.none { it.id == id }) criteria = criteria + AcceptanceCriterion(id, CriterionKind.PROJECT_VERIFICATION, subject)
        }
        criteria = criteria.map { criterion ->
            if (criterion.kind != CriterionKind.PROJECT_VERIFICATION) return@map criterion
            val passed = if (criterion.subject.startsWith("tests:")) {
                val decoded = runCatching { json.fromJson(stdout) }.getOrNull()
                tool == "python.tests" && ok && call.args["cwd"] == root &&
                    EvidenceProjectApplicationPolicy.isFullProjectTestArgs(call.args) &&
                    decoded?.get("schema") == "lumena.project.tests.v1" && decoded["cwd"] == root &&
                    ((decoded["tests_count"] as? Number)?.toInt() ?: 0) > 0 && decoded["verified"] == true
            } else {
                tool == "file.read" && ok && call.args["path"] == criterion.subject.removePrefix("read:") &&
                    call.args["start_line"].isNullOrBlank() && call.args["end_line"].isNullOrBlank()
            }
            if (passed) criterion.copy(status = CriterionStatus.PASSED, evidence = listOf(
                CriterionEvidence(event.id, event.tool, event.target, VerificationStrength.INDEPENDENT_TOOL_RESULT, event.revision)))
            else criterion
        }
        require(criteria.size <= 32) { "Project verification obligations exceed this stage; continue with a smaller task" }
        return contract.copy(criteria = criteria, coverage = GoalContractCoverage.TYPED_OPERATIONAL_V1)
    }
}
