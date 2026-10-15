package com.lumena.android.agent.core

/** Red-test stub: masking lands in the next commit. */
enum class SecretKind {
    BEARER,
    SK_KEY,
    GITHUB_TOKEN,
    LUMENA_STATE_LINE,
    ENV_SECRET
}

data class MaskResult(
    val text: String,
    val byKind: Map<SecretKind, Int> = emptyMap()
) {
    val masks: Int
        get() = byKind.values.sum()
}

object SecretMasker {
    fun mask(text: String): MaskResult = MaskResult(text)
}

data class CloudMaskingSnapshot(
    val cloudRequests: Long = 0,
    val requestsWithMasks: Long = 0,
    val maskedValues: Long = 0,
    val byKind: Map<SecretKind, Long> = emptyMap(),
    val lastCloudModel: String? = null
)

object CloudSecretMaskingStats {
    fun record(model: String, results: List<MaskResult>) {}

    fun snapshot(): CloudMaskingSnapshot = CloudMaskingSnapshot()
}
