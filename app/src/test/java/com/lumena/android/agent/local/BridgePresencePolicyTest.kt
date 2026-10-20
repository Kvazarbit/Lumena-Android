package com.lumena.android.agent.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BridgePresencePolicyTest {
    @Test fun transientMissDoesNotDemandAutostart() = runBlocking {
        var probes = 0
        var pauses = 0
        assertTrue(BridgePresencePolicy.reachable({ ++probes >= 2 }, { pauses++ }))
        assertEquals(2, probes)
        assertEquals(1, pauses)
    }
    @Test fun unreachableBridgeTerminatesAfterThreeReadOnlyProbes() = runBlocking {
        var probes = 0
        var pauses = 0
        assertFalse(BridgePresencePolicy.reachable({ probes++; false }, { pauses++ }))
        assertEquals(3, probes)
        assertEquals(2, pauses)
    }
}
