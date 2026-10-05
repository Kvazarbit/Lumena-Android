package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePrinciplesTest {
    @Test fun candidatesFitOneConstitutionLine() {
        val line = CandidatePrinciples.promptLine()
        // ContextBuilder truncates each constitution line at 900 characters.
        assertTrue("length=${line.length}", line.length <= 900)
        CandidatePrinciples.candidates.forEach { assertTrue(it.id, line.contains("${it.id}: ")) }
        assertTrue(line.contains("not permission"))
    }

    @Test fun candidatesAreGovernedNotHardLaw() {
        assertEquals(AdvisoryLayer.PRINCIPLES, AdvisoryLayer.fromKey("principles"))
        val coreIds = CoreDna.principles.map { it.id }.toSet()
        // A candidate must not silently shadow an existing Core DNA principle id.
        assertTrue(CandidatePrinciples.candidates.none { it.id in coreIds })
    }
}
