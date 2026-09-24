package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskIntentRouterTest {
    @Test
    fun visualSearchGetsMandatoryImagePreflight() {
        val profile = TaskIntentRouter.route(
            "знайди фото жінки в інтернеті і покажи"
        )

        assertEquals(TaskIntent.VISUAL_SEARCH, profile.intent)
        assertEquals("image.search", profile.preflight?.tool)
        assertTrue(profile.preflight?.mandatory == true)
        assertTrue(profile.preflight?.args?.get("query").orEmpty().contains("жінки"))
    }

    @Test
    fun ollamaOperationGetsStatusPreflight() {
        val profile = TaskIntentRouter.route(
            "перевір чи Ollama модель зараз запущена"
        )

        assertEquals(TaskIntent.OLLAMA_OPERATION, profile.intent)
        assertEquals("ollama.status", profile.preflight?.tool)
        assertTrue(profile.preflight?.mandatory == true)
    }

    @Test
    fun codeWorkGetsOptionalContextSnapshot() {
        val profile = TaskIntentRouter.route(
            "виправ bug у Python script і перевір тестами"
        )

        assertEquals(TaskIntent.CODE_WORK, profile.intent)
        assertEquals("context.snapshot", profile.preflight?.tool)
        assertFalse(profile.preflight?.mandatory ?: true)
        assertTrue("python.tests" in profile.recommendedTools)
        assertEquals(6, profile.minimumToolSteps)
    }


    @Test
    fun mixedWebResearchAndCodeGetsCombinedRecipeAndEightToolBudget() {
        val profile = TaskIntentRouter.route(
            """
            Працюй у проекті e2e_step87.
            Прочитай через web.read:
            https://docs.python.org/3/library/pathlib.html#pathlib.Path.mkdir
            Потім створи e2e_step87/dir_a.py і e2e_step87/test_dir_a.py.
            Запусти python.syntax_check і python.tests.
            """.trimIndent()
        )

        assertEquals(TaskIntent.CODE_WORK, profile.intent)
        assertTrue(profile.confidence >= 90)
        assertTrue("web.read" in profile.recommendedTools)
        assertTrue("file.write" in profile.recommendedTools)
        assertTrue("python.syntax_check" in profile.recommendedTools)
        assertTrue("python.tests" in profile.recommendedTools)
        assertEquals(8, profile.minimumToolSteps)
        assertEquals("context.snapshot", profile.preflight?.tool)
    }

    @Test
    fun ordinaryConversationKeepsFourToolCeilingHint() {
        val profile = TaskIntentRouter.route(
            "поясни мені різницю між RAM і SSD"
        )

        assertEquals(TaskIntent.GENERAL, profile.intent)
        assertEquals(4, profile.minimumToolSteps)
    }


    @Test
    fun explicitToolRequirementsAreDetectedAndNegatedOnesAreExcluded() {
        val required = TaskIntentRouter.explicitRequiredTools(
            """
            Прочитай через web.read.
            Запусти python.syntax_check.
            Запусти python.tests.
            Не запускай python.run.
            """.trimIndent()
        )

        assertEquals(
            setOf(
                "web.read",
                "python.syntax_check",
                "python.tests"
            ),
            required
        )
    }

    @Test
    fun fileInspectionDiscoversWorkspaceFirst() {
        val profile = TaskIntentRouter.route(
            "знайди файл README і прочитай його"
        )

        assertEquals(TaskIntent.FILE_INSPECTION, profile.intent)
        assertEquals("workspace.list", profile.preflight?.tool)
        assertTrue(profile.preflight?.mandatory == true)
    }

    @Test
    fun publicWebIntentDoesNotInventAUrlPreflight() {
        val profile = TaskIntentRouter.route(
            "перевір актуальну інформацію на цьому сайті https://example.com"
        )

        assertEquals(TaskIntent.PUBLIC_WEB, profile.intent)
        assertNull(profile.preflight)
        assertTrue("http.get" in profile.recommendedTools)
    }

    @Test
    fun webResearchStartsWithActualSearchRatherThanGuessedHomepage() {
        val profile = TaskIntentRouter.route("знайди в інтернеті актуальні інструменти AI")
        assertEquals(TaskIntent.PUBLIC_WEB, profile.intent)
        assertEquals("web.search", profile.preflight?.tool)
        assertTrue(profile.preflight?.mandatory == true)
        assertTrue(profile.preflight?.args?.get("query").orEmpty().contains("інструменти AI"))
        assertTrue("web.read" in profile.recommendedTools)
    }

    @Test
    fun publicWebPreflightDistillsUkrainianPresentationInstructions() {
        val profile = TaskIntentRouter.route(
            "Знайди в інтернеті останні новини Python сьогодні і коротко підсумуй їх з посиланнями на джерела."
        )

        assertEquals(TaskIntent.PUBLIC_WEB, profile.intent)
        assertEquals("web.search", profile.preflight?.tool)
        assertEquals(
            "останні новини Python сьогодні",
            profile.preflight?.args?.get("query")
        )
    }

    @Test
    fun retryPrefixDoesNotPolluteSearchEngineQuery() {
        val profile = TaskIntentRouter.route(
            "спробуй інший підхід до запиту: Знайди в інтернеті останні новини Python сьогодні і коротко підсумуй їх з посиланнями на джерела."
        )

        assertEquals(
            "останні новини Python сьогодні",
            profile.preflight?.args?.get("query")
        )
    }

    @Test
    fun ordinaryConversationStaysGeneral() {
        val profile = TaskIntentRouter.route("поясни мені різницю між RAM і SSD")

        assertEquals(TaskIntent.GENERAL, profile.intent)
        assertEquals(0, profile.confidence)
        assertNull(profile.preflight)
    }
    @Test
    fun latestWebQueryDoesNotCollideWithTestKeyword() {
        val profile = TaskIntentRouter.route("Find latest world news on the internet")

        assertEquals(TaskIntent.PUBLIC_WEB, profile.intent)
        assertEquals("web.search", profile.preflight?.tool)
        assertTrue(profile.preflight?.mandatory == true)
    }

    @Test
    fun asciiTestCommandStillRoutesToCodeWork() {
        val profile = TaskIntentRouter.route("test Python code")

        assertEquals(TaskIntent.CODE_WORK, profile.intent)
        assertEquals("context.snapshot", profile.preflight?.tool)
    }

}
