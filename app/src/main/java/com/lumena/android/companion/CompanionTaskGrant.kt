package com.lumena.android.companion

/** In-memory user consent. Context IDs alone are never authority. Gate validation still applies. */
data class CompanionTaskGrant private constructor(
    val sessionId: String,
    val taskId: String,
    private val modelId: String,
    private val bridgeUrl: String,
    private val bridgeToken: String,
    val patterns: List<String>,
    val expiresAt: Long,
    val remaining: Int = 40
) {
    fun allows(command: CompanionCommand, url: String, token: String, now: Long): Boolean {
        if (now >= expiresAt || remaining <= 0 || url != bridgeUrl || token != bridgeToken) return false
        if (command.sessionId != sessionId || command.taskId != taskId || command.modelId.orEmpty() != modelId) return false
        if (!canOffer(command)) return false
        val path = command.decision.request.args["path"] ?: return false
        if (!validPath(path)) return false
        return patterns.any { pattern ->
            if (!pattern.endsWith('*')) path == pattern
            else {
                val prefix = pattern.dropLast(1)
                path.startsWith(prefix) && '/' !in path.removePrefix(prefix)
            }
        }
    }

    fun consume(): CompanionTaskGrant = copy(remaining = (remaining - 1).coerceAtLeast(0))

    companion object {
        fun canOffer(command: CompanionCommand): Boolean =
            !command.sessionId.isNullOrBlank() && !command.taskId.isNullOrBlank() &&
                command.decision.request.tool in setOf("file.write", "file.patch")

        private fun validPath(path: String): Boolean = path.isNotBlank() &&
            path == path.trim() && !path.startsWith('/') && !path.startsWith('@') &&
            path.none { it == '\\' || it == ':' || it == '*' || it.isISOControl() } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        fun create(command: CompanionCommand, paths: String, url: String, token: String, now: Long): CompanionTaskGrant? {
            if (!canOffer(command) || url.isBlank() || token.isBlank()) return null
            val patterns = paths.lines().map(String::trim).filter(String::isNotEmpty).distinct()
            if (patterns.isEmpty() || patterns.size > 20) return null
            if (patterns.any { !validPath(it.removeSuffix("*")) }) return null
            return CompanionTaskGrant(command.sessionId!!, command.taskId!!,
                command.modelId.orEmpty(), url, token, patterns, now + 30 * 60_000L)
        }
    }
}
