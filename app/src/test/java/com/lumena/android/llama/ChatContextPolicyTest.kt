package com.lumena.android.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextPolicyTest {
    private fun formatter(
        roles: Array<String>,
        contents: Array<String>
    ): String = roles.indices.joinToString("|") { index ->
        "${roles[index]}:${contents[index]}"
    }

    private fun tokenCounter(text: String): Int = text.length

    @Test
    fun dropsOldHistoryButKeepsSystemAndNewestUser() {
        val fit = ChatContextPolicy.fit(
            roles = arrayOf("system", "user", "assistant", "user", "assistant", "user"),
            contents = arrayOf(
                "RULES",
                "old-question-one",
                "old-answer-one",
                "old-question-two",
                "old-answer-two",
                "newest-user"
            ),
            maxPromptTokens = 55,
            formatter = ::formatter,
            tokenCounter = ::tokenCounter
        )

        assertTrue(fit.fits)
        assertTrue(fit.prompt.contains("system:RULES"))
        assertTrue(fit.prompt.contains("user:newest-user"))
        assertFalse(fit.prompt.contains("old-question-one"))
        assertFalse(fit.prompt.contains("old-answer-one"))
        assertTrue(fit.droppedMessages >= 2)
    }

    @Test
    fun neverDropsSystemToForceAFit() {
        val fit = ChatContextPolicy.fit(
            roles = arrayOf("system", "user"),
            contents = arrayOf("S".repeat(80), "latest"),
            maxPromptTokens = 20,
            formatter = ::formatter,
            tokenCounter = ::tokenCounter
        )

        assertFalse(fit.fits)
        assertTrue(fit.prompt.contains("system:"))
        assertTrue(fit.prompt.contains("user:latest"))
        assertEquals(0, fit.droppedMessages)
    }

    @Test
    fun preservesArrayAlignmentWhileDroppingPairs() {
        val fit = ChatContextPolicy.fit(
            roles = arrayOf("system", "user", "assistant", "user"),
            contents = arrayOf("sys", "old", "answer", "latest"),
            maxPromptTokens = 35,
            formatter = ::formatter,
            tokenCounter = ::tokenCounter
        )

        assertEquals(fit.roles.size, fit.contents.size)
        assertEquals("system", fit.roles.first())
        assertEquals("user", fit.roles.last())
        assertEquals("latest", fit.contents.last())
    }
}
