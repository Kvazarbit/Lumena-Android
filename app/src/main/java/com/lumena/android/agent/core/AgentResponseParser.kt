package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class AgentResponseParser {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Suppress("UNCHECKED_CAST")
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java
        )
    )

    fun parse(raw: String): AgentDecision {
        val text = raw.trim()
        if (text.isBlank()) return AgentDecision.Reply("")

        val candidate = extractProtocolJson(text) ?: return AgentDecision.Reply(text)
        val obj = runCatching { mapAdapter.fromJson(candidate) }.getOrNull()
            ?: return AgentDecision.Reply(text)

        if (obj["done"] == true) {
            val summary = obj["summary"]?.toString()?.trim().orEmpty()
            return AgentDecision.Done(summary.ifBlank { "Task complete." })
        }

        obj["reply"]?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
            return AgentDecision.Reply(it)
        }

        // Lumena JSON: {"tool":"file.read","args":{...}}
        // Hermes/OpenAI-style content fallback: {"name":"file.read","arguments":{...}}
        val tool = (obj["tool"] ?: obj["name"])?.toString()?.trim().orEmpty()
        if (tool.isBlank()) return AgentDecision.Reply(text)

        val rawArgs = when (val value = obj["args"] ?: obj["arguments"]) {
            is Map<*, *> -> value
            is String -> runCatching { mapAdapter.fromJson(value) }.getOrNull() ?: emptyMap<String, Any?>()
            else -> emptyMap<String, Any?>()
        }
        val args = buildMap {
            rawArgs.forEach { (key, value) ->
                if (key != null && value != null) put(key.toString(), value.toString())
            }
        }
        val reason = obj["reason"]?.toString()?.trim().orEmpty()
        val plan = (obj["plan"] as? List<*>)
            .orEmpty()
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .take(6)
            .map { it.take(180) }

        return AgentDecision.ToolCall(
            tool = tool,
            args = args,
            reason = reason,
            plan = plan
        )
    }

    /**
     * Do not execute arbitrary JSON quoted in prose. Accept only an all-JSON response,
     * an explicit JSON code fence, or a Hermes <tool_call> envelope.
     */
    private fun extractProtocolJson(text: String): String? {
        if (text.startsWith("{") && text.endsWith("}")) return text

        val toolOpen = text.indexOf("<tool_call>", ignoreCase = true)
        if (toolOpen >= 0) {
            val start = toolOpen + "<tool_call>".length
            val end = text.indexOf("</tool_call>", start, ignoreCase = true)
            if (end > start) {
                return text.substring(start, end).trim().takeIf { it.startsWith("{") && it.endsWith("}") }
            }
        }

        val fences = listOf("```json", "```JSON", "```")
        for (fence in fences) {
            val open = text.indexOf(fence)
            if (open < 0) continue
            val start = open + fence.length
            val end = text.indexOf("```", start)
            if (end <= start) continue
            val body = text.substring(start, end).trim()
            if (body.startsWith("{") && body.endsWith("}")) return body
        }
        return null
    }
}
