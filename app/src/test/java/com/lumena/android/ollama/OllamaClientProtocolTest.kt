package com.lumena.android.ollama

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OllamaClientProtocolTest {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(OllamaChatResponse::class.java)

    @Test
    fun streamedErrorFieldIsParsedAndRaisedWithOriginalReason() {
        val parsed = adapter.fromJson(
            """{"error":"context length exceeded: prompt has 5000 tokens","done":true}"""
        ) ?: error("fixture did not parse")

        assertEquals("context length exceeded: prompt has 5000 tokens", parsed.error)
        try {
            OllamaStreamProtocol.requireUsable(parsed)
            fail("Expected provider error to terminate the stream")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("context length exceeded"))
            assertTrue(error.message.orEmpty().contains("5000 tokens"))
        }
    }

    @Test
    fun normalMessageChunkPassesGuard() {
        val parsed = adapter.fromJson(
            """{"message":{"role":"assistant","content":"ok"},"done":false}"""
        ) ?: error("fixture did not parse")

        OllamaStreamProtocol.requireUsable(parsed)
        assertEquals("ok", parsed.message?.content)
    }
}
