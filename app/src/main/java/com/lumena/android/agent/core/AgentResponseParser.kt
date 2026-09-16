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

        val candidate = when {
            text.startsWith("{") && text.endsWith("}") -> text
            else -> extractFirstJsonObject(text)
        } ?: return AgentDecision.Reply(text)

        val obj = runCatching { mapAdapter.fromJson(candidate) }.getOrNull()
            ?: return AgentDecision.Reply(text)

        if (obj["done"] == true) {
            val summary = obj["summary"]?.toString()?.trim().orEmpty()
            return AgentDecision.Done(summary.ifBlank { "Task complete." })
        }

        val tool = obj["tool"]?.toString()?.trim().orEmpty()
        if (tool.isBlank()) return AgentDecision.Reply(text)

        val rawArgs = obj["args"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val args = buildMap {
            rawArgs.forEach { (key, value) ->
                if (key != null && value != null) put(key.toString(), value.toString())
            }
        }
        val reason = obj["reason"]?.toString()?.trim().orEmpty()

        return AgentDecision.ToolCall(
            tool = tool,
            args = args,
            reason = reason
        )
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
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
}
