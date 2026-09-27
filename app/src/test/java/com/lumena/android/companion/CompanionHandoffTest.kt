package com.lumena.android.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionHandoffTest {
    @Test fun assistantTextIsPreservedAndNeverPresentedAsVerifiedToolEvidence() {
        val text = "Створено файл\n```html\n<title>Риби 🐟</title>\n```"
        val draft = CompanionHandoff("m-7", "assistant", text, "topic-2", "task-3", "Акваріум").draftText()
        assertTrue(draft.contains(text))
        assertTrue(draft.contains("не перевірений результат інструмента"))
        assertTrue(draft.contains("session_id=topic-2"))
        assertTrue(draft.contains("task_id=task-3"))
        assertTrue(draft.contains("source_message_id=m-7"))
    }

    @Test fun identicalTextFromDifferentMessagesKeepsDistinctProvenance() {
        val first = CompanionHandoff("m-1", "user", "уточни", "p", "t", "Пошук")
        assertNotEquals(first.draftText(), first.copy(messageId = "m-2").draftText())
        assertTrue(first.draftText().contains("запит користувача"))
        assertTrue(first.copy(role = "error").draftText().contains("повідомлення про помилку"))
    }
}
