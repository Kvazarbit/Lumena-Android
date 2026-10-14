package com.lumena.android.companion

import com.lumena.android.agent.local.ToolGate
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionClipboardImportTest {
    @Test
    fun pastedChatGptCodeBlockHasSameFingerprintAsAccessibility() {
        val text = """
            LUMENA_TOOL
            {"session_id":"project-7f3a","task_id":"clipboard-1","model_id":"chatgpt","tool":"file.read","args":{"path":"README.md"},"reason":"inspect"}
        """.trimIndent()

        val fromClipboard = requireNotNull(
            CompanionClipboardImport.parse("```text\n$text\n```")
        )
        val fromAccessibility = requireNotNull(CompanionProtocol.parseVisibleText(text))
        assertEquals(fromAccessibility.fingerprint, fromClipboard.fingerprint)
        assertEquals(fromAccessibility.decision.request, fromClipboard.decision.request)
        assertEquals("project-7f3a", fromClipboard.sessionId)
        assertEquals("clipboard-1", fromClipboard.taskId)
    }

    @Test
    fun longUntruncatedClipboardToolBlockPreservesSource() {
        val source = "x".repeat(64_000)
        val json = """{"session_id":"s","task_id":"large-1","tool":"file.write","args":{"path":"long.txt","content":"$source"}}"""
        val command = requireNotNull(
            CompanionClipboardImport.parse("LUMENA_TOOL\n$json")
        )
        assertEquals(source, command.decision.request.args["content"])
        assertEquals("file.write", command.decision.request.tool)
    }

    @Test
    fun missingMarkerIncompleteJsonAndOversizeAreRejected() {
        assertNull(CompanionClipboardImport.parse(null))
        assertNull(CompanionClipboardImport.parse("   "))
        assertNull(CompanionClipboardImport.parse("""{"tool":"system.time","args":{}}"""))
        assertTrue(CompanionClipboardImport.diagnostic(null).contains("порожній"))
        assertTrue(CompanionClipboardImport.diagnostic("plain text").contains("LUMENA_TOOL"))

        val valid = """LUMENA_TOOL
            {"tool":"system.time","args":{}}"""
        val partial = """LUMENA_TOOL
            {"tool":"file.read","args":{"path":"README.md"}"""
        assertNull(CompanionClipboardImport.parse(valid + "\n" + partial))
        assertTrue(
            CompanionClipboardImport.diagnostic(valid + "\n" + partial)
                .contains("неповний")
        )

        val oversized = "LUMENA_TOOL\n" + "x".repeat(160_001)
        assertNull(CompanionClipboardImport.parse(oversized))
        assertTrue(CompanionClipboardImport.diagnostic(oversized).contains("завелика"))
    }

    @Test
    fun base64IntegrityChecksAlsoApplyToClipboard() {
        val raw = """{"session_id":"s","task_id":"t","tool":"python.run","args":{"script":"probe.py"}}"""
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val block = """LUMENA_TOOL
            {"encoding":"base64-sha256-v1","payload_b64":"$encoded","sha256":"$sha"}"""

        val parsed = requireNotNull(CompanionClipboardImport.parse(block))
        assertEquals(raw, parsed.rawJson)
        assertEquals("probe.py", parsed.decision.request.args["script"])

        val tampered = block.replace(
            "\"sha256\":\"$sha\"",
            "\"sha256\":\"0000000000000000000000000000000000000000000000000000000000000000\""
        )
        assertNull(CompanionClipboardImport.parse(tampered))
        assertTrue(CompanionClipboardImport.diagnostic(tampered).contains("SHA-256"))
    }

    @Test
    fun pastedExecutableStillGoesThroughNormalExternalToolGate() {
        val command = requireNotNull(
            CompanionClipboardImport.parse(
                """LUMENA_TOOL
                {"tool":"python.run","args":{"script":"probe.py"},"reason":"run"}"""
            )
        )
        val planned = ToolGate.plan(command.decision, externalSource = true)
        assertTrue(planned.allowed)
        assertTrue(planned.requiresConfirmation)

        val unregistered = requireNotNull(
            CompanionClipboardImport.parse(
                """LUMENA_TOOL
                {"tool":"shell.execute","args":{"command":"rm -rf /"},"reason":"bad"}"""
            )
        )
        assertFalse(ToolGate.plan(unregistered.decision, externalSource = true).allowed)
    }
}
