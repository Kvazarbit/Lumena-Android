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
    private val anyAdapter = moshi.adapter(Any::class.java)

    fun parse(raw: String): AgentDecision {
        val text = raw.trim()
        if (text.isBlank()) return AgentDecision.Reply("")

        val candidate = extractProtocolJson(text) ?: return AgentDecision.Reply(text)
        val obj = runCatching { mapAdapter.fromJson(candidate) }.getOrNull()
            ?: return AgentDecision.Reply(text)

        // Compatibility envelope used by several local instruct models:
        // {"action":"reply","result":"..."}, {"action":"done","result":"..."},
        // {"action":"partial","result":"..."}, or {"action":"tool",...}.
        // Normalize only explicitly known actions; never execute an unknown action.
        when (obj["action"]?.toString()?.trim()?.lowercase()) {
            "reply" -> {
                val value = (obj["reply"] ?: obj["result"] ?: obj["content"])
                    ?.toString()?.trim().orEmpty()
                if (value.isNotBlank()) return AgentDecision.Reply(value)
            }
            "done" -> {
                val value = (obj["summary"] ?: obj["result"] ?: obj["reply"])
                    ?.toString()?.trim().orEmpty()
                return AgentDecision.Done(value.ifBlank { "Task complete." })
            }
            "partial" -> {
                val value = (obj["summary"] ?: obj["result"] ?: obj["reply"])
                    ?.toString()?.trim().orEmpty()
                return AgentDecision.Partial(
                    value.ifBlank { "Task incomplete; inspect the recorded results before continuing." }
                )
            }
            "tool" -> Unit
            null, "" -> Unit
            else -> return AgentDecision.Reply(text)
        }

        if (obj["partial"] == true) {
            return AgentDecision.Partial(obj["summary"]?.toString()?.trim().orEmpty()
                .ifBlank { "Task incomplete; inspect the recorded results before continuing." })
        }
        if (obj["done"] == true) {
            val summary = obj["summary"]?.toString()?.trim().orEmpty()
            return AgentDecision.Done(summary.ifBlank { "Task complete." })
        }

        obj["reply"]?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
            return AgentDecision.Reply(it)
        }

        // Lumena JSON: {"tool":"file.read","args":{...}}
        // Hermes/OpenAI-style content fallback: {"name":"file.read","arguments":{...}}
        // Some small models emit {"web.search":{...}}. Normalize only a single
        // registered tool with object arguments; controller validation still applies.
        val shorthand = obj.entries.singleOrNull()?.takeIf {
            ToolRegistry.get(it.key) != null && it.value is Map<*, *>
        }
        val tool = (obj["tool"] ?: obj["name"] ?: shorthand?.key)?.toString()?.trim().orEmpty()
        if (tool.isBlank()) return AgentDecision.Reply(text)

        val rawArgs = when (val value = obj["args"] ?: obj["arguments"] ?: shorthand?.value) {
            is Map<*, *> -> value
            is String -> runCatching { mapAdapter.fromJson(value) }.getOrNull() ?: emptyMap<String, Any?>()
            else -> emptyMap<String, Any?>()
        }
        val args = buildMap {
            rawArgs.forEach { (key, value) ->
                if (key == null || value == null) return@forEach
                val rendered = when (value) {
                    is Map<*, *>, is List<*> -> anyAdapter.toJson(value)
                    else -> value.toString()
                }
                put(key.toString(), rendered)
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
