package com.lumena.android.agent.core

/**
 * Transport replay is allowed only for operations whose external effect is
 * provably read-only.
 *
 * Internal System-1 probes are intentionally not registered in ToolRegistry:
 * the deliberative model must not gain a new executable tool surface just
 * because Lumena's controller can query its own local decision sidecar.
 */
object BridgeTransportPolicy {
    private val internalReadOnly = setOf(
        "laya.status",
        "laya.predict"
    )

    fun canRetry(tool: String): Boolean {
        val canonical = ToolRegistry.canonicalize(tool)
        return canonical in internalReadOnly ||
            ToolRegistry.get(canonical)?.risk == ToolRisk.READ_ONLY
    }

    fun outcomeUnknown(tool: String): Boolean = !canRetry(tool)
}
