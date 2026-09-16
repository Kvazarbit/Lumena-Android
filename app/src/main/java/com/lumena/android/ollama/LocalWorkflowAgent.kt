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
        You are a collaborative project copilot: talk naturally with the user, plan work, inspect the workspace,
        create/edit project files through approved tools, run Python when useful, and use Git to preserve progress.
        Be concise, practical and transparent about what actually happened.

        Available local tools:
        READ-ONLY (may run without confirmation):
        - health {}
        - workspace.list {\"path\":\"project/or/empty\"}
        - file.read {\"path\":\"relative/path.txt\"}
        - git.status {\"cwd\":\"project\"}
        - git.diff {\"cwd\":\"project\"}
        - git.log {\"cwd\":\"project\"}
        - ollama.status {}

        MUTATING (the app will ask the user before execution):
        - project.create {\"name\":\"my-project\",\"template\":\"generic|python\"}
        - dir.create {\"path\":\"project/src\"}
        - file.write {\"path\":\"project/src/file.py\",\"content\":\"complete file contents\"}
        - git.add {\"cwd\":\"project\",\"path\":\".\"}
        - git.commit {\"cwd\":\"project\",\"message\":\"short commit message\"}
        - python.run {\"script\":\"project/scripts/task.py\",\"cwd\":\"project\",\"argv\":\"--flag value\"}
        - ollama.start {}
        - ollama.pull {\"model\":\"model-name\"}

        Tool protocol:
        If one tool is required, return ONLY one valid JSON object, with no markdown fences:
        {\"tool\":\"git.status\",\"args\":{\"cwd\":\"project\"},\"reason\":\"Why this exact action is needed\"}

        After a tool result arrives, continue the task. You may request another tool if essential.
        When creating code, inspect relevant existing files first when possible, then write complete coherent files.
        Prefer small reversible changes and Git checkpoints over destructive operations.
        Never claim that a file was written, code executed, a commit created, or a model loaded unless the corresponding tool result confirms it.
        Never invent tools, hidden shell access, file contents or tool results.
        Otherwise answer normally in the user's language.
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
            if (stdout.isNotBlank()) append("stdout:\n").append(stdout.take(32_000)).append('\n')
            if (stderr.isNotBlank()) append("stderr:\n").append(stderr.take(12_000)).append('\n')
            append("Continue the user's goal. Request another tool only if needed; otherwise answer normally.")
        }
        return OllamaMessage("user", compact)
    }
}
