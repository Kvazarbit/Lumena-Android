package com.lumena.android.agent.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class TermuxBridgeClientTest {
    /** Consume the request completely, then simulate a bridge dying before its response. */
    private fun runFixture(tool: String, initialStatus: Int? = null, recover: Boolean = true): Pair<ToolResult, Int> {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 1500
        val calls = AtomicInteger()
        val worker = thread {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 1500
                        val reader = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                length = line.substringAfter(':').trim().toInt()
                            }
                        }
                        repeat(length) { reader.read() }
                        val count = calls.incrementAndGet()
                        if ((count > 1 && recover) || initialStatus != null) {
                            val status = initialStatus ?: 200
                            val body = if (status == 200) """{"ok":true,"stdout":"read complete","exitCode":0}"""
                                else """{"ok":false,"error":"Upstream HTTP 403","exitCode":1}"""
                            val response = "HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                            socket.getOutputStream().write(response.toByteArray())
                            socket.getOutputStream().flush()
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                // No second request is the expected behavior for a mutation/known failure.
            } catch (_: java.net.SocketException) {
                // Fixture teardown closes accept().
            }
        }
        return try {
            val client = TermuxBridgeClient("http://127.0.0.1:${server.localPort}", "test-token")
            val result = runBlocking { client.execute(ToolRequest(tool, emptyMap(), "transport-test")) }
            result to calls.get()
        } finally {
            server.close()
            worker.join(2000)
        }
    }

    @Test fun readRetriesOneLostResponseAndReturnsSecondResult() {
        val (result, calls) = runFixture("web.search")
        assertEquals(2, calls)
        assertTrue(result.ok)
        assertFalse(result.outcomeUnknown)
    }

    @Test fun unknownMutationIsNeverReplayed() {
        val (result, calls) = runFixture("file.write")
        assertEquals(1, calls)
        assertFalse(result.ok)
        assertTrue(result.outcomeUnknown)
    }

    @Test fun persistentReadDisconnectStopsAfterOneRetry() {
        val (result, calls) = runFixture("http.get", recover = false)
        assertEquals(2, calls)
        assertFalse(result.ok)
        assertFalse(result.outcomeUnknown)
        assertTrue(result.error.orEmpty().contains("Bridge transport"))
    }

    @Test fun knownUpstreamErrorIsNotATransportRetry() {
        val (result, calls) = runFixture("web.read", 422)
        assertEquals(1, calls)
        assertFalse(result.ok)
        assertFalse(result.outcomeUnknown)
        assertEquals("Upstream HTTP 403", result.error)
    }
}
