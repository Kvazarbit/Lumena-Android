package com.lumena.android.ollama

import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.ToolRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** AUTO probes the selected model; JSON remains an explicit compatibility mode. */
enum class ToolMode { AUTO, NATIVE, JSON }

data class OllamaFunctionCall(val name: String, val arguments: Map<String, Any?> = emptyMap())
data class OllamaToolCall(val function: OllamaFunctionCall)
data class OllamaFunctionSchema(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)
data class OllamaToolSchema(val type: String = "function", val function: OllamaFunctionSchema)
data class OllamaTurn(val message: OllamaMessage, val doneReason: String? = null)

sealed interface NativeAction {
    data class Tool(val decision: AgentDecision.ToolCall) : NativeAction
    data class Plan(val steps: List<String>) : NativeAction
    data class Finish(val summary: String) : NativeAction
    data class Invalid(val reason: String) : NativeAction
}

object NativeTools {
    private val optionalArgs = mapOf(
        "project.create" to setOf("git"),
        "file.write" to setOf("overwrite"),
        "python.run" to setOf("cwd", "argv", "timeout"),
        "python.syntax_check" to setOf("cwd", "timeout"),
        "python.tests" to setOf("argv", "timeout"),
        "git.status" to setOf("timeout"),
        "git.diff" to setOf("timeout"),
        "git.log" to setOf("timeout"),
        "git.add" to setOf("timeout"),
        "git.commit" to setOf("timeout"),
        "ollama.pull" to setOf("timeout")
    )
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val decisionAdapter = moshi.adapter(AgentDecision.ToolCall::class.java)
    private val mapAdapter = moshi.adapter(Map::class.java)

    fun schemas(): List<OllamaToolSchema> = ToolRegistry.all().map { spec ->
        val properties = (spec.requiredArgs + optionalArgs[spec.name].orEmpty()).associateWith {
            mapOf<String, Any>("type" to "string")
        }
        OllamaToolSchema(function = OllamaFunctionSchema(
            spec.name, spec.description,
            mapOf("type" to "object", "properties" to properties,
                "required" to spec.requiredArgs.sorted(), "additionalProperties" to false)
        ))
    } + listOf(
        OllamaToolSchema(function = OllamaFunctionSchema("agent.plan",
            "Store a short public plan. This does not execute or verify any step.",
            mapOf("type" to "object", "properties" to mapOf("steps" to mapOf(
                "type" to "array", "items" to mapOf("type" to "string"), "minItems" to 1, "maxItems" to 6
            )), "required" to listOf("steps"), "additionalProperties" to false))),
        OllamaToolSchema(function = OllamaFunctionSchema("agent.finish",
            "Request completion after inspecting real results. The controller may reject completion.",
            mapOf("type" to "object", "properties" to mapOf("summary" to mapOf("type" to "string")),
                "required" to listOf("summary"), "additionalProperties" to false)))
    )

    fun decode(message: OllamaMessage): NativeAction {
        val calls = message.tool_calls.orEmpty()
        if (calls.size != 1) return NativeAction.Invalid("Return exactly one tool call per turn; none of this batch was executed.")
        val fn = calls.single().function
        if (fn.name == "agent.plan") {
            val steps = fn.arguments["steps"] as? List<*>
                ?: return NativeAction.Invalid("agent.plan requires a steps array.")
            if (fn.arguments.keys != setOf("steps") || steps.size !in 1..6 ||
                steps.any { it !is String || it.isBlank() || it.length > 180 }) {
                return NativeAction.Invalid("Plan must have 1-6 non-empty steps, each at most 180 characters.")
            }
            return NativeAction.Plan(steps.filterIsInstance<String>())
        }
        if (fn.name == "agent.finish") {
            val summary = fn.arguments["summary"] as? String
            return if (fn.arguments.keys == setOf("summary") && !summary.isNullOrBlank() && summary.length <= 4000)
                NativeAction.Finish(summary) else NativeAction.Invalid("agent.finish requires a non-empty summary, up to 4000 characters.")
        }
        val spec = ToolRegistry.get(fn.name) ?: return NativeAction.Invalid("Unknown tool: ${fn.name}")
        if (fn.arguments.keys.any { it !in spec.requiredArgs + optionalArgs[spec.name].orEmpty() } ||
            fn.arguments.values.any { it !is String }) {
            return NativeAction.Invalid("Use only the declared string arguments for ${spec.name}.")
        }
        val call = AgentDecision.ToolCall(spec.name,
            fn.arguments.mapValues { it.value as String }, "Native tool request: ${spec.name}")
        val check = ToolRegistry.validate(call)
        return if (check.allowed) NativeAction.Tool(call)
        else NativeAction.Invalid(check.error ?: "Invalid tool arguments")
    }

    fun controllerJson(action: NativeAction): String = when (action) {
        is NativeAction.Tool -> decisionAdapter.toJson(action.decision)
        is NativeAction.Finish -> mapAdapter.toJson(mapOf("done" to true, "summary" to action.summary))
        else -> error("This action is not a controller decision")
    }

    fun result(name: String, ok: Boolean, stdout: String = "", stderr: String = "", error: String? = null): OllamaMessage =
        OllamaMessage("tool", mapAdapter.toJson(mapOf(
            "ok" to ok, "stdout" to stdout, "stderr" to stderr, "error" to error
        )), tool_name = name)
}

/** Never split an assistant tool-call / tool-result group, or silently slice a JSON argument. */
object OllamaContextWindow {
    fun compact(messages: List<OllamaMessage>, maxChars: Int = 14000): List<OllamaMessage> {
        val system = messages.firstOrNull { it.role == "system" }
        val groups = mutableListOf<MutableList<OllamaMessage>>()
        for (message in messages.filterNot { it.role == "system" }) {
            if (message.role == "tool" && groups.lastOrNull()?.firstOrNull()?.tool_calls?.isNotEmpty() == true) {
                groups.last().add(message)
            } else if (message.role != "tool") {
                groups.add(mutableListOf(message))
            }
            // An orphan native result is not a valid conversation and is never sent by itself.
        }
        fun cost(m: OllamaMessage) = m.content.length + m.tool_calls.orEmpty().sumOf {
            it.function.name.length + it.function.arguments.toString().length
        } + 80
        var used = system?.let(::cost) ?: 0
        require(used <= maxChars) { "The task/system description exceeds the context budget; shorten the task instead of losing its goal." }
        val recent = mutableListOf<List<OllamaMessage>>()
        for (group in groups.asReversed()) {
            val n = group.sumOf(::cost)
            if (used + n > maxChars) {
                // Keep an explicit marker, not a fake partial tool call or a silent loss of instructions.
                if (recent.isEmpty()) recent.add(listOf(OllamaMessage("user",
                    "[The latest exchange is too large for this context. Its full text is retained in chat history. Inspect smaller file ranges; do not assume omitted output succeeded.]")))
                break
            }
            recent.add(group)
            used += n
        }
        return listOfNotNull(system) + recent.asReversed().flatten()
    }

    fun asJsonProtocol(messages: List<OllamaMessage>): List<OllamaMessage> = messages.map { m ->
        when {
            !m.tool_calls.isNullOrEmpty() -> OllamaMessage("assistant", m.tool_calls.joinToString("\n") {
                "Historical tool request ${it.function.name}: ${it.function.arguments}"
            })
            m.role == "tool" -> OllamaMessage("user", "Historical TOOL_RESULT ${m.tool_name}: ${m.content}")
            else -> m.copy(tool_calls = null, tool_name = null)
        }
    }
}
