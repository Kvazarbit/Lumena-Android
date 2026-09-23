package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

enum class ProtocolFailureKind {
    SYNTAX,
    AMBIGUOUS,
    UNKNOWN_ACTION,
    UNSUPPORTED_SHAPE
}

enum class NormalizationRule {
    ACTION_REPLY,
    ACTION_DONE,
    ACTION_PARTIAL,
    ACTION_TOOL,
    REGISTERED_ACTION_ALIAS,
    HERMES_TOOL_CALL,
    REGISTERED_SINGLE_KEY_TOOL,
    ARGS_ALIAS,
    FENCED_JSON,
    SINGLE_FUNCTION_TOOL_CALL
}

sealed interface NormalizationResult {
    data class Canonical(
        val json: String,
        val changed: Boolean,
        val rule: NormalizationRule? = null
    ) : NormalizationResult

    data class PlainText(val text: String) : NormalizationResult

    data class Failure(
        val kind: ProtocolFailureKind,
        val reason: String
    ) : NormalizationResult
}

/**
 * Deterministic compatibility boundary between fallible local-model output and
 * the strict agent protocol.
 *
 * This layer may only normalize shapes that map unambiguously to existing
 * AgentDecision forms and already-registered tools. It never grants authority,
 * changes tool risk, bypasses confirmation, or executes anything.
 */
class ProtocolNormalizer {
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

    private data class Envelope(
        val json: String,
        val changed: Boolean,
        val rule: NormalizationRule? = null
    )

    fun normalize(raw: String): NormalizationResult {
        val text = raw.trim()
        if (text.isEmpty()) {
            return NormalizationResult.Failure(
                ProtocolFailureKind.SYNTAX,
                "Empty model output"
            )
        }

        val envelope = extractStrictEnvelope(text)
            ?: return if (looksProtocolLike(text)) {
                NormalizationResult.Failure(
                    ProtocolFailureKind.SYNTAX,
                    "Protocol-looking output is not one valid JSON envelope"
                )
            } else {
                NormalizationResult.PlainText(text)
            }

        val obj = runCatching { mapAdapter.fromJson(envelope.json) }
            .getOrNull()
            ?: return NormalizationResult.Failure(
                ProtocolFailureKind.SYNTAX,
                "Protocol envelope contains invalid JSON"
            )

        val action = obj["action"]
            ?.toString()
            ?.trim()
            ?.lowercase()

        when (action) {
            "reply" -> return canonicalReply(
                obj["reply"] ?: obj["result"] ?: obj["content"],
                envelope,
                NormalizationRule.ACTION_REPLY
            )

            "done" -> return canonicalDone(
                obj["summary"] ?: obj["result"] ?: obj["reply"],
                envelope
            )

            "partial" -> return canonicalPartial(
                obj["summary"] ?: obj["result"] ?: obj["reply"],
                envelope
            )

            "tool", null, "" -> Unit

            else -> {
                if (ToolRegistry.get(action) == null) {
                    return NormalizationResult.Failure(
                        ProtocolFailureKind.UNKNOWN_ACTION,
                        "Unknown model action: $action"
                    )
                }
            }
        }

        if (obj["partial"] == true) {
            val summary = obj["summary"]?.toString()?.trim().orEmpty()
                .ifBlank { "Task incomplete; inspect the recorded results before continuing." }
            return canonicalJson(
                linkedMapOf("partial" to true, "summary" to summary),
                envelope,
                envelope.rule
            )
        }
        if (obj["done"] == true) {
            val summary = obj["summary"]?.toString()?.trim().orEmpty()
                .ifBlank { "Task complete." }
            return canonicalJson(
                linkedMapOf("done" to true, "summary" to summary),
                envelope,
                envelope.rule
            )
        }
        obj["reply"]?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { reply ->
            return canonicalJson(
                linkedMapOf("reply" to reply),
                envelope,
                envelope.rule
            )
        }

        if (
            obj["tool"] == null &&
            obj["name"] == null &&
            action.isNullOrBlank()
        ) {
            normalizeSingleFunctionWrapper(obj, envelope)?.let { return it }
        }

        val registeredShorthand = obj.entries.filter {
            ToolRegistry.get(it.key) != null && it.value is Map<*, *>
        }
        if (
            obj["tool"] == null &&
            obj["name"] == null &&
            action.isNullOrBlank() &&
            registeredShorthand.size > 1
        ) {
            return NormalizationResult.Failure(
                ProtocolFailureKind.AMBIGUOUS,
                "More than one registered tool appears in a shorthand envelope"
            )
        }

        val shorthand = registeredShorthand.singleOrNull()
            ?.takeIf { obj.size == 1 }

        val proposedTool = when {
            obj["tool"] != null -> obj["tool"].toString().trim()
            obj["name"] != null -> obj["name"].toString().trim()
            !action.isNullOrBlank() && action != "tool" -> action
            shorthand != null -> shorthand.key
            else -> ""
        }

        if (proposedTool.isBlank()) {
            return NormalizationResult.Failure(
                ProtocolFailureKind.UNSUPPORTED_SHAPE,
                "Protocol JSON does not contain a canonical action"
            )
        }

        val canonicalTool = ToolRegistry.canonicalize(proposedTool)
        if (ToolRegistry.get(canonicalTool) == null) {
            return NormalizationResult.Failure(
                ProtocolFailureKind.UNKNOWN_ACTION,
                "Unregistered tool: $proposedTool"
            )
        }

        val rawArgsSource = when {
            obj.containsKey("args") -> obj["args"]
            obj.containsKey("arguments") -> obj["arguments"]
            obj.containsKey("parameters") -> obj["parameters"]
            obj.containsKey("input") -> obj["input"]
            shorthand != null -> shorthand.value
            else -> emptyMap<String, Any?>()
        }
        val rawArgs = when (rawArgsSource) {
            is Map<*, *> -> rawArgsSource
            is String -> runCatching { mapAdapter.fromJson(rawArgsSource) }.getOrNull()
                ?: return NormalizationResult.Failure(
                    ProtocolFailureKind.SYNTAX,
                    "Tool arguments string is not a JSON object"
                )
            null -> emptyMap<String, Any?>()
            else -> return NormalizationResult.Failure(
                ProtocolFailureKind.UNSUPPORTED_SHAPE,
                "Tool arguments must be a JSON object"
            )
        }

        val canonical = linkedMapOf<String, Any?>(
            "tool" to canonicalTool,
            "args" to rawArgs
        )
        obj["reason"]?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
            canonical["reason"] = it
        }
        (obj["plan"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            ?.take(6)
            ?.takeIf { it.isNotEmpty() }
            ?.let { canonical["plan"] = it }

        val rule = when {
            envelope.rule == NormalizationRule.HERMES_TOOL_CALL ->
                NormalizationRule.HERMES_TOOL_CALL
            shorthand != null ->
                NormalizationRule.REGISTERED_SINGLE_KEY_TOOL
            !action.isNullOrBlank() && action != "tool" ->
                NormalizationRule.REGISTERED_ACTION_ALIAS
            action == "tool" ->
                NormalizationRule.ACTION_TOOL
            obj.containsKey("arguments") || obj.containsKey("parameters") || obj.containsKey("input") ->
                NormalizationRule.ARGS_ALIAS
            proposedTool != canonicalTool ->
                NormalizationRule.REGISTERED_ACTION_ALIAS
            else -> envelope.rule
        }

        return canonicalJson(canonical, envelope, rule)
    }

    private fun normalizeSingleFunctionWrapper(
        obj: Map<String, Any?>,
        envelope: Envelope
    ): NormalizationResult? {
        val wrapped: Map<*, *> = when {
            obj.containsKey("tool_calls") -> {
                val calls = obj["tool_calls"] as? List<*>
                    ?: return NormalizationResult.Failure(
                        ProtocolFailureKind.UNSUPPORTED_SHAPE,
                        "tool_calls must be a JSON array"
                    )
                if (calls.size != 1) {
                    return NormalizationResult.Failure(
                        ProtocolFailureKind.AMBIGUOUS,
                        "tool_calls must contain exactly one function call"
                    )
                }
                val item = calls.single() as? Map<*, *>
                    ?: return NormalizationResult.Failure(
                        ProtocolFailureKind.UNSUPPORTED_SHAPE,
                        "tool_calls item must be a JSON object"
                    )
                (item["function"] as? Map<*, *>) ?: item
            }

            obj.containsKey("tool_call") -> {
                val item = obj["tool_call"] as? Map<*, *>
                    ?: return NormalizationResult.Failure(
                        ProtocolFailureKind.UNSUPPORTED_SHAPE,
                        "tool_call must be a JSON object"
                    )
                (item["function"] as? Map<*, *>) ?: item
            }

            obj.containsKey("function") -> {
                obj["function"] as? Map<*, *>
                    ?: return NormalizationResult.Failure(
                        ProtocolFailureKind.UNSUPPORTED_SHAPE,
                        "function must be a JSON object"
                    )
            }

            else -> return null
        }

        val proposedTool = wrapped["name"]?.toString()?.trim().orEmpty()
        if (proposedTool.isBlank()) {
            return NormalizationResult.Failure(
                ProtocolFailureKind.UNSUPPORTED_SHAPE,
                "function wrapper has no tool name"
            )
        }

        val canonicalTool = ToolRegistry.canonicalize(proposedTool)
        if (ToolRegistry.get(canonicalTool) == null) {
            return NormalizationResult.Failure(
                ProtocolFailureKind.UNKNOWN_ACTION,
                "Unregistered tool in function wrapper: $proposedTool"
            )
        }

        val rawArgsSource =
            wrapped["arguments"] ?: wrapped["args"] ?: wrapped["parameters"] ?: emptyMap<String, Any?>()
        val rawArgs = when (rawArgsSource) {
            is Map<*, *> -> rawArgsSource
            is String -> runCatching { mapAdapter.fromJson(rawArgsSource) }.getOrNull()
                ?: return NormalizationResult.Failure(
                    ProtocolFailureKind.SYNTAX,
                    "Function arguments string is not a JSON object"
                )
            null -> emptyMap<String, Any?>()
            else -> return NormalizationResult.Failure(
                ProtocolFailureKind.UNSUPPORTED_SHAPE,
                "Function arguments must be a JSON object"
            )
        }

        val canonical = linkedMapOf<String, Any?>(
            "tool" to canonicalTool,
            "args" to rawArgs
        )
        obj["reason"]?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
            canonical["reason"] = it
        }

        return canonicalJson(
            canonical,
            envelope,
            NormalizationRule.SINGLE_FUNCTION_TOOL_CALL
        )
    }

    private fun canonicalReply(
        value: Any?,
        envelope: Envelope,
        rule: NormalizationRule
    ): NormalizationResult {
        val text = value?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            return NormalizationResult.Failure(
                ProtocolFailureKind.UNSUPPORTED_SHAPE,
                "reply action has no reply/result/content"
            )
        }
        return canonicalJson(linkedMapOf("reply" to text), envelope, rule)
    }

    private fun canonicalDone(
        value: Any?,
        envelope: Envelope
    ): NormalizationResult {
        val summary = value?.toString()?.trim().orEmpty()
            .ifBlank { "Task complete." }
        return canonicalJson(
            linkedMapOf("done" to true, "summary" to summary),
            envelope,
            NormalizationRule.ACTION_DONE
        )
    }

    private fun canonicalPartial(
        value: Any?,
        envelope: Envelope
    ): NormalizationResult {
        val summary = value?.toString()?.trim().orEmpty()
            .ifBlank { "Task incomplete; inspect the recorded results before continuing." }
        return canonicalJson(
            linkedMapOf("partial" to true, "summary" to summary),
            envelope,
            NormalizationRule.ACTION_PARTIAL
        )
    }

    private fun canonicalJson(
        obj: Map<String, Any?>,
        envelope: Envelope,
        rule: NormalizationRule?
    ): NormalizationResult.Canonical {
        val json = mapAdapter.toJson(obj)
        return NormalizationResult.Canonical(
            json = json,
            changed = envelope.changed || json != envelope.json || rule != null,
            rule = rule
        )
    }

    private fun extractStrictEnvelope(text: String): Envelope? {
        if (text.startsWith("{") && text.endsWith("}")) {
            return Envelope(text, changed = false)
        }

        if (text.startsWith("<tool_call>", ignoreCase = true)) {
            val start = "<tool_call>".length
            val end = text.indexOf("</tool_call>", start, ignoreCase = true)
            if (end > start && text.substring(end + "</tool_call>".length).isBlank()) {
                val body = text.substring(start, end).trim()
                if (body.startsWith("{") && body.endsWith("}")) {
                    return Envelope(
                        json = body,
                        changed = true,
                        rule = NormalizationRule.HERMES_TOOL_CALL
                    )
                }
            }
            return null
        }

        val fences = listOf("```json", "```JSON", "```")
        for (fence in fences) {
            if (!text.startsWith(fence)) continue
            val start = fence.length
            val end = text.indexOf("```", start)
            if (end <= start) return null
            if (text.substring(end + 3).isNotBlank()) return null
            val body = text.substring(start, end).trim()
            if (body.startsWith("{") && body.endsWith("}")) {
                return Envelope(
                    json = body,
                    changed = true,
                    rule = NormalizationRule.FENCED_JSON
                )
            }
            return null
        }

        return null
    }

    private fun looksProtocolLike(text: String): Boolean =
        text.startsWith("{") ||
            text.startsWith("```json", ignoreCase = true) ||
            text.startsWith("```") ||
            text.startsWith("<tool_call>", ignoreCase = true)
}
