package com.lumena.android.settings

import com.lumena.android.agent.core.CausalGrade
import com.lumena.android.agent.core.NervousEventKind
import com.lumena.android.agent.core.NervousEvidenceSource
import com.lumena.android.agent.core.NervousSubsystem
import com.lumena.android.agent.core.NervousSystemPolicy
import com.lumena.android.agent.core.NervousSystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NervousSystemCodecTest {
    @Test
    fun roundTripPreservesTypedCausalEvidence() {
        val event = NervousSystemPolicy.event(
            now = 1234,
            subsystem = NervousSubsystem.COMPANION,
            kind = NervousEventKind.INCIDENT,
            code = "SELF_ACTION_CORRELATED_STREAM_ABORT",
            grade = CausalGrade.CORRELATED,
            source = NervousEvidenceSource.LOCAL_OBSERVATION,
            locallyVerified = false,
            contextKey = "chatgpt:streaming"
        )
        val state = NervousSystemPolicy.record(
            NervousSystemState(),
            event
        )

        val decoded = NervousSystemCodec.decode(
            NervousSystemCodec.encode(state)
        )

        assertEquals(state, decoded)
        assertTrue(decoded.events.single().incidentCode!!.contains("STREAM_ABORT"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSchema() {
        NervousSystemCodec.decode("""{"version":99,"events":[]}""")
    }

    @Test(expected = Exception::class)
    fun rejectsMalformedJsonInsteadOfResettingHistory() {
        NervousSystemCodec.decode("{not-json")
    }
}
