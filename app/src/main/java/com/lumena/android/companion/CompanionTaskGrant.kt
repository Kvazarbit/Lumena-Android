package com.lumena.android.companion

import java.security.MessageDigest

enum class CompanionTaskGrantKind { FILES, PYTHON_RUN }

/**
 * Explicit user consent for bounded Companion automation.
 *
 * Context IDs are scope, not authority: the grant is created only from an APK button,
 * remains subject to ToolGate / ToolRegistry validation, is connection-bound, expires,
 * and has a finite execution budget.
 */
data class CompanionTaskGrant private constructor(
    val sessionId: String,
    val taskId: String,
    internal val modelId: String,
    internal val bridgeFingerprint: String,
    val kind: CompanionTaskGrantKind,
    val patterns: List<String>,
    val expiresAt: Long,
    val remaining: Int = MAX_USES
) {
    fun isLiveForConnection(url: String, token: String, now: Long): Boolean =
        now < expiresAt && remaining > 0 && bridgeFingerprint == connectionFingerprint(url, token)

    fun allows(command: CompanionCommand, url: String, token: String, now: Long): Boolean {
        if (!isLiveForConnection(url, token, now)) return false
        if (command.sessionId != sessionId || command.taskId != taskId || command.modelId.orEmpty() != modelId) return false
        return when (kind) {
            CompanionTaskGrantKind.PYTHON_RUN ->
                command.decision.request.tool == "python.run"
            CompanionTaskGrantKind.FILES -> {
                if (command.decision.request.tool !in FILE_TOOLS) return false
                val path = command.decision.request.args["path"] ?: return false
                if (!validPath(path)) return false
                patterns.any { pattern ->
                    if (!pattern.endsWith('*')) path == pattern
                    else {
                        val prefix = pattern.dropLast(1)
                        path.startsWith(prefix) && '/' !in path.removePrefix(prefix)
                    }
                }
            }
        }
    }

    fun consume(): CompanionTaskGrant = copy(remaining = (remaining - 1).coerceAtLeast(0))

    companion object {
        const val MAX_USES = 40
        const val DURATION_MS = 40 * 60_000L
        private val FILE_TOOLS = setOf("file.write", "file.patch")

        fun canOffer(command: CompanionCommand): Boolean =
            !command.sessionId.isNullOrBlank() && !command.taskId.isNullOrBlank() &&
                command.decision.request.tool in FILE_TOOLS + "python.run"

        private fun validPath(path: String): Boolean = path.isNotBlank() &&
            path == path.trim() && !path.startsWith('/') && !path.startsWith('@') &&
            path.none { it == '\\' || it == ':' || it == '*' || it.isISOControl() } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        internal fun connectionFingerprint(url: String, token: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest((url + "\u0000" + token).toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun create(
            command: CompanionCommand,
            paths: String,
            url: String,
            token: String,
            now: Long
        ): CompanionTaskGrant? {
            if (!canOffer(command) || url.isBlank() || token.isBlank()) return null
            val tool = command.decision.request.tool
            val kind = if (tool == "python.run") CompanionTaskGrantKind.PYTHON_RUN else CompanionTaskGrantKind.FILES
            val patterns = if (kind == CompanionTaskGrantKind.FILES) {
                paths.lines().map(String::trim).filter(String::isNotEmpty).distinct().also {
                    if (it.isEmpty() || it.size > 20 || it.any { p -> !validPath(p.removeSuffix("*")) }) return null
                }
            } else {
                emptyList()
            }
            return CompanionTaskGrant(
                sessionId = command.sessionId!!,
                taskId = command.taskId!!,
                modelId = command.modelId.orEmpty(),
                bridgeFingerprint = connectionFingerprint(url, token),
                kind = kind,
                patterns = patterns,
                expiresAt = now + DURATION_MS
            )
        }

        internal fun restore(
            sessionId: String,
            taskId: String,
            modelId: String,
            bridgeFingerprint: String,
            kindName: String,
            patterns: List<String>,
            expiresAt: Long,
            remaining: Int
        ): CompanionTaskGrant? {
            if (sessionId.isBlank() || taskId.isBlank() || bridgeFingerprint.isBlank()) return null
            if (remaining !in 1..MAX_USES || expiresAt <= 0L) return null
            val kind = runCatching { CompanionTaskGrantKind.valueOf(kindName) }.getOrNull() ?: return null
            val safePatterns = when (kind) {
                CompanionTaskGrantKind.PYTHON_RUN -> emptyList()
                CompanionTaskGrantKind.FILES -> patterns.distinct().also {
                    if (it.isEmpty() || it.size > 20 || it.any { p -> !validPath(p.removeSuffix("*")) }) return null
                }
            }
            return CompanionTaskGrant(
                sessionId, taskId, modelId, bridgeFingerprint, kind, safePatterns, expiresAt, remaining
            )
        }
    }
}
