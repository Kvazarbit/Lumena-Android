package com.lumena.android.ollama

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OllamaStreamingTest {
    @Test fun streamRequiresFinalFrameAndNeverIncludesThinking() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(
                """{"message":{"role":"assistant","content":"hello","thinking":"private"},"done":false}
{"message":{"role":"assistant","content":" world"},"done":true}
"""))
            var pulse = ModelPulse()
            val result = OllamaClient(server.url("/").toString()).chat("test", listOf(OllamaMessage("user", "hello"))) { pulse = it }
            assertEquals("hello world", result.getOrThrow())
            assertEquals(2, pulse.chunks)
            assertEquals(7, pulse.thinkingChars)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
        } finally { server.shutdown() }
    }
    @Test fun missingDoneIsNotExecutableSuccess() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"message\":{\"content\":\"partial\"},\"done\":false}\n"))
            assertTrue(OllamaClient(server.url("/").toString()).chat("test", listOf(OllamaMessage("user", "hello"))).isFailure)
        } finally { server.shutdown() }
    }
    @Test fun tokenBudgetExhaustionIsReported() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"message\":{\"content\":\"partial\"},\"done\":true,\"done_reason\":\"length\"}\n"))
            assertTrue(OllamaClient(server.url("/").toString()).chat("test", listOf(OllamaMessage("user","hello"))).isFailure)
        } finally { server.shutdown() }
    }
    @Test fun cancelStopsWaitingWithoutRetry() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.Default) {
                OllamaClient(server.url("/").toString()).chat("test", listOf(OllamaMessage("user", "hello")))
                fail("Cancellation must not become a successful reply")
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun completePinnedContextIsNotTruncated() {
        val client = OllamaClient("http://127.0.0.1:11434")
        val system = "RULES".repeat(1000) + "GOAL-MUST-SURVIVE"
        val result = client.compactMessages(listOf(OllamaMessage("system", system), OllamaMessage("user", "hello")))
        assertEquals(system, result.first().content)
    }
}
