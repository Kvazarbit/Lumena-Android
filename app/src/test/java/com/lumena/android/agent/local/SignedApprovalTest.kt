package com.lumena.android.agent.local

import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Canonical approval encoding and signing through [ApprovalSigner], with a
 * software java.security key. The Android Keystore signer itself cannot run
 * in a JVM test; only the interface it implements is covered here.
 */
class SignedApprovalTest {
    class SoftwareSigner(private val pair: KeyPair = newPair()) : ApprovalSigner {
        override fun publicKeySpki(): ByteArray = pair.public.encoded

        override fun sign(message: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(message)
                sign()
            }

        companion object {
            fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        }
    }

    companion object {
        // Same vector as termux/test_signed_approvals.py.
        const val VECTOR_TOOL = "file.write"
        val VECTOR_ARGS = mapOf(
            "path" to "notes/привіт.txt",
            "content" to "line1\nline2 ✓",
            "mode" to "",
            // UTF-16 order puts the emoji first; UTF-8 byte order puts U+FF5E first.
            "😀" to "utf16-first",
            "～" to "utf8-first"
        )
        const val VECTOR_NONCE = "AAECAwQFBgcICQoLDA0ODw"
        const val VECTOR_ISSUED_AT = 1760000000000L
        const val VECTOR_EXPIRES_AT = 1760000060000L
        const val VECTOR_ARGS_SHA256 = "7804638486a26d9b7d6d239b41090a1e6b13b5345eb28da4d3293bb80ba0d739"
        const val VECTOR_MESSAGE_HEX =
            "000000126c756d656e612d617070726f76616c2d76310000000a66696c652e777269746500000040" +
                "37383034363338343836613236643962376436643233396234313039306131653662313362353334" +
                "3565623238646134643332393362623830626130643733390000001641414543417751464267634943" +
                "516f4c4441304f44770000000d313736303030303030303030300000000d31373630303030303630303030"
        const val VECTOR_MESSAGE_SHA256 = "ef44b4f1b6ac9a09280734db8d0e8189ab1ec5c422a2c21fcd8d896fc62e643e"
        // Signed once by an independent implementation (pyca/cryptography).
        const val VECTOR_SPKI_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEhMc4xWrLNDiH5Q8h64PLKpfaTn8mrYpVslignErsQDz6" +
                "3O0sql4YisCBvIRiJAefiXlMDocO1XaqWKsYbvDpMg=="
        const val VECTOR_SIG_B64URL =
            "MEYCIQDW1Fg_z6UvpWr0UICeUIavZ5D5B3U1bkkMC4H91MXN8gIhANMujUPX6rtAv67dOAUi0cqd7_Te" +
                "exwOrqHponhaNUYS"

        fun verifies(spki: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            return Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun unb64url(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    }

    private fun vectorMessage(): ByteArray = ApprovalCanonical.message(
        VECTOR_TOOL,
        ApprovalCanonical.argsSha256(VECTOR_ARGS),
        VECTOR_NONCE,
        VECTOR_ISSUED_AT,
        VECTOR_EXPIRES_AT
    )

    @Test fun crossLanguageVectorMatchesBridge() {
        assertEquals(VECTOR_ARGS_SHA256, ApprovalCanonical.argsSha256(VECTOR_ARGS))
        val message = vectorMessage()
        assertEquals(VECTOR_MESSAGE_HEX, hex(message))
        assertEquals(VECTOR_MESSAGE_SHA256, ApprovalCanonical.sha256Hex(message))
    }

    @Test fun independentlySignedVectorVerifiesInJava() {
        val spki = Base64.getDecoder().decode(VECTOR_SPKI_B64)
        val signature = unb64url(VECTOR_SIG_B64URL)
        assertTrue(verifies(spki, vectorMessage(), signature))
        val tampered = ApprovalCanonical.message(
            VECTOR_TOOL,
            ApprovalCanonical.argsSha256(VECTOR_ARGS + ("mode" to "x")),
            VECTOR_NONCE,
            VECTOR_ISSUED_AT,
            VECTOR_EXPIRES_AT
        )
        assertFalse(verifies(spki, tampered, signature))
    }

    @Test fun argsDigestIgnoresInsertionOrderButNotContent() {
        val a = linkedMapOf("path" to "a.txt", "content" to "x")
        val b = linkedMapOf("content" to "x", "path" to "a.txt")
        assertEquals(ApprovalCanonical.argsSha256(a), ApprovalCanonical.argsSha256(b))
        assertNotEquals(ApprovalCanonical.argsSha256(a), ApprovalCanonical.argsSha256(a + ("content" to "y")))
        // Length prefixes: moving a character between key and value changes the digest.
        assertNotEquals(
            ApprovalCanonical.argsSha256(mapOf("ab" to "c")),
            ApprovalCanonical.argsSha256(mapOf("a" to "bc"))
        )
        assertNotEquals(ApprovalCanonical.argsSha256(emptyMap()), ApprovalCanonical.argsSha256(mapOf("" to "")))
    }

    @Test fun issuedApprovalVerifiesOnlyForItsKeyToolAndArgs() {
        val signer = SoftwareSigner()
        val args = mapOf("path" to "a.txt", "content" to "safe")
        val approval = requireNotNull(ApprovalIssuer(signer).approve("file.write", args)) {
            "file.write must be signed"
        }
        val signature = unb64url(approval.sig)
        fun message(tool: String, sent: Map<String, String>) = ApprovalCanonical.message(
            tool, ApprovalCanonical.argsSha256(sent), approval.nonce, approval.issuedAt, approval.expiresAt
        )
        assertTrue(verifies(signer.publicKeySpki(), message("file.write", args), signature))
        assertFalse(verifies(signer.publicKeySpki(), message("file.write", args + ("content" to "evil")), signature))
        assertFalse(verifies(signer.publicKeySpki(), message("file.patch", args), signature))
        assertFalse(verifies(SoftwareSigner().publicKeySpki(), message("file.write", args), signature))
    }

    @Test fun approvalsAreShortLivedWithFreshNonces() {
        val now = 1_760_000_000_000L
        val issuer = ApprovalIssuer(SoftwareSigner(), clock = { now })
        val first = requireNotNull(issuer.approve("dir.create", mapOf("path" to "a")))
        val second = requireNotNull(issuer.approve("dir.create", mapOf("path" to "a")))
        assertEquals(now, first.issuedAt)
        assertTrue(first.expiresAt - first.issuedAt in 1L..120_000L)
        assertNotEquals(first.nonce, second.nonce)
        assertTrue(first.nonce, Regex("[A-Za-z0-9_-]{16,128}").matches(first.nonce))
    }

    @Test fun onlyMutatingAndExecutableToolsAreSigned() {
        val issuer = ApprovalIssuer(SoftwareSigner())
        listOf("health", "web.search", "file.read", "git.status", "inspect.batch", "laya.status", "laya.predict")
            .forEach { assertNull("$it is read-only", issuer.approve(it, emptyMap())) }
        listOf("file.write", "dir.create", "git.commit", "python.run", "python.tests", "ollama.pull", "laya.start")
            .forEach { assertNotNull("$it must be signed", issuer.approve(it, emptyMap())) }
        ToolRegistry.all().forEach { spec ->
            assertEquals(spec.name, spec.risk != ToolRisk.READ_ONLY, ApprovalPolicy.requiresSignature(spec.name))
        }
    }

    @Test fun pairingCommandInstallsTheKeyLocallyAndNeverPostsIt() {
        val spki = Base64.getDecoder().decode(VECTOR_SPKI_B64)
        val command = ApprovalPairing.command(spki)
        assertTrue(command, command.contains("'ecdsa-p256 $VECTOR_SPKI_B64'"))
        assertTrue(command, command.contains("\$HOME/.lumena/approval_pubkey"))
        // The key reaches the bridge as a file written from the owner's own
        // Termux shell, never through an HTTP body.
        command.lines()
            .filter { "curl" in it || "-d " in it }
            .forEach { assertFalse(it, VECTOR_SPKI_B64 in it) }
        assertEquals("bc880bfc41329858", ApprovalPairing.fingerprint(spki))
    }
}
