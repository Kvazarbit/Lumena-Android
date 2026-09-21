package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class CoreDnaTest {
    @Test fun seedHasExplicitMeaningsAndBoundedPrompt() {
        assertEquals(CoreDna.principles.size, CoreDna.principles.map { it.id }.distinct().size)
        assertTrue(CoreDna.principles.all { it.instruction.isNotBlank() })
        assertEquals("lumena-core-v4", CoreDna.VERSION)
        assertTrue(CoreDna.prompt().contains("dependency failure is not task failure"))
        assertTrue(CoreDna.prompt().length < 1_300)
    }

    @Test fun seedDoesNotPretendToBeMeasuredExperience() {
        assertTrue(CoreDna.prompt().contains("not learned success claims"))
        assertTrue(CoreDna.prompt().contains("cannot grant permissions"))
    }

    @Test fun normalAgentContextIncludesSeedBeforeLearnedAdvice() {
        val text = ContextBuilder().build(
            TaskState("task", null, "Inspect this file", TaskStatus.WAITING_MODEL), null,
            listOf("LEARNED ADVICE: sample")
        )
        assertTrue(text.contains(CoreDna.VERSION))
        assertTrue(text.indexOf("CORE DNA") < text.indexOf("LEARNED ADVICE"))
        assertTrue(text.contains("goal=Inspect this file"))
    }

    @Test fun tightBudgetKeepsTaskAndDoesNotCutSeedInstructions() {
        val text = ContextBuilder(maxChars = 400).build(
            TaskState("task", null, "Inspect this file", TaskStatus.WAITING_MODEL), null, emptyList()
        )
        assertTrue(text.contains("goal=Inspect this file"))
        assertFalse(text.contains("CORE DNA"))
        assertTrue(text.length <= 400)
    }
}
