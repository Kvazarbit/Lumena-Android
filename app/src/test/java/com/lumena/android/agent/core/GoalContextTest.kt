package com.lumena.android.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalContextTest {
    @Test
    fun longWorkGoalKeepsRootAndNewestDirective() {
        val root =
            "ROOT_AQUARIUM_REQUIREMENT " +
                "x".repeat(1_400)
        val latest =
            "LATEST_DIRECTIVE_KEEP_FISH_JUMP_PHYSICS"
        val clipped =
            GoalContext.clip(
                root +
                    "\nWORK THREAD DIRECTIVES:\n- " +
                    latest,
                420
            )

        assertTrue(
            clipped.startsWith(
                "ROOT_AQUARIUM_REQUIREMENT"
            )
        )
        assertTrue(
            clipped.contains(latest)
        )
        assertTrue(
            clipped.contains(
                "goal middle omitted"
            )
        )
        assertFalse(
            clipped.length > 420
        )
    }
}
