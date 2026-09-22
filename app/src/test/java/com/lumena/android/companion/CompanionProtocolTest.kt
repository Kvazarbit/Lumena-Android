package com.lumena.android.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionProtocolTest {
    private fun parse(url: String) = CompanionProtocol.parseVisibleText(
        """
        LUMENA_TOOL
        {"tool":"http.get","args":{"url":"$url"},"reason":"probe"}
        """.trimIndent()
    )!!

    @Test
    fun accessibilityTransportArtifactsAreRepairedForAsciiUrls() {
        assertEquals(
            "https://example.com/path",
            parse("ht\u2060tps://exa\ufeffmple.com/path").decision.request.args["url"]
        )
        assertEquals(
            "https://example.com/path",
            parse("https://exa\ufffdmple.com/path").decision.request.args["url"]
        )
    }

    @Test
    fun ambiguousReplacementInUnicodeUrlIsPreservedForBridgeRejection() {
        val value = parse("https://b\ufffdücher.example").decision.request.args["url"].orEmpty()
        assertTrue(value.contains('\ufffd'))
    }

    @Test
    fun semanticallySameTransportDamagedCommandHasStableFingerprint() {
        val clean = parse("https://example.com/path")
        val damaged = parse("https://exa\u2060mple.com/path")
        assertEquals(clean.fingerprint, damaged.fingerprint)
    }

    @Test
    fun differentUrlHasDifferentFingerprint() {
        assertNotEquals(
            parse("https://example.com/a").fingerprint,
            parse("https://example.com/b").fingerprint
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
