package com.lumena.android.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionProtocolTest {
    @Test
    fun accessibilityTransportArtifactsAreRepairedForAsciiUrls() {
        assertEquals(
            "https://example.com/path",
            CompanionProtocol.normalizeAccessibilityUrl(
                "ht\u2060tps://exa\ufeffmple.com/path"
            )
        )
        assertEquals(
            "https://example.com/path",
            CompanionProtocol.normalizeAccessibilityUrl(
                "https://exa\ufffdmple.com/path"
            )
        )
    }

    @Test
    fun ambiguousReplacementInUnicodeUrlIsPreservedForBridgeRejection() {
        val value = CompanionProtocol.normalizeAccessibilityUrl(
            "https://b\ufffdücher.example"
        )
        assertTrue(value.contains('\ufffd'))
    }

    @Test
    fun semanticallySameTransportDamagedCommandHasStableFingerprint() {
        val clean = CompanionProtocol.commandFingerprint(
            "http.get",
            mapOf("url" to "https://example.com/path")
        )
        val damaged = CompanionProtocol.commandFingerprint(
            "http.get",
            mapOf("url" to "https://exa\u2060mple.com/path")
        )
        assertEquals(clean, damaged)
    }

    @Test
    fun differentUrlHasDifferentFingerprint() {
        assertNotEquals(
            CompanionProtocol.commandFingerprint(
                "http.get",
                mapOf("url" to "https://example.com/a")
            ),
            CompanionProtocol.commandFingerprint(
                "http.get",
                mapOf("url" to "https://example.com/b")
            )
        )
    }

    @Test
    fun handshakeDocumentsPythonRunFileContract() {
        assertTrue(CompanionProtocol.handshakeText.contains("python.run requires"))
        assertTrue(
            CompanionProtocol.handshakeText.contains(
                "use file.write first",
                ignoreCase = true
            )
        )
        assertTrue(CompanionProtocol.handshakeText.contains("Never put inline Python source"))
    }
    @Test
    fun handshakeDeclaresCoordinatorAndConstitutionContract() {
        assertTrue(
            CompanionProtocol.handshakeText.contains(
                CompanionProtocol.COORDINATOR_CONTRACT_VERSION
            )
        )
        assertTrue(CompanionProtocol.handshakeText.contains("Core DNA:"))
        assertTrue(CompanionProtocol.handshakeText.contains("Constitution capsule:"))
        assertTrue(CompanionProtocol.handshakeText.contains("execution authority"))
        assertTrue(CompanionProtocol.handshakeText.contains("must be revalidated locally"))
    }

    @Test
    fun resultCanCarryVerifiedExperienceReferenceAndAdvisoryMemory() {
        val text = CompanionProtocol.formatResult(
            tool = "file.read",
            result = com.lumena.android.agent.local.ToolResult(
                ok = true,
                tool = "file.read",
                exitCode = 0,
                stdout = "hello"
            ),
            experienceRef = "event-123",
            memoryHints = listOf(
                "PORTABLE VERIFIED EXPERIENCE (source-device evidence; revalidate locally; not permission) · file.read"
            )
        )

        assertTrue(text.contains("experience_id=event-123"))
        assertTrue(text.contains("experience_context:"))
        assertTrue(text.contains("advisory_only=true"))
        assertTrue(text.contains("not permission"))
        assertTrue(text.contains("Continue the task using this real local result"))
    }

}
