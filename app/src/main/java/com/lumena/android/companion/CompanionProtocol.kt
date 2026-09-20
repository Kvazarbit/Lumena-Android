package com.lumena.android.companion

import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.lumena.android.model.ScreenSnapshot
import org.json.JSONObject
import java.security.MessageDigest

data class CompanionCommand(
    val decision: PlannerDecision,
    val rawJson: String,
    val fingerprint: String
)

object CompanionProtocol {
    const val TOOL_MARKER = "LUMENA_TOOL"
    const val RESULT_MARKER = "LUMENA_RESULT"

    val handshakeText: String = """
        Use Lumena Companion for local work on my Android phone.
        When you actually need a local tool, output a block in exactly this form and nothing else in that block:

        LUMENA_TOOL
        {"tool":"git.status","args":{"cwd":"project"},"reason":"Why this local action is needed"}

        Available tools:
        health, workspace.list, file.read, project.create, dir.create, file.write,
        git.status, git.diff, git.log, git.add, git.commit, python.run,
        ollama.status, ollama.start, ollama.pull.

        Never invent tool results. Wait for a LUMENA_RESULT message before continuing the task.
        Prefer read-only inspection before edits. Use only one tool request at a time.
        Lumena may auto-run registry-marked read-only tools when Safe Auto is enabled.
        Mutating or executable tools still require explicit user approval.
    """.trimIndent()

    fun parse(snapshot: ScreenSnapshot?): CompanionCommand? {
        if (snapshot == null) return null
        val text = buildString {
            snapshot.nodes.forEach { node ->
                val value = node.text ?: node.contentDescription
                if (!value.isNullOrBlank()) appendLine(value)
            }
        }
        return parseVisibleText(text)
    }

    fun parseVisibleText(text: String): CompanionCommand? {
        val markerIndex = text.lastIndexOf(TOOL_MARKER)
        if (markerIndex < 0) return null
        val json = extractJsonObject(text, markerIndex + TOOL_MARKER.length) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            val tool = obj.optString("tool").trim()
            if (tool.isBlank()) return null
            val reason = obj.optString("reason").ifBlank { "ChatGPT requested $tool" }
            val argsJson = obj.optJSONObject("args") ?: JSONObject()
            val args = buildMap {
                val keys = argsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, argsJson.opt(key)?.toString().orEmpty())
                }
            }
            CompanionCommand(
                decision = PlannerDecision(ToolRequest(tool, args), reason),
                rawJson = json,
                fingerprint = sha256(json)
            )
        }.getOrNull()
    }

    fun formatResult(tool: String, result: ToolResult): String = buildString {
        appendLine(RESULT_MARKER)
        appendLine("tool=$tool")
        appendLine("ok=${result.ok}")
        result.exitCode?.let { appendLine("exit_code=$it") }
        if (!result.error.isNullOrBlank()) appendLine("error=${result.error}")
        if (result.stdout.isNotBlank()) {
            appendLine("stdout:")
            appendLine(result.stdout.take(12000).trimEnd())
        }
        if (result.stderr.isNotBlank()) {
            appendLine("stderr:")
            appendLine(result.stderr.take(6000).trimEnd())
        }
        append("Continue the task using this real local result. Do not claim any action that is not shown here.")
    }

    private fun extractJsonObject(text: String, from: Int): String? {
        val start = text.indexOf('{', from)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
