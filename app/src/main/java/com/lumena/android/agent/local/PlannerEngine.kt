package com.lumena.android.agent.local

interface PlannerEngine {
    fun plan(userRequest: String): PlannerDecision?
}

class RulePlanner : PlannerEngine {
    override fun plan(userRequest: String): PlannerDecision? {
        val input = userRequest.trim()
        if (input.isBlank()) return null
        val lower = input.lowercase()

        if (lower == "health" || lower == "bridge health" || lower == "перевір міст") {
            return PlannerDecision(ToolRequest("health"), "Check the local Termux bridge.")
        }

        prefixValue(input, lower, listOf("read ", "прочитай "))?.let { path ->
            return PlannerDecision(
                ToolRequest("file.read", mapOf("path" to path)),
                "Read a text file inside the configured workspace."
            )
        }

        prefixValue(input, lower, listOf("git status ", "git статус "))?.let { cwd ->
            return PlannerDecision(
                ToolRequest("git.status", mapOf("cwd" to cwd)),
                "Inspect repository status without changing it."
            )
        }

        prefixValue(input, lower, listOf("git diff "))?.let { cwd ->
            return PlannerDecision(
                ToolRequest("git.diff", mapOf("cwd" to cwd)),
                "Inspect uncommitted Git changes."
            )
        }

        prefixValue(input, lower, listOf("git log "))?.let { cwd ->
            return PlannerDecision(
                ToolRequest("git.log", mapOf("cwd" to cwd)),
                "Read recent Git history."
            )
        }

        prefixValue(input, lower, listOf("python ", "пайтон "))?.let { command ->
            val firstSpace = command.indexOf(' ')
            val script = if (firstSpace < 0) command else command.substring(0, firstSpace)
            val argv = if (firstSpace < 0) "" else command.substring(firstSpace + 1).trim()
            if (script.isBlank()) return null
            return PlannerDecision(
                ToolRequest(
                    "python.run",
                    buildMap {
                        put("script", script)
                        if (argv.isNotBlank()) put("argv", argv)
                    }
                ),
                "Run a Python script inside the Lumena workspace."
            )
        }

        return null
    }

    private fun prefixValue(input: String, lower: String, prefixes: List<String>): String? {
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return null
        return input.substring(prefix.length).trim().takeIf { it.isNotBlank() }
    }
}
