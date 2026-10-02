package com.lumena.android.ollama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionEpochHistoryTest {
    @Test
    fun freshEpochKeepsVisibleConversationButDropsTransportTurns() {
        val system =
            OllamaMessage(
                "system",
                "system rules"
            )
        val rebuilt =
            ExecutionEpochHistory.rebuild(
                systemMessage = system,
                visibleTurns =
                    listOf(
                        "user" to
                            "Продовж розробку aquarium.html з v3 physics",
                        "assistant" to
                            "Попередній видимий partial",
                        "status" to
                            "TOOL_RESULT for file.write: ok=true",
                        "user" to
                            "LUMENA_TOOL\n{\"tool\":\"file.write\"}",
                        "error" to
                            "старий запуск завершився помилкою"
                    )
            )

        assertEquals(system, rebuilt.first())
        assertTrue(
            rebuilt.any {
                it.role == "user" &&
                    it.content.contains(
                        "aquarium.html"
                    )
            }
        )
        assertTrue(
            rebuilt.any {
                it.role == "assistant" &&
                    it.content.contains(
                        "Попередній видимий partial"
                    )
            }
        )
        assertTrue(
            rebuilt.any {
                it.role == "assistant" &&
                    it.content.contains(
                        "Previous visible task error"
                    )
            }
        )
        assertFalse(
            rebuilt.any {
                it.content.contains(
                    "LUMENA_TOOL"
                )
            }
        )
        assertFalse(
            rebuilt.any {
                it.content.contains(
                    "TOOL_RESULT for file.write"
                )
            }
        )
    }

    @Test
    fun transportFenceIsNotDurableConversationContext() {
        val rebuilt =
            ExecutionEpochHistory.rebuild(
                systemMessage =
                    OllamaMessage(
                        "system",
                        "system"
                    ),
                visibleTurns =
                    listOf(
                        "user" to
                            "```text\nLUMENA_TOOL\n{\"encoding\":\"base64-sha256-v1\"}\n```",
                        "user" to
                            "звичайне повідомлення"
                    )
            )

        assertEquals(2, rebuilt.size)
        assertEquals(
            "звичайне повідомлення",
            rebuilt.last().content
        )
    }
}
