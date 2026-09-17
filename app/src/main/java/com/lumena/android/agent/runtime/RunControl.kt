package com.lumena.android.agent.runtime

import com.lumena.android.agent.local.ToolRequest
import java.util.concurrent.atomic.AtomicBoolean

data class ActiveTool(val id: String, val request: ToolRequest)
class RunControl {
    private val stop = AtomicBoolean(false)
    @Volatile var activeTool: ActiveTool? = null
    fun requestStopAfterStep() { stop.set(true) }
    fun shouldStop(): Boolean = stop.get()
}
