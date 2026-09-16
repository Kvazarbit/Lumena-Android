package com.lumena.android.ollama

import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.ToolRequest
import org.json.JSONObject

sealed interface AgentReply {
    data class Message(val text: String) : AgentReply
    data class Tool(val decision: PlannerDecision, val raw: String) : AgentReply
}

object LocalWorkflowAgent {
    val systemPrompt = """
        You are Lumena Local Agent running on the user's Android phone.
        Be concise, practical and transparent about what you can and cannot do.

        Available local tools:
        - health {}
        - file.read {\"path\":\"relative/path.txt\"}
        - git.status {\"cwd\":\"project\"}
        - git.diff {\"cwd\":\"project\"}
        - git.log {\"cwd\":\"project\"}
        - python.run {\"script\":\"scripts/task.py\",\"cwd\":\"project\",\"argv\":\"--flag value\"}
        - ollama.status {}
        - ollama.start {}
        - ollama.pull {\"model\":\"model-name\"}

        If you need exactly one tool, return ONLY valid JSON in this shape:
        {\"tool\":\"git.status\",\"args\":{\"cwd\":\"project\"},\"reason\":\"Why this tool is needed\"}

        Otherwise answer normally in the user's language.
        Never invent a tool result. Never request commands outside the allow-list.
    """.trimIndent()

    fun parse(text: String): AgentReply {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return AgentReply.Message(trimmed)
        }

        return runCatching {
            val obj = JSONObject(trimmed)
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
            AgentReply.Tool(
                decision = PlannerDecision(ToolRequest(tool, args), reason),
                raw = trimmed
            )
        }.getOrElse {
            AgentReply.Message(trimmed)
        }
    }

    fun toolResultMessage(tool: String, ok: Boolean, stdout: String, stderr: String, error: String?): OllamaMessage {
        val compact = buildString {
            append("Tool result for ").append(tool).append(':').append('\n')
            append("ok=").append(ok).append('\n')
            if (!error.isNullOrBlank()) append("error=").append(error).append('\n')
            if (stdout.isNotBlank()) append("stdout:\n").append(stdout.take(16_000)).append('\n')
            if (stderr.isNotBlank()) append("stderr:\n").append(stderr.take(8_000)).append('\n')
            append("Continue the task. If no further tool is essential, answer the user normally.")
        }
        return OllamaMessage("user", compact)
    }
}
