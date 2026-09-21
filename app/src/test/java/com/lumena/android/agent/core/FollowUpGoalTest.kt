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

    @Test fun explicitNewGoalAndMissingHistoryArePreserved() {
        assertEquals("повтори", FollowUpGoal.resolve("повтори", null))
        assertEquals("повтори тест файлу", FollowUpGoal.resolve("повтори тест файлу", "старе завдання"))
    }
}
