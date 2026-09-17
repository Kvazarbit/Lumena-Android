package com.lumena.android.ollama

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OllamaHttpTest {
    @Test fun nativeRequestContainsSchemasAndAcceptsContentlessToolCall() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"done":true,"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"health","arguments":{}}}]}}"""))
            val result = OllamaClient(server.url("/").toString()).chatTurn("test", listOf(OllamaMessage("user", "check health")), true)
            assertTrue(result.isSuccess)
            assertEquals("health", result.getOrThrow().message.tool_calls!!.single().function.name)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            val body = request.body.readUtf8()
            assertEquals("/api/chat", request.path)
            assertTrue(body.contains("\"tools\""))
            assertTrue(body.contains("agent.finish"))
            assertFalse(body.contains("Bearer"))
        } finally { server.shutdown() }
    }
    @Test fun truncatedJsonCannotBecomeExecutableTool() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"done":true,"done_reason":"length","message":{"role":"assistant","content":"{\"tool\":\"file.write\"}"}}"""))
            val result = OllamaClient(server.url("/").toString()).chatTurn("test", listOf(OllamaMessage("user", "test")), false)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("incomplete"))
        } finally { server.shutdown() }
    }
    @Test fun capabilityProbeIsModelSpecific() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"capabilities":["completion","tools"]}"""))
            assertTrue(OllamaClient(server.url("/").toString()).supportsNativeTools("local-test").getOrThrow())
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/show", request.path)
            assertTrue(request.body.readUtf8().contains("local-test"))
        } finally { server.shutdown() }
    }
    @Test fun cancelDoesNotWaitForTenMinuteReadTimeout() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OllamaClient(server.url("/").toString())
            val job = launch(Dispatchers.IO) { client.chatTurn("test", listOf(OllamaMessage("user", "wait")), false) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }
}
