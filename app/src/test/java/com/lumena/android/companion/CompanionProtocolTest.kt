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
}
