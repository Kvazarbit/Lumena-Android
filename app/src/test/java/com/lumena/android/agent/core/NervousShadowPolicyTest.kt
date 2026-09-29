package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NervousShadowPolicyTest {
    private fun event(
        at: Long,
        context: String,
        source: NervousEvidenceSource = NervousEvidenceSource.LOCAL_TEST,
        grade: CausalGrade = CausalGrade.VERIFIED_EFFECT,
        verified: Boolean = true
    ) = NervousSystemPolicy.event(
        now = at,
        subsystem = NervousSubsystem.COMPANION,
        kind = NervousEventKind.INCIDENT,
        action = SelfActionKind.CHATGPT_SEND,
        code = "CHATGPT_GENERATING_UI_MUTATION",
        grade = grade,
        source = source,
        locallyVerified = verified,
        contextKey = context
    )

    @Test
    fun oneIncidentNeverCreatesShadowRule() {
        assertNull(
            NervousShadowPolicy.candidate(
                events = listOf(event(1, "a")),
                incidentCode = "CHATGPT_GENERATING_UI_MUTATION",
                ruleId = "companion.no-ui-mutation-while-generating",
                statement = "Do not mutate ChatGPT UI while generation is active."
            )
        )
    }

    @Test
    fun correlatedOrImportedEvidenceDoesNotCount() {
        val events = listOf(
            event(
                1, "a",
                source = NervousEvidenceSource.LOCAL_OBSERVATION,
                grade = CausalGrade.CORRELATED,
                verified = false
            ),
            event(
                2, "b",
                source = NervousEvidenceSource.IMPORTED,
                grade = CausalGrade.TRANSFERRED,
                verified = false
            ),
            event(3, "c")
        )

        assertNull(
            NervousShadowPolicy.candidate(
                events,
                "CHATGPT_GENERATING_UI_MUTATION",
                "companion.no-ui-mutation-while-generating",
                "Do not mutate ChatGPT UI while generation is active."
            )
        )
    }

    @Test
    fun verifiedMultiContextEvidenceCreatesInactiveShadowOnly() {
        val candidate = NervousShadowPolicy.candidate(
            events = listOf(
                event(1, "chatgpt:pl"),
                event(2, "chatgpt:uk"),
                event(3, "chatgpt:pl")
            ),
            incidentCode = "CHATGPT_GENERATING_UI_MUTATION",
            ruleId = "companion.no-ui-mutation-while-generating",
            statement = "Do not mutate ChatGPT UI while generation is active."
        )

        assertEquals("SHADOW", candidate!!.stage)
        assertFalse(candidate.active)
        assertEquals(3, candidate.verifiedEvidenceIds.size)
        assertEquals(2, candidate.distinctContexts)
        assertEquals(CausalGrade.TRANSFERRED, candidate.causalGrade)
    }

    @Test
    fun contestedEvidenceIsExcluded() {
        val candidate = NervousShadowPolicy.candidate(
            events = listOf(
                event(1, "a"),
                event(2, "b"),
                event(3, "c", grade = CausalGrade.CONTESTED)
            ),
            incidentCode = "CHATGPT_GENERATING_UI_MUTATION",
            ruleId = "companion.no-ui-mutation-while-generating",
            statement = "Do not mutate ChatGPT UI while generation is active."
        )
        assertNull(candidate)
    }
}
