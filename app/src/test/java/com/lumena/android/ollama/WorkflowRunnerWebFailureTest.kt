package com.lumena.android.ollama

import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.TermuxBridgeClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class WorkflowRunnerWebFailureTest {
    private class CountingModelClient : ChatModelClient {
        val calls = AtomicInteger(0)

        override suspend fun chat(
            model: String,
            messages: List<OllamaMessage>
        ): Result<String> {
            calls.incrementAndGet()
            return Result.success("""{"done":true,"summary":"should not be reached"}""")
        }
    }

    @Test
    fun failedMandatoryWebPreflightStopsBeforeAnyModelGeneration() {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 2000
        val bridgeCalls = AtomicInteger(0)
        val worker = thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
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
                    bridgeCalls.incrementAndGet()

                    val body = """{"ok":false,"tool":"web.search","exitCode":1,"stdout":"","stderr":"","error":"Search unavailable: Upstream HTTP 202"}"""
                    val bytes = body.toByteArray()
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray())
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (_: SocketTimeoutException) {
                // A second bridge call would violate this regression.
            } finally {
                runCatching { server.close() }
            }
        }

        try {
            val model = CountingModelClient()
            val bridge = TermuxBridgeClient(
                "http://127.0.0.1:${server.localPort}",
                "test-token"
            )
            val task = TaskState(
                id = "web-fail",
                projectId = null,
                goal = "Знайди останні новини в інтернеті",
                status = TaskStatus.WAITING_MODEL
            )
            val runner = WorkflowRunner(
                modelClient = model,
                bridge = bridge,
                model = "test-model"
            )

            val outcome = runBlocking {
                runner.run(
                    history = listOf(
                        OllamaMessage("system", "test system"),
                        OllamaMessage("user", task.goal)
                    ),
                    task = task
                )
            }

            assertTrue(outcome is WorkflowOutcome.Failed)
            outcome as WorkflowOutcome.Failed
            assertTrue(outcome.message.contains("automatic model retry suppressed"))
            assertEquals(TaskStatus.FAILED, outcome.control.task.status)
            assertEquals(1, bridgeCalls.get())
            assertEquals(0, model.calls.get())
        } finally {
            runCatching { server.close() }
            worker.join(3000)
        }
    }
}
