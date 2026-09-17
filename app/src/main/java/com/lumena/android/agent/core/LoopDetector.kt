package com.lumena.android.agent.core

class LoopDetector(
    private val maxIdenticalAttempts: Int = 3
) {
    private var lastSignature: String? = null
    private var identicalCount: Int = 0

    fun record(call: AgentDecision.ToolCall): LoopState {
        val signature = signature(call)
        if (signature == lastSignature) {
            identicalCount++
        } else {
            lastSignature = signature
            identicalCount = 1
        }

        return if (identicalCount >= maxIdenticalAttempts) {
            LoopState.Detected(signature, identicalCount)
        } else {
            LoopState.Ok(signature, identicalCount)
        }
    }

    fun reset() {
        lastSignature = null
        identicalCount = 0
    }

    private fun signature(call: AgentDecision.ToolCall): String {
        val canonical = ToolRegistry.canonicalize(call.tool)
        val normalizedArgs = call.args
            .toSortedMap()
            .entries
            .joinToString("&") { (key, value) -> "$key=${value.trim()}" }
        return "$canonical|$normalizedArgs"
    }
}

sealed interface LoopState {
    val signature: String
    val count: Int

    data class Ok(
        override val signature: String,
        override val count: Int
    ) : LoopState

    data class Detected(
        override val signature: String,
        override val count: Int
    ) : LoopState
}
