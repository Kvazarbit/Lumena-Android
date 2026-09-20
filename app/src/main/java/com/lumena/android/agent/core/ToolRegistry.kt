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
        ToolSpec("system.time", ToolRisk.READ_ONLY, description = "Read the phone's current local date, time and timezone."),
        ToolSpec("system.info", ToolRisk.READ_ONLY, description = "Inspect CPU, memory, storage and Termux/Android environment."),
        ToolSpec("http.json", ToolRisk.READ_ONLY, setOf("url"), "Fetch a public HTTPS JSON API with SSRF, redirect, timeout and size guards."),
        ToolSpec("file.list", ToolRisk.READ_ONLY, description = "List a directory in an allowed read root. Optional path; use @Lumena-Android for the app repo."),
        ToolSpec("file.search", ToolRisk.READ_ONLY, setOf("query"), "Search file names and text in allowed read roots. Optional path; @Lumena-Android is read-only."),
        ToolSpec("workspace.list", ToolRisk.READ_ONLY, description = "List writable workspace contents and discover explicit read-only roots such as @Lumena-Android."),
        ToolSpec("file.read", ToolRisk.READ_ONLY, setOf("path"), "Read a text file from the workspace or an explicit read-only root."),
        ToolSpec("git.status", ToolRisk.READ_ONLY, setOf("cwd"), "Inspect repository status without optional locks; cwd may be @Lumena-Android."),
        ToolSpec("git.diff", ToolRisk.READ_ONLY, setOf("cwd"), "Inspect repository diff with external diff/textconv disabled; cwd may be @Lumena-Android."),
        ToolSpec("git.log", ToolRisk.READ_ONLY, setOf("cwd"), "Inspect recent Git commits; cwd may be @Lumena-Android."),
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
        "file_list" to "file.list",
        "file_search" to "file.search",
        "http_json" to "http.json",
        "system_info" to "system.info",
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
