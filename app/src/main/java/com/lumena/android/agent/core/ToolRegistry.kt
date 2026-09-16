package com.lumena.android.agent.core

enum class ToolRisk {
    READ_ONLY,
    MUTATING,
    EXECUTABLE
}

data class ToolSpec(
    val name: String,
    val risk: ToolRisk,
    val requiredArgs: Set<String> = emptySet(),
    val description: String
)

data class ToolValidation(
    val allowed: Boolean,
    val canonicalTool: String?,
    val requiresConfirmation: Boolean,
    val error: String? = null
)

object ToolRegistry {
    private val specs = listOf(
        ToolSpec("health", ToolRisk.READ_ONLY, description = "Check the local Termux bridge."),
        ToolSpec("workspace.list", ToolRisk.READ_ONLY, description = "List projects and files in the workspace root."),
        ToolSpec("file.read", ToolRisk.READ_ONLY, setOf("path"), "Read a text file inside the workspace."),
        ToolSpec("git.status", ToolRisk.READ_ONLY, setOf("cwd"), "Inspect repository status."),
        ToolSpec("git.diff", ToolRisk.READ_ONLY, setOf("cwd"), "Inspect repository diff."),
        ToolSpec("git.log", ToolRisk.READ_ONLY, setOf("cwd"), "Inspect recent Git commits."),
        ToolSpec("ollama.status", ToolRisk.READ_ONLY, description = "Inspect local Ollama status and models."),

        ToolSpec("project.create", ToolRisk.MUTATING, setOf("name"), "Create a workspace project."),
        ToolSpec("dir.create", ToolRisk.MUTATING, setOf("path"), "Create a directory inside the workspace."),
        ToolSpec("file.write", ToolRisk.MUTATING, setOf("path", "content"), "Write a text file inside the workspace."),
        ToolSpec("file.patch", ToolRisk.MUTATING, setOf("path", "old", "new"), "Replace exactly one known text fragment."),
        ToolSpec("git.add", ToolRisk.MUTATING, setOf("cwd", "paths"), "Stage workspace files."),
        ToolSpec("git.commit", ToolRisk.MUTATING, setOf("cwd", "message"), "Commit staged changes."),

        ToolSpec("python.run", ToolRisk.EXECUTABLE, setOf("script"), "Run an existing Python script inside the workspace."),
        ToolSpec("python.syntax_check", ToolRisk.EXECUTABLE, setOf("script"), "Compile-check a Python script without running its logic."),
        ToolSpec("python.tests", ToolRisk.EXECUTABLE, setOf("cwd"), "Run project tests through the controlled Python runner."),
        ToolSpec("ollama.start", ToolRisk.EXECUTABLE, description = "Start the same-phone Ollama sidecar."),
        ToolSpec("ollama.pull", ToolRisk.EXECUTABLE, setOf("model"), "Download an Ollama model after approval.")
    ).associateBy { it.name }

    private val aliases = mapOf(
        "git_status" to "git.status",
        "git_diff" to "git.diff",
        "git_log" to "git.log",
        "file_read" to "file.read",
        "file_write" to "file.write",
        "python_run" to "python.run"
    )

    fun all(): List<ToolSpec> = specs.values.sortedBy { it.name }

    fun canonicalize(name: String): String {
        val normalized = name.trim().lowercase()
        return aliases[normalized] ?: normalized
    }

    fun get(name: String): ToolSpec? = specs[canonicalize(name)]

    fun validate(call: AgentDecision.ToolCall, externalSource: Boolean = false): ToolValidation {
        val canonical = canonicalize(call.tool)
        val spec = specs[canonical]
            ?: return ToolValidation(false, null, true, "Unknown tool: ${call.tool}")

        val missing = spec.requiredArgs.filter { call.args[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return ToolValidation(
                allowed = false,
                canonicalTool = canonical,
                requiresConfirmation = true,
                error = "Missing required args: ${missing.joinToString()}"
            )
        }

        val oversized = call.args.entries.firstOrNull { (key, value) ->
            val limit = if (key == "content") 256_000 else 16_000
            value.length > limit
        }
        if (oversized != null) {
            return ToolValidation(
                allowed = false,
                canonicalTool = canonical,
                requiresConfirmation = true,
                error = "Argument too large: ${oversized.key}"
            )
        }

        return ToolValidation(
            allowed = true,
            canonicalTool = canonical,
            requiresConfirmation = externalSource || spec.risk != ToolRisk.READ_ONLY
        )
    }

    fun renderForPrompt(allowed: Set<String>? = null): String {
        return all()
            .filter { allowed == null || it.name in allowed }
            .joinToString("\n") { spec ->
                val args = if (spec.requiredArgs.isEmpty()) "{}"
                else spec.requiredArgs.joinToString(prefix = "{", postfix = "}") { "\"$it\":\"...\"" }
                "- ${spec.name} $args — ${spec.description}"
            }
    }
}
