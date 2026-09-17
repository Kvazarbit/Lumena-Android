package com.lumena.android.ollama

import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.ToolRequest
import org.json.JSONArray
import org.json.JSONObject

sealed interface AgentReply {
    data class Message(val text: String) : AgentReply
    data class Tool(
        val decision: PlannerDecision,
        val raw: String,
        val plan: List<String> = emptyList()
    ) : AgentReply
}

object LocalWorkflowAgent {
    val systemPrompt = """
        You are Lumena Local Agent running on the user's Android phone.
        The application, not you, owns execution, security, retries and verification.

        RULES:
        - For a task that needs local work, return exactly ONE tool call per response.
        - Never claim that a tool ran unless a TOOL_RESULT was provided.
        - Never invent files, outputs, tests or repository state.
        - If the task has multiple steps, include a short plan of 2-6 steps in the FIRST tool call.
        - After every TOOL_RESULT choose the next single tool, or answer normally if the task is complete.
        - Keep reasons and plan steps short.
        - Use only the tools listed below.

        AVAILABLE TOOLS:
        - health {}
        - workspace.list {}
        - project.create {\"name\":\"project-name\",\"git\":\"true\"}
        - dir.create {\"path\":\"project/src\"}
        - file.read {\"path\":\"project/file.txt\"}
        - file.write {\"path\":\"project/file.txt\",\"content\":\"...\",\"overwrite\":\"true\"}
        - file.patch {\"path\":\"project/file.txt\",\"old\":\"exact old text\",\"new\":\"replacement\"}
        - git.status {\"cwd\":\"project\"}
        - git.diff {\"cwd\":\"project\"}
        - git.log {\"cwd\":\"project\"}
        - git.add {\"cwd\":\"project\",\"paths\":\".\"}
        - git.commit {\"cwd\":\"project\",\"message\":\"message\"}
        - python.run {\"script\":\"project/scripts/task.py\",\"cwd\":\"project\",\"argv\":\"--flag value\"}
        - python.syntax_check {\"script\":\"project/file.py\"}
        - python.tests {\"cwd\":\"project\",\"argv\":\"-q tests\"}
        - ollama.status {}
        - ollama.start {}
        - ollama.pull {\"model\":\"model-name\"}

        TOOL CALL FORMAT:
        {\"plan\":[\"inspect\",\"change\",\"verify\"],\"tool\":\"git.status\",\"args\":{\"cwd\":\"project\"},\"reason\":\"Inspect repository state\"}

        The plan field is required only on the first tool call for a multi-step task.
        If no tool is needed, answer normally in the user's language.
    """.trimIndent()

    fun parse(text: String): AgentReply {
        val trimmed = text.trim()
        val jsonCandidate = when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
            else -> extractFirstJsonObject(trimmed)
        }
        if (jsonCandidate == null) return AgentReply.Message(trimmed)

        return runCatching {
            val obj = JSONObject(jsonCandidate)
            val tool = obj.optString("tool").trim()
            if (tool.isBlank()) return AgentReply.Message(trimmed)
            val reason = obj.optString("reason").ifBlank { "Local model requested $tool" }
            val jsonArgs = obj.optJSONObject("args") ?: JSONObject()
            val args = buildMap {
                val keys = jsonArgs.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, jsonArgs.opt(key)?.toString().orEmpty())
                }
            }
            val plan = obj.optJSONArray("plan").toStringList()
            AgentReply.Tool(
                decision = PlannerDecision(ToolRequest(tool, args), reason),
                raw = jsonCandidate,
                plan = plan
            )
        }.getOrElse {
            AgentReply.Message(trimmed)
        }
    }

    fun toolResultMessage(tool: String, ok: Boolean, stdout: String, stderr: String, error: String?): OllamaMessage {
        val compact = buildString {
            append("TOOL_RESULT for ").append(tool).append(':').append('\n')
            append("ok=").append(ok).append('\n')
            if (!error.isNullOrBlank()) append("error=").append(error.take(2_000)).append('\n')
            if (stdout.isNotBlank()) append("stdout:\n").append(stdout.take(8_000)).append('\n')
            if (stderr.isNotBlank()) append("stderr:\n").append(stderr.take(4_000)).append('\n')
            append("Continue the SAME task. Choose exactly one next tool, or answer normally if complete. Do not repeat an unchanged failed action.")
        }
        return OllamaMessage("user", compact)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = optString(i).trim()
                if (value.isNotBlank()) add(value.take(160))
                if (size >= 6) break
            }
        }
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (ch) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
