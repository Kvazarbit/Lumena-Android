package com.lumena.android.agent.core

import org.junit.Assert.*
import org.junit.Test

class CodeTaskAnchorTest {
    @Test fun aquariumGoalSurvivesFailureDiscussionAndStackClarification() {
        val root = "створи в html, 3d акваріум з рибками, симулятор"
        val start = CodeTaskAnchor.resolve(root, null, null)
        assertEquals(root, start.anchor)
        val meta = CodeTaskAnchor.resolve("що не так? чому не зробив?", start.anchor, null)
        assertEquals(root, meta.anchor)
        val yes = CodeTaskAnchor.resolve("так", meta.anchor, "Бажаєте, щоб я створив цей код зараз?")
        assertTrue(yes.continued)
        assertEquals(root, yes.text)
        val html = CodeTaskAnchor.resolve("html+js", yes.anchor, "Уточніть ваш запит")
        assertTrue(html.text.contains(root))
        assertTrue(html.text.contains("html+js"))
        assertEquals(html.text, html.anchor)
    }

    @Test fun bareStackAndYesWithoutAnchorDoNotInventTask() {
        assertEquals("html+js", CodeTaskAnchor.resolve("html+js", null, null).text)
        assertEquals("так", CodeTaskAnchor.resolve("так", null, "Створити код?").text)
    }

    @Test fun newTopicReplacesOrClearsOldCodeAnchor() {
        val old = "Create HTML aquarium"
        assertNull(CodeTaskAnchor.resolve("Знайди новини в інтернеті", old, null).anchor)
        assertNull(CodeTaskAnchor.resolve("Розкажи про океан", old, null).anchor)
        assertEquals("Write Python calculator", CodeTaskAnchor.resolve("Write Python calculator", old, null).anchor)
        assertFalse(CodeTaskAnchor.resolve("так", old, "Видалити файл?").continued)
    }

    @Test fun oldChatCanRestoreGoalAfterUpgradeWithoutRestoringActions() {
        val goal = CodeTaskAnchor.restore(listOf(
            "user" to "Create HTML aquarium",
            "error" to "Protocol failure",
            "user" to "що не так? чому не зробив?",
            "assistant" to "Створити цей код зараз?",
            "user" to "так",
            "user" to "html+js"
        ))
        assertTrue(goal!!.contains("Create HTML aquarium"))
        assertTrue(goal.contains("html+js"))
        assertNull(CodeTaskAnchor.restore(listOf("user" to "hello")))
    }
}
