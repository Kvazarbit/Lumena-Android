package com.lumena.android.agent.core

/** A transport replay is allowed only for a registry-confirmed read operation. */
object BridgeTransportPolicy {
    fun canRetry(tool: String): Boolean = ToolRegistry.get(tool)?.risk == ToolRisk.READ_ONLY
    fun outcomeUnknown(tool: String): Boolean = !canRetry(tool)
}
