package com.lumena.android.ollama

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaClientProtocolTest {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(OllamaChatResponse::class.java)

    @Test
    fun streamErrorFieldSurvivesJsonDecoding() {
        val chunk = requireNotNull(
            adapter.fromJson("""{"error":"context length exceeded","done":true}""")
        )

        assertEquals("Ollama: context length exceeded", ollamaProtocolError(chunk))
        assertTrue(isOllamaContextLimitFailure(chunk.error.orEmpty()))
    }

    @Test
    fun normalMessageHasNoProtocolError() {
        val chunk = requireNotNull(
            adapter.fromJson("""{"message":{"role":"assistant","content":"ok"},"done":false}""")
        )

        assertEquals(null, ollamaProtocolError(chunk))
        assertFalse(isOllamaContextLimitFailure("socket closed"))
    }

    @Test
    fun contextClassifierCoversCommonOllamaWordings() {
        for (message in listOf(
            "context length exceeded",
            "prompt is too long",
            "too many tokens",
            "input is too long for the context window"
        )) {
            assertTrue(message, isOllamaContextLimitFailure(message))
        }
    }
}
