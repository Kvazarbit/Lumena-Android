package com.lumena.android.agent.local

// Expected RED: signature-only stubs for Trust Gate V2a signed approvals.

interface ApprovalSigner {
    fun publicKeySpki(): ByteArray
    fun sign(message: ByteArray): ByteArray
}

data class BridgeApproval(
    val nonce: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val sig: String
)

object ApprovalCanonical {
    const val VERSION = "lumena-approval-v1"

    fun argsSha256(args: Map<String, String>): String = TODO("Trust Gate V2a")

    fun message(tool: String, argsSha256: String, nonce: String, issuedAt: Long, expiresAt: Long): ByteArray =
        TODO("Trust Gate V2a")

    fun sha256Hex(bytes: ByteArray): String = TODO("Trust Gate V2a")
}

object ApprovalPolicy {
    fun requiresSignature(tool: String): Boolean = false
}

class ApprovalIssuer(
    private val signer: ApprovalSigner,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun approve(tool: String, args: Map<String, String>): BridgeApproval? = null
}

object ApprovalPairing {
    fun fingerprint(spki: ByteArray): String = ""

    fun command(spki: ByteArray): String = ""
}
