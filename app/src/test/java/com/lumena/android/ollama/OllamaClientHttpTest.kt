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
    private fun consumeRequest(socket: Socket): String {
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        val end = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (true) {
            val value = input.read()
            if (value < 0) return ""
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
        val body = ByteArrayOutputStream()
        var remaining = length
        val buffer = ByteArray(4096)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            body.write(buffer, 0, read)
            remaining -= read
        }
        return body.toString(Charsets.UTF_8.name())
    }

    private fun withFixture(
        responseBodies: List<String>,
        requests: MutableList<String> = mutableListOf(),
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
                        requests += consumeRequest(socket)
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
    fun finalOllamaUsageCountsReplaceThePreflightEstimate() {
        withFixture(
            listOf(
                """{"message":{"role":"assistant","content":"ok"},"done":false,"prompt_eval_count":321}
{"done":true,"eval_count":17}
"""
            )
        ) { port, calls ->
            val client = OllamaClient("http://127.0.0.1:$port")
            val before = client.estimateContextUsage(
                listOf(OllamaMessage("user", "hello context meter"))
            )
            assertTrue(!before.promptTokensExact)

            val result = runBlocking {
                client.chatStreaming(
                    model = "fixture",
                    messages = listOf(
                        OllamaMessage("user", "hello context meter")
                    ),
                    onPartial = {}
                )
            }

            assertEquals("ok", result.getOrThrow())
            assertEquals(1, calls.get())
            val usage = client.lastContextUsage()
            assertTrue(usage != null)
            usage!!
            assertTrue(usage.promptTokensExact)
            assertEquals(321, usage.promptTokens)
            assertEquals(17, usage.generatedTokens)
            assertTrue(usage.inputBudgetTokens > 0)
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
    fun cloudModelFallsBackToGenerateWhenBothChatModesAreEmpty() {
        withFixture(
            listOf(
                """{"done":true}
""",
                """{"done":true}""",
                """{"response":"generate fallback ok","done":true,"prompt_eval_count":88,"eval_count":9}"""
            )
        ) { port, calls ->
            val client = OllamaClient("http://127.0.0.1:$port")
            val partials = mutableListOf<String>()
            val result = runBlocking {
                client.chatStreaming(
                    model = "gemma4:31b-cloud",
                    messages = listOf(
                        OllamaMessage("system", "system rules"),
                        OllamaMessage("user", "hello")
                    ),
                    onPartial = { partials += it }
                )
            }

            assertEquals("generate fallback ok", result.getOrThrow())
            assertEquals(3, calls.get())
            assertTrue(partials.count { it.isEmpty() } >= 2)

            val usage = client.lastContextUsage()
            assertTrue(usage != null)
            usage!!
            assertTrue(usage.promptTokensExact)
            assertEquals(88, usage.promptTokens)
            assertEquals(9, usage.generatedTokens)
        }
    }

    @Test
    fun localModelDoesNotEscalateEmptyChatIntoGenerateFallback() {
        withFixture(
            listOf(
                """{"done":true}
""",
                """{"done":true}"""
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

            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()?.message.orEmpty()
                    .contains("Ollama returned no message")
            )
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun cloudAliasDetectionIsNarrow() {
        assertTrue(isCloudBackedOllamaModel("gemma4:31b-cloud"))
        assertTrue(isCloudBackedOllamaModel("model:cloud"))
        assertTrue(!isCloudBackedOllamaModel("gemma4:31b"))
        assertTrue(!isCloudBackedOllamaModel("cloudless-model"))
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

    @Test fun lengthLimitedStreamIsNotAUsableToolResponseAndIsNotRetriedInTransport() {
        withFixture(listOf("""{"message":{"role":"assistant","content":"{\"tool\":\"file.write\""},"done":false}
{"done":true,"done_reason":"length","eval_count":768}
""")) { port, calls ->
            val result = runBlocking { OllamaClient("http://127.0.0.1:$port").chat("fixture", emptyList()) }
            assertTrue(result.exceptionOrNull() is ModelOutputIncompleteException)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("length"))
            assertEquals(1, calls.get())
        }
    }

    @Test fun streamWithoutFinalDoneIsRejectedEvenIfTextLooksComplete() {
        withFixture(listOf("""{"message":{"role":"assistant","content":"{\"done\":true,\"summary\":\"ok\"}"},"done":false}
""")) { port, calls ->
            val result = runBlocking { OllamaClient("http://127.0.0.1:$port").chat("fixture", emptyList()) }
            assertTrue(result.exceptionOrNull() is ModelOutputIncompleteException)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("missing_done"))
            assertEquals(1, calls.get())
        }
    }

    @Test fun emptyLengthLimitedStreamDoesNotTriggerEmptyResponseFallback() {
        withFixture(List(2) { """{"done":true,"done_reason":"length","eval_count":768}
""" }) { port, calls ->
            val result = runBlocking { OllamaClient("http://127.0.0.1:$port").chat("gemma4:31b-cloud", emptyList()) }
            assertTrue(result.exceptionOrNull() is ModelOutputIncompleteException)
            assertEquals(2, calls.get())
        }
    }

    @Test fun fallbackChatAlsoRejectsOutputLengthLimit() {
        withFixture(listOf("""{"done":true}
""", """{"message":{"role":"assistant","content":"unfinished"},"done":true,"done_reason":"length"}""", """{"done":true,"done_reason":"length"}""")) { port, calls ->
            val result = runBlocking { OllamaClient("http://127.0.0.1:$port").chat("gemma4:31b-cloud", emptyList()) }
            assertTrue(result.exceptionOrNull() is ModelOutputIncompleteException)
            assertEquals(3, calls.get())
        }
    }

    @Test fun generateFallbackAlsoRejectsOutputLengthLimit() {
        withFixture(listOf("""{"done":true}
""", """{"done":true}""", """{"response":"unfinished","done":true,"done_reason":"length"}""", """{"done":true,"done_reason":"length"}""")) { port, calls ->
            val result = runBlocking { OllamaClient("http://127.0.0.1:$port").chat("gemma4:31b-cloud", emptyList()) }
            assertTrue(result.exceptionOrNull() is ModelOutputIncompleteException)
            assertEquals(4, calls.get())
        }
    }

    @Test fun cloudLengthRecoveryExpandsBudgetAndDiscardsBrokenText() {
        val requests = mutableListOf<String>()
        withFixture(listOf(
            """{"message":{"role":"assistant","content":"BROKEN_FRAGMENT"},"done":true,"done_reason":"length","eval_count":4096}""",
            """{"message":{"role":"assistant","content":"complete"},"done":true,"done_reason":"stop"}""",
            """{"message":{"role":"assistant","content":"next"},"done":true}"""
        ), requests) { port, calls ->
            val profile = com.lumena.android.llama.LlamaHardwarePolicy.resolve(
                com.lumena.android.llama.LlamaHardwareInputs(8.0, 4.0, 8, "Adreno", false, false, false))
            assertEquals(640, profile.maxTokens)
            val client = OllamaClient("http://127.0.0.1:$port", profile, "gemma4:31b-cloud")
            assertEquals(4096, client.estimateContextUsage(emptyList()).reservedOutputTokens)
            val partials = mutableListOf<String>()
            val result = runBlocking { client.chatStreaming("gemma4:31b-cloud",
                listOf(OllamaMessage("user", "Create HTML"))) { partials += it } }
            assertEquals("complete", result.getOrThrow())
            assertEquals(2, calls.get())
            assertTrue(requests[0].contains("\"num_predict\":4096"))
            assertTrue(requests[1].contains("\"num_predict\":8192"))
            assertTrue(requests.all { it.contains("\"num_ctx\":16384") })
            assertTrue(!requests[1].contains("BROKEN_FRAGMENT"))
            assertTrue(requests[1].contains("ONE short complete JSON"))
            assertTrue(partials.contains(""))
            assertEquals(8192, client.lastContextUsage()!!.reservedOutputTokens)
            assertEquals("next", runBlocking { client.chat("gemma4:31b-cloud", emptyList()) }.getOrThrow())
            assertTrue(requests[2].contains("\"num_predict\":8192"))
        }
    }

    @Test fun exhaustedCloudExpansionDoesNotMakeThirdRequest() {
        val requests = mutableListOf<String>()
        withFixture(List(2) { """{"done":true,"done_reason":"length","eval_count":8192}""" }, requests) { port, calls ->
            val result = runBlocking { OllamaClient("http://127.0.0.1:$port").chat("model:cloud", emptyList()) }
            val failure = result.exceptionOrNull() as ModelOutputIncompleteException
            assertTrue(failure.outputRecoveryExhausted)
            assertEquals(2, calls.get())
            assertTrue(requests[0].contains("\"num_predict\":4096"))
            assertTrue(requests[1].contains("\"num_predict\":8192"))
        }
    }

    @Test fun cloudMissingDoneDoesNotIncreaseOutputBudget() {
        withFixture(listOf("""{"message":{"role":"assistant","content":"unfinished"},"done":false}""")) { port, calls ->
            val result = runBlocking { OllamaClient("http://127.0.0.1:$port").chat("model:cloud", emptyList()) }
            val failure = result.exceptionOrNull() as ModelOutputIncompleteException
            assertEquals("missing_done", failure.reason)
            assertTrue(!failure.outputRecoveryExhausted)
            assertEquals(1, calls.get())
        }
    }
}
