package com.lumena.android.ollama

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaClientProtocolTest {
    @Test
    fun streamedErrorFieldIsParsedAndPreserved() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(OllamaChatResponse::class.java)

        val chunk = adapter.fromJson("""{"error":" context length exceeded "}""")
            ?: error("chunk not parsed")

        assertEquals("context length exceeded", ollamaChunkError(chunk))
    }

    @Test
    fun onlyTimeoutAndContextPressureUseTheCompactRetryPath() {
        assertTrue(shouldRetryOllamaWithSmallerContext(SocketTimeoutException("read timed out")))
        assertTrue(shouldRetryOllamaWithSmallerContext(IllegalStateException("context window exceeded")))
        assertTrue(shouldRetryOllamaWithSmallerContext(IllegalStateException("prompt too long")))
        assertTrue(shouldRetryOllamaWithSmallerContext(IllegalStateException("too many tokens")))
        assertFalse(shouldRetryOllamaWithSmallerContext(IllegalStateException("HTTP 500 upstream failure")))
        assertFalse(shouldRetryOllamaWithSmallerContext(IllegalStateException("model not found")))
    }

    @Test
    fun normalChunkHasNoProtocolError() {
        val chunk = OllamaChatResponse(
            message = OllamaMessage("assistant", "hello"),
            done = false
        )

        assertEquals(null, ollamaChunkError(chunk))
    }
}
