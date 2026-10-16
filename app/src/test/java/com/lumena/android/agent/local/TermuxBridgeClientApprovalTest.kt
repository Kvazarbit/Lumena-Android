package com.lumena.android.agent.local

import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

/** What TermuxBridgeClient actually puts on the wire, and parity with bridge.py. */
class TermuxBridgeClientApprovalTest {
    private val mapAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    private fun readBody(input: InputStream): String {
        val head = ByteArrayOutputStream()
        var last = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
            last = (last shl 8) or b
            if (last == 0x0D0A0D0A) break
        }
        val length = head.toString("ISO-8859-1").lines()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(body, 0, read, Charsets.UTF_8)
    }

    /** A bridge stand-in that records every /tool body and answers ok. */
    private fun send(requests: List<ToolRequest>, signer: ApprovalSigner?): List<Map<String, Any?>> {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 3000
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val worker = thread {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        bodies += readBody(socket.getInputStream())
                        val reply = """{"ok":true,"stdout":"ok","exitCode":0}"""
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${reply.toByteArray().size}\r\nConnection: close\r\n\r\n$reply"
                        socket.getOutputStream().write(response.toByteArray())
                        socket.getOutputStream().flush()
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: SocketException) {
                // Fixture teardown closes accept().
            }
        }
        try {
            val client = TermuxBridgeClient(
                "http://127.0.0.1:${server.localPort}",
                "test-token",
                approvalSigner = { signer }
            )
            requests.forEach { request ->
                val result = runBlocking { client.execute(request) }
                assertTrue("${request.tool}: ${result.error}", result.ok)
            }
        } finally {
            server.close()
            worker.join(4000)
        }
        return bodies.map { requireNotNull(mapAdapter.fromJson(it)) }
    }

    @Test fun clientSignsOnlyMutatingAndExecutableRequests() {
        val signer = SignedApprovalTest.SoftwareSigner()
        val bodies = send(
            listOf(
                ToolRequest("file.write", mapOf("path" to "a.txt", "content" to "hi"), "write-1"),
                ToolRequest("python.run", mapOf("script" to "main.py")),
                ToolRequest("web.search", mapOf("query" to "lumena")),
                ToolRequest("health")
            ),
            signer
        )
        assertEquals(4, bodies.size)
        val byTool = bodies.associateBy { it["tool"] as String }
        for (tool in listOf("file.write", "python.run")) {
            val body = byTool.getValue(tool)
            val approval = body["approval"] as? Map<*, *> ?: throw AssertionError("$tool was sent unsigned")
            @Suppress("UNCHECKED_CAST")
            val args = body["args"] as Map<String, String>
            val message = ApprovalCanonical.message(
                tool,
                ApprovalCanonical.argsSha256(args),
                approval["nonce"] as String,
                (approval["issuedAt"] as Number).toLong(),
                (approval["expiresAt"] as Number).toLong()
            )
            assertTrue(
                "$tool signature must verify over what was sent",
                SignedApprovalTest.verifies(
                    signer.publicKeySpki(),
                    message,
                    Base64.getUrlDecoder().decode(approval["sig"] as String)
                )
            )
        }
        assertEquals("write-1", byTool.getValue("file.write")["requestId"])
        assertFalse("web.search is read-only", "approval" in byTool.getValue("web.search"))
        assertFalse("health is read-only", "approval" in byTool.getValue("health"))
    }

    @Test fun clientWithoutSignerSendsUnsignedAndLetsTheBridgeDecide() {
        val bodies = send(listOf(ToolRequest("dir.create", mapOf("path" to "a"))), signer = null)
        assertEquals(1, bodies.size)
        assertFalse("approval" in bodies.single())
    }

    @Test fun bridgeTokenOnlyToolsAreExactlyTheReadOnlyTools() {
        val appDir = listOf(File("."), File("app"))
            .map { it.canonicalFile }
            .first { File(it, "src/main/java").isDirectory }
        val bridge = File(requireNotNull(appDir.parentFile), "termux/bridge.py").readText()
        fun names(pattern: String): Set<String> {
            val block = requireNotNull(Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(bridge)) {
                "termux/bridge.py has no $pattern"
            }.groupValues[1]
            return Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
        }
        val tokenOnly = names("""READ_ONLY_BATCH_TOOLS = \{(.*?)\}""") +
            names("""APPROVAL_EXEMPT_EXTRA_TOOLS = \((.*?)\)""")

        val specs = ToolRegistry.kernelToolNames().map { requireNotNull(ToolRegistry.get(it)) } +
            ModuleRegistry.all().flatMap { module -> module.manifest.tools.map { it.spec } }
        // Internal System-1 probes (BridgeTransportPolicy) are not in ToolRegistry.
        val readOnly = specs.filter { it.risk == ToolRisk.READ_ONLY }.map { it.name }.toSet() +
            setOf("laya.status", "laya.predict")
        val signed = specs.filter { it.risk != ToolRisk.READ_ONLY }.map { it.name }.toSet() + "laya.start"

        assertEquals("MUTATING/EXECUTABLE tools a paired bridge would run token-only",
            emptySet<String>(), signed intersect tokenOnly)
        assertEquals("READ_ONLY tools a paired bridge would refuse unsigned",
            emptySet<String>(), readOnly - tokenOnly)
        assertEquals("token-only bridge tools the app does not know as READ_ONLY",
            emptySet<String>(), tokenOnly - readOnly)
    }
}
