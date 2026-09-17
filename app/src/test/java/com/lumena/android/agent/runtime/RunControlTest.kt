package com.lumena.android.agent.runtime

import org.junit.Assert.*
import org.junit.Test
class RunControlTest {
    @Test fun stoppingOneRunDoesNotStopAnother() {
        val first=RunControl(); val second=RunControl()
        first.requestStopAfterStep()
        assertTrue(first.shouldStop()); assertFalse(second.shouldStop())
    }
    @Test fun stopRequestIsIdempotent() {
        val control=RunControl(); control.requestStopAfterStep(); control.requestStopAfterStep()
        assertTrue(control.shouldStop())
    }
}
