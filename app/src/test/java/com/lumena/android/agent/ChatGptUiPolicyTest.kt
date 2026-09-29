package com.lumena.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptUiPolicyTest {
    @Test
    fun recognizesGeneratingControlsAcrossSupportedLocales() {
        assertTrue(ChatGptUiPolicy.isGeneratingLabel("Stop generating"))
        assertTrue(ChatGptUiPolicy.isGeneratingLabel("Zatrzymaj generowanie"))
        assertTrue(ChatGptUiPolicy.isGeneratingLabel("Зупинити генерацію"))
        assertTrue(ChatGptUiPolicy.isGeneratingLabel("Остановить генерацию"))
    }

    @Test
    fun ordinaryConversationTextDoesNotLookLikeAGeneratingControl() {
        assertFalse(ChatGptUiPolicy.isGeneratingLabel("Please stop changing the file"))
        assertFalse(ChatGptUiPolicy.isGeneratingLabel("Send this later"))
    }

    @Test
    fun sendMatchingIsExactNotSubstringBased() {
        assertTrue(ChatGptUiPolicy.isSendLabel("Send"))
        assertTrue(ChatGptUiPolicy.isSendLabel("Wyślij"))
        assertFalse(ChatGptUiPolicy.isSendLabel("Resend last message"))
        assertFalse(ChatGptUiPolicy.isSendLabel("Send feedback"))
    }
}
