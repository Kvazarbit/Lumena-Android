package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class FollowUpGoalTest {
    @Test fun repeatRetainsActualGoalAcrossRepeatedRetries() {
        val goal = "Знайди новини в інтернеті"
        val retry = FollowUpGoal.resolve("повтори!", goal)
        assertEquals(goal, retry)
        assertEquals(goal, FollowUpGoal.resolve("Продовжуй", retry))
    }

    @Test fun narrowNewsFollowUpsRetainPreviousResearchGoal() {
        val goal =
            "Знайди в інтернеті останні новини Python сьогодні і коротко підсумуй їх"

        for (followUp in listOf(
            "а друга новина?",
            "ще новина?",
            "наступна новина",
            "another news item?",
            "kolejna wiadomość?"
        )) {
            assertTrue(FollowUpGoal.isReference(followUp))
            assertEquals(goal, FollowUpGoal.resolve(followUp, goal))
        }
    }

    @Test fun researchAnchorSurvivesInterveningMetaChat() {
        val research =
            "Знайди в інтернеті останні новини саме про мову програмування Python"

        val first = ResearchGoalAnchor.resolve(
            text = research,
            previousGoal = null,
            researchGoal = null
        )
        assertEquals(research, first.goal)
        assertEquals(research, first.researchGoal)

        val meta = ResearchGoalAnchor.resolve(
            text = "там немає ліміту, бо ти працюєш",
            previousGoal = first.goal,
            researchGoal = first.researchGoal
        )
        assertEquals("там немає ліміту, бо ти працюєш", meta.goal)
        assertEquals(research, meta.researchGoal)

        val followUp = ResearchGoalAnchor.resolve(
            text = "а друга новина?",
            previousGoal = meta.goal,
            researchGoal = meta.researchGoal
        )
        assertEquals(research, followUp.goal)
        assertEquals(research, followUp.researchGoal)
    }

    @Test fun newsReferenceDoesNotInventResearchGoalWithoutHistory() {
        val result = ResearchGoalAnchor.resolve(
            text = "а друга новина?",
            previousGoal = null,
            researchGoal = null
        )
        assertEquals("а друга новина?", result.goal)
        assertNull(result.researchGoal)
    }

    @Test fun explicitNewGoalAndMissingHistoryArePreserved() {
        assertEquals("повтори", FollowUpGoal.resolve("повтори", null))
        assertEquals("повтори тест файлу", FollowUpGoal.resolve("повтори тест файлу", "старе завдання"))
    }
}
