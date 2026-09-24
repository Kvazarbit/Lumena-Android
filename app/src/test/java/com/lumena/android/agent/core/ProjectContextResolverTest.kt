package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectContextResolverTest {
    @Test
    fun resolvesExplicitProjectAcrossSupportedConversationLanguages() {
        val cases = mapOf(
            "Працюй у проєкті demo_project." to "demo_project",
            "Працюй у проекті alpha-2." to "alpha-2",
            "Работай в проекте test_app." to "test_app",
            "Work in project lab.v1." to "lab.v1",
            "Pracuj w projekcie mobile_core." to "mobile_core",
            "project: \"quoted_name\"" to "quoted_name"
        )

        cases.forEach { (text, expected) ->
            assertEquals(
                text,
                expected,
                ProjectContextResolver.resolve(
                    text = text,
                    previousProjectId = null,
                    carryForward = false
                )
            )
        }
    }

    @Test
    fun explicitProjectOverridesPreviousScope() {
        assertEquals(
            "new_project",
            ProjectContextResolver.resolve(
                text = "Use project new_project for this task.",
                previousProjectId = "old_project",
                carryForward = true
            )
        )
    }

    @Test
    fun relatedFollowUpCarriesPreviousProjectOnlyWhenAllowed() {
        assertEquals(
            "demo_project",
            ProjectContextResolver.resolve(
                text = "чому?",
                previousProjectId = "demo_project",
                carryForward = true
            )
        )

        assertNull(
            ProjectContextResolver.resolve(
                text = "розкажи про погоду",
                previousProjectId = "demo_project",
                carryForward = false
            )
        )
    }

    @Test
    fun doesNotGuessProjectFromGenericProseOrPaths() {
        for (text in listOf(
            "Ми проектуємо нову систему.",
            "Open demo_project/file.py",
            "project ../secret",
            "project demo/project",
            "проєктування контекстної системи"
        )) {
            assertNull(
                text,
                ProjectContextResolver.resolve(
                    text = text,
                    previousProjectId = null,
                    carryForward = false
                )
            )
        }
    }

    @Test
    fun invalidPreviousScopeCannotBeCarriedForward() {
        for (bad in listOf("", ".", "..", "../demo", "demo/project", "@root")) {
            assertNull(
                bad,
                ProjectContextResolver.resolve(
                    text = "continue",
                    previousProjectId = bad,
                    carryForward = true
                )
            )
        }
    }
}
