package com.lumena.android.agent.core

object RecoveryAdvisor {
    fun suggest(
        task: TaskState,
        call: AgentDecision.ToolCall,
        ok: Boolean,
        stdout: String,
        stderr: String,
        error: String?
    ): String? {
        if (ok) return null

        val tool = ToolRegistry.canonicalize(call.tool)
        val detail = sequenceOf(error, stderr, stdout)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
            .take(4_000)

        return when {
            tool == "image.search" ->
                "image.search already tries query broadening and multiple providers. Do not repeat the identical query; change the key subject terms while preserving the user's intent."

            tool in setOf("file.read", "file.list", "file.search") &&
                listOf(
                    "no such file", "not found", "does not exist", "requested path",
                    "path escapes", "must be a directory"
                ).any(detail::contains) ->
                "The requested path is not verified. Use workspace.list and/or file.search to discover the real path before retrying."

            tool.startsWith("git.") &&
                listOf(
                    "not a git repository", "no such file", "not found",
                    "path escapes", "cwd"
                ).any(detail::contains) ->
                "The repository/cwd is not verified. Discover a real root with workspace.list; @Lumena-Android is a known read-only repo alias when present."

            tool == "http.json" ->
                "Do not repeat the identical failing URL. Verify the public HTTPS endpoint; use http.get instead only when the response is text/HTML rather than JSON."

            tool == "http.get" ->
                "Do not repeat the identical failing URL. Verify the public HTTPS endpoint; use http.json instead when a structured JSON API exists."

            tool == "ollama.generate" ->
                "Inspect ollama.status before retrying generation. If the API is down, ollama.start requires approval; do not create ad-hoc Python HTTP helper scripts."

            tool == "ollama.status" ->
                "Use the real status error as evidence. If the local server is not running, ollama.start is the controlled recovery path and requires approval."

            tool.startsWith("python.") &&
                ("modulenotfounderror" in detail || "no module named" in detail) ->
                "A Python dependency is missing. Do not repeat the unchanged script. Inspect the script/environment and choose an explicit dependency or implementation recovery path."

            tool.startsWith("python.") ->
                "Do not repeat the unchanged failing Python action. Inspect the exact stderr/error, change the script or inputs, then verify again."

            else ->
                "Do not repeat the identical failing action. Re-inspect verified state, change the relevant input, and retry only with new evidence."
        }
    }
}
