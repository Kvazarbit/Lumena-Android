package com.lumena.android.companion

import java.security.MessageDigest

/** Explicit local consent bound to context and connection, never inferred from model text. */
data class CompanionTaskGrant private constructor(
    val sessionId: String,
    val taskId: String,
    private val modelId: String,
    private val connection: String,
    val patterns: List<String>,
    val allowPython: Boolean,
    val paused: Boolean = false
) {
    fun denial(command: CompanionCommand, url: String, token: String): String? {
        if (paused) return "Дозвіл призупинено: результат попередньої команди невідомий."
        if (connection != connectionKey(url, token)) return "Змінилося підключення до bridge."
        if (command.sessionId != sessionId || command.taskId != taskId || command.modelId.orEmpty() != modelId)
            return "Інша сесія, задача або модель; попередній дозвіл збережено."
        val request = command.decision.request
        if (!canOffer(command)) return "Цей інструмент не входить у дозвіл."
        if (request.tool == "python.run" && !allowPython) return "Автозапуск Python не дозволений для цієї задачі."
        val path = request.args[if (request.tool == "python.run") "script" else "path"]
        if (path == null || !validPath(path) || !patterns.any { matches(it, path) })
            return "Файл або скрипт поза погодженим переліком."
        return null
    }

    fun allows(command: CompanionCommand, url: String, token: String) = denial(command, url, token) == null

    fun snapshot(): Map<String, String> = mapOf("session" to sessionId, "task" to taskId,
        "model" to modelId, "connection" to connection, "paths" to patterns.joinToString("\n"),
        "python" to allowPython.toString(), "paused" to paused.toString())

    companion object {
        fun canOffer(command: CompanionCommand): Boolean =
            !command.sessionId.isNullOrBlank() && !command.taskId.isNullOrBlank() &&
                command.decision.request.tool in setOf("file.write", "file.patch", "python.run")

        private fun connectionKey(url: String, token: String) = MessageDigest.getInstance("SHA-256")
            .digest("$url\u0000$token".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun validPath(path: String): Boolean = path.isNotBlank() &&
            path == path.trim() && !path.startsWith('/') && !path.startsWith('@') &&
            path.none { it == '\\' || it == ':' || it == '*' || it.isISOControl() } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        private fun matches(pattern: String, path: String): Boolean = if (!pattern.endsWith('*')) path == pattern
            else path.startsWith(pattern.dropLast(1)) && '/' !in path.removePrefix(pattern.dropLast(1))

        private fun parsePaths(value: String): List<String>? {
            val list = value.lines().map(String::trim).filter(String::isNotEmpty).distinct()
            return list.takeIf { it.isNotEmpty() && it.size <= 20 && it.all { p -> validPath(p.removeSuffix("*")) } }
        }

        fun create(command: CompanionCommand, paths: String, url: String, token: String,
                   allowPython: Boolean = false): CompanionTaskGrant? {
            if (!canOffer(command) || url.isBlank() || token.isBlank()) return null
            return CompanionTaskGrant(command.sessionId!!, command.taskId!!, command.modelId.orEmpty(),
                connectionKey(url, token), parsePaths(paths) ?: return null, allowPython)
        }

        fun restore(values: Map<String, String>): CompanionTaskGrant? {
            if (values["session"].isNullOrBlank() || values["task"].isNullOrBlank()) return null
            val key = values["connection"] ?: return null
            if (!key.matches(Regex("[a-f0-9]{64}"))) return null
            val python = values["python"]?.toBooleanStrictOrNull() ?: return null
            val paused = values["paused"]?.toBooleanStrictOrNull() ?: return null
            return CompanionTaskGrant(values.getValue("session"), values.getValue("task"),
                values["model"] ?: return null, key, parsePaths(values["paths"] ?: return null) ?: return null, python, paused)
        }
    }
}
