package com.lumena.android.ollama

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaClientHttpTest {
    private fun consumeRequest(socket: Socket) {
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        val end = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (true) {
            val value = input.read()
            if (value < 0) return
            header.write(value)
            matched = if (value.toByte() == end[matched]) matched + 1
                else if (value.toByte() == end[0]) 1 else 0
            if (matched == end.size) break
        }

        val headerText = header.toString(Charsets.ISO_8859_1.name())
        val length = Regex("(?i)Content-Length:\\s*(\\d+)")
            .find(headerText)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: 0
        var remaining = length
        val buffer = ByteArray(4096)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            remaining -= read
        }
    }

    private fun withFixture(
        responseBodies: List<String>,
        block: (port: Int, calls: AtomicInteger) -> Unit
    ) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 3000
        val calls = AtomicInteger(0)
        val worker = thread(name = "ollama-http-fixture") {
            try {
                for (body in responseBodies) {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        consumeRequest(socket)
                        calls.incrementAndGet()
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val response = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/x-ndjson\r\n")
                            append("Content-Length: ${bytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }.toByteArray(Charsets.ISO_8859_1)
                        socket.getOutputStream().write(response)
                        socket.getOutputStream().write(bytes)
                        socket.getOutputStream().flush()
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Missing or extra requests make assertions fail through call count/result.
            } catch (_: java.net.SocketException) {
                // Fixture teardown closes accept() after the test block returns.
            } finally {
                runCatching { server.close() }
            }
        }

        try {
            block(server.localPort, calls)
        } finally {
            runCatching { server.close() }
            worker.join(5000)
        }
    }

    @Test
    fun nonContextStreamErrorIsPreservedAndNeverRetried() {
        withFixture(
            listOf("""{"error":"model not found","done":true}
""")
        ) { port, calls ->
            val client = OllamaClient("http://127.0.0.1:$port")
            val result = runBlocking {
                client.chatStreaming(
                    model = "fixture",
                    messages = listOf(OllamaMessage("user", "hello")),
                    onPartial = {}
                )
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("model not found"))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun emptyStreamingResponseFallsBackToNonStreamingChatOnce() {
        withFixture(
            listOf(
                """{"done":true}
""",
                """{"message":{"role":"assistant","content":"fallback ok"},"done":true}"""
            )
        ) { port, calls ->
            val client = OllamaClient("http://127.0.0.1:$port")
            val partials = mutableListOf<String>()
            val result = runBlocking {
                client.chatStreaming(
                    model = "fixture",
                    messages = listOf(OllamaMessage("user", "hello")),
                    onPartial = { partials += it }
                )
            }

            assertEquals("fallback ok", result.getOrThrow())
            assertEquals(2, calls.get())
            assertTrue(partials.contains(""))
        }
    }

    @Test
    fun contextPressureRetriesExactlyOnceThenSucceeds() {
        withFixture(
            listOf(
                """{"error":"context length exceeded","done":true}
""",
                """{"message":{"role":"assistant","content":"ok"},"done":true}
"""
            )
        ) { port, calls ->
            val client = OllamaClient("http://127.0.0.1:$port")
            val result = runBlocking {
                client.chatStreaming(
                    model = "fixture",
                    messages = listOf(OllamaMessage("user", "hello")),
                    onPartial = {}
                )
            }

            assertEquals("ok", result.getOrThrow())
            assertEquals(2, calls.get())
        }
    }
}
