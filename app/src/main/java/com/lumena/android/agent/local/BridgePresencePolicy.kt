package com.lumena.android.agent.local

/** Three bounded read-only probes; never repeat an effectful request. */
object BridgePresencePolicy {
    suspend fun reachable(probe: () -> Boolean, pause: suspend () -> Unit): Boolean {
        repeat(3) { attempt ->
            if (probe()) return true
            if (attempt < 2) pause()
        }
        return false
    }
}
