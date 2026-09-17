package com.lumena.android.settings

import com.lumena.android.agent.core.TaskStatus
import java.text.DateFormat
import java.util.Date

object WorkReportFormatter {
    fun compact(branch: HistoryBranch): String {
        val task = branch.session.task
        val plan = branch.session.pending?.control?.plan.orEmpty()
        return buildString {
            appendLine("Lumena work report")
            appendLine("Topic: ${branch.topic}")
            appendLine("Task: ${branch.taskTitle}")
            appendLine("Branch: ${branch.title}")
            appendLine("Updated: ${formatTime(branch.updatedAt)}")
            appendLine("Status: ${task?.status?.name ?: "conversation"}")
            if (!task?.goal.isNullOrBlank()) appendLine("Goal: ${task?.goal}")
            if (plan.isNotEmpty()) {
                appendLine("Plan:")
                plan.take(8).forEachIndexed { index, step -> appendLine("${index + 1}. $step") }
            }
            if (!task?.lastTool.isNullOrBlank()) appendLine("Last tool: ${task?.lastTool}")
            if (!task?.lastResult.isNullOrBlank()) {
                appendLine("Last result:")
                appendLine(redact(task?.lastResult.orEmpty()).take(3000))
            }
            if (!task?.errors.isNullOrEmpty()) {
                appendLine("Errors:")
                task!!.errors.takeLast(3).forEach { appendLine("- ${redact(it).take(1200)}") }
            }
            val recent = branch.session.chat.takeLast(12)
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("Recent conversation:")
                recent.forEach { message ->
                    appendLine("${message.role.uppercase()}: ${redact(message.text).take(2200)}")
                }
            }
        }.trim()
    }

    fun full(branch: HistoryBranch): String {
        val task = branch.session.task
        return buildString {
            appendLine("Lumena full work report")
            appendLine("Topic: ${branch.topic}")
            appendLine("Task: ${branch.taskTitle}")
            appendLine("Branch: ${branch.title}")
            appendLine("Updated: ${formatTime(branch.updatedAt)}")
            appendLine("Status: ${task?.status?.name ?: "conversation"}")
            appendLine()

            if (task != null) {
                appendLine("TASK STATE")
                appendLine("Goal: ${redact(task.goal)}")
                appendLine("Step: ${task.step}/${task.maxSteps}")
                appendLine("Last tool: ${task.lastTool ?: "-"}")
                if (!task.lastResult.isNullOrBlank()) {
                    appendLine("Last result:")
                    appendLine(redact(task.lastResult).take(8000))
                }
                if (task.errors.isNotEmpty()) {
                    appendLine("Errors:")
                    task.errors.takeLast(8).forEach { appendLine("- ${redact(it).take(3000)}") }
                }
                appendLine()
            }

            val control = branch.session.pending?.control
            if (control != null) {
                if (control.plan.isNotEmpty()) {
                    appendLine("PLAN")
                    control.plan.forEachIndexed { index, step -> appendLine("${index + 1}. ${redact(step)}") }
                    appendLine()
                }
                appendLine("CONTROL")
                appendLine("verificationRequired=${control.verificationRequired}")
                if (!control.verificationReason.isNullOrBlank()) {
                    appendLine("verificationReason=${redact(control.verificationReason)}")
                }
                appendLine("protocolRetries=${control.protocolRetries}")
                appendLine("modelFailures=${control.modelFailures}")
                appendLine("identicalToolCalls=${control.identicalToolCalls}")
                appendLine()
            }

            appendLine("CHAT")
            branch.session.chat.takeLast(80).forEach { message ->
                appendLine("[${message.role.uppercase()}]")
                appendLine(redact(message.text).take(12000))
                appendLine()
            }

            if (branch.session.history.isNotEmpty()) {
                appendLine("MODEL / TOOL HISTORY")
                branch.session.history.takeLast(48).forEach { message ->
                    appendLine("[${message.role.uppercase()}]")
                    appendLine(redact(message.content).take(12000))
                    appendLine()
                }
            }
        }.trim()
    }

    private fun formatTime(time: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))

    private fun redact(raw: String): String {
        var text = raw
        text = text.replace(Regex("(?i)(authorization\\s*:\\s*bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[REDACTED]")
        text = text.replace(Regex("(?i)(bridge[_ -]?token\\s*[=:]\\s*)[^\\s,}\"]+"), "$1[REDACTED]")
        text = text.replace(Regex("(?i)(api[_ -]?key\\s*[=:]\\s*)[^\\s,}\"]+"), "$1[REDACTED]")
        return text
    }
}
