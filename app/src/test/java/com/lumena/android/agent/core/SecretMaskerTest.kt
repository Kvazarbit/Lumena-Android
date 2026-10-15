package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretMaskerTest {
    private fun maskedKinds(text: String): Map<SecretKind, Int> = SecretMasker.mask(text).byKind

    @Test fun bearerTokenIsMaskedButSchemeStaysReadable() {
        val result = SecretMasker.mask("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")
        assertFalse(result.text.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(result.text.contains("Bearer [MASKED"))
        assertEquals(mapOf(SecretKind.BEARER to 1), result.byKind)
    }

    @Test fun providerKeysAreMasked() {
        assertEquals(mapOf(SecretKind.SK_KEY to 1), maskedKinds("key sk-proj-AbCdEfGhIjKlMnOpQrSt123"))
        assertEquals(mapOf(SecretKind.GITHUB_TOKEN to 1), maskedKinds("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
        assertEquals(mapOf(SecretKind.GITHUB_TOKEN to 1), maskedKinds("github_pat_11ABCDEFG0123456789_abcdefghijklmnop"))
    }

    @Test fun lineReferencingLumenaStateIsMaskedWhole() {
        val result = SecretMasker.mask("a\ncat ~/.lumena/bridge_token -> Zx81_qq\nb")
        assertEquals("a\n[MASKED:lumena-state line]\nb", result.text)
        assertEquals(mapOf(SecretKind.LUMENA_STATE_LINE to 1), result.byKind)
        // Other dot-lumena names are not state paths.
        assertTrue(maskedKinds("TMP=\$HOME/.lumena-update").isEmpty())
    }

    @Test fun dotenvAndJsonSecretValuesAreMaskedButNamesKept() {
        val result = SecretMasker.mask(
            "export LUMENA_BRAVE_API_KEY=BSA123\nEXA_TOKEN=abc\n{\"OPENROUTER_API_KEY\": \"or-xyz\"}"
        )
        assertFalse(result.text.contains("BSA123"))
        assertFalse(result.text.contains("abc\n"))
        assertFalse(result.text.contains("or-xyz"))
        assertTrue(result.text.contains("LUMENA_BRAVE_API_KEY=[MASKED"))
        assertTrue(result.text.contains("\"OPENROUTER_API_KEY\": \"[MASKED"))
        assertEquals(mapOf(SecretKind.ENV_SECRET to 3), result.byKind)
    }

    @Test fun ordinaryCodeAndProseAreNotMasked() {
        val code = """
            MAX_TOKENS = 4096
            token = tokenizer.encode(text)
            api_key = os.environ["API_KEY"]
            risk-assessment-framework-v2
            PATH=/usr/bin
            DEBUG=1
        """.trimIndent()
        val result = SecretMasker.mask(code)
        assertEquals(code, result.text)
        assertEquals(0, result.masks)
    }

    @Test fun maskingIsIdempotent() {
        val once = SecretMasker.mask("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 LUMENA_BRIDGE_TOKEN=x1").text
        val twice = SecretMasker.mask(once)
        assertEquals(once, twice.text)
        assertEquals(0, twice.masks)
    }
}
