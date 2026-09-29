package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NervousSystemPolicyTest {
    private val generating = NervousFrame(
        chatGptActive = true,
        chatGptGenerating = true,
        companionBusy = false,
        uiUpdatedAt = 1000,
        outcomeKnown = true
    )

    @Test
    fun generatingUiMutationIsBlockedAsNarrowReflex() {
        val assessment = NervousSystemPolicy.classifyChatGptUiAction(
            action = SelfActionKind.CHATGPT_SEND,
            before = generating,
            after = generating,
            performed = false,
            source = NervousEvidenceSource.LOCAL_VERIFIER,
            now = 2000,
            contextKey = "chatgpt:streaming"
        )

        assertNotNull(assessment)
        assertTrue(assessment!!.reflexRequired)
        assertEquals(NervousEventKind.REFLEX_BLOCK, assessment.event.kind)
        assertEquals(CausalGrade.VERIFIED_EFFECT, assessment.event.causalGrade)
        assertTrue(assessment.event.promotionEligible())
    }

    @Test
    fun ordinaryIdleUiActionDoesNotCreateIncident() {
        val assessment = NervousSystemPolicy.classifyChatGptUiAction(
            action = SelfActionKind.CHATGPT_INSERT,
            before = generating.copy(chatGptGenerating = false),
            after = generating.copy(chatGptGenerating = false),
            performed = true,
            source = NervousEvidenceSource.LOCAL_OBSERVATION,
            now = 2000
        )
        assertNull(assessment)
    }

    @Test
    fun observedCorrelationIsNotPromotionEligible() {
        val assessment = NervousSystemPolicy.classifyChatGptUiAction(
            action = SelfActionKind.CHATGPT_ACTIVITY_OPEN,
            before = generating,
            after = generating.copy(chatGptGenerating = false),
            performed = true,
            source = NervousEvidenceSource.LOCAL_OBSERVATION,
            now = 2000,
            contextKey = "chatgpt:streaming"
        )

        assertEquals(CausalGrade.CORRELATED, assessment!!.event.causalGrade)
        assertFalse(assessment.event.promotionEligible())
    }

    @Test
    fun importedEvidenceNeverPromotesLocally() {
        val event = NervousSystemPolicy.event(
            now = 10,
            subsystem = NervousSubsystem.COMPANION,
            kind = NervousEventKind.INCIDENT,
            action = SelfActionKind.CHATGPT_SEND,
            code = "CHATGPT_GENERATING_UI_MUTATION",
            grade = CausalGrade.TRANSFERRED,
            source = NervousEvidenceSource.IMPORTED,
            locallyVerified = false,
            contextKey = "imported"
        )
        assertFalse(event.promotionEligible())
    }

    @Test
    fun transferRequiresVerifiedEvidenceAcrossDistinctContexts() {
        val events = listOf(
            NervousSystemPolicy.event(
                1, NervousSubsystem.COMPANION, NervousEventKind.INCIDENT,
                SelfActionKind.CHATGPT_SEND, code = "X",
                grade = CausalGrade.VERIFIED_EFFECT,
                source = NervousEvidenceSource.LOCAL_TEST,
                locallyVerified = true, contextKey = "a"
            ),
            NervousSystemPolicy.event(
                2, NervousSubsystem.COMPANION, NervousEventKind.INCIDENT,
                SelfActionKind.CHATGPT_SEND, code = "X",
                grade = CausalGrade.VERIFIED_EFFECT,
                source = NervousEvidenceSource.LOCAL_TEST,
                locallyVerified = true, contextKey = "a"
            ),
            NervousSystemPolicy.event(
                3, NervousSubsystem.COMPANION, NervousEventKind.INCIDENT,
                SelfActionKind.CHATGPT_SEND, code = "X",
                grade = CausalGrade.VERIFIED_EFFECT,
                source = NervousEvidenceSource.LOCAL_TEST,
                locallyVerified = true, contextKey = "b"
            )
        )
        assertTrue(NervousSystemPolicy.transferable(events, "X"))
    }

    @Test
    fun reducerIsBoundedAndDeduplicatesEvents() {
        var state = NervousSystemState()
        val first = NervousSystemPolicy.event(
            1, NervousSubsystem.RUNTIME, NervousEventKind.SENSE,
            code = "BOOT", contextKey = "one"
        )
        state = NervousSystemPolicy.record(state, first)
        state = NervousSystemPolicy.record(state, first)
        assertEquals(1, state.events.size)

        for (i in 2..1100) {
            state = NervousSystemPolicy.record(
                state,
                NervousSystemPolicy.event(
                    i.toLong(),
                    NervousSubsystem.RUNTIME,
                    NervousEventKind.SENSE,
                    code = "E$i"
                )
            )
        }
        assertEquals(NervousSystemPolicy.MAX_EVENTS, state.events.size)
    }
}
