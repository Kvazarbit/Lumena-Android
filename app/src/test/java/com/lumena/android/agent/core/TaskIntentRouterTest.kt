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
    fun ordinaryConversationStaysGeneral() {
        val profile = TaskIntentRouter.route("поясни мені різницю між RAM і SSD")

        assertEquals(TaskIntent.GENERAL, profile.intent)
        assertEquals(0, profile.confidence)
        assertNull(profile.preflight)
    }
}
