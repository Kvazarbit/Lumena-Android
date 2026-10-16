package com.lumena.android.agent.core

import java.util.concurrent.atomic.AtomicLong

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

/**
 * Masks KNOWN secret shapes before text leaves the phone for a cloud model.
 *
 * It is a pattern filter, not a guarantee: a bare token with no recognizable
 * prefix or name (for example the bridge token value on its own) is not
 * detected. Upper-case env names keep false positives in code low:
 * `MAX_TOKENS = 4096` and `api_key = os.environ[...]` stay readable.
 */
object SecretMasker {
    private const val MASK = "[MASKED"
    private val secretName = Regex("KEY|TOKEN|SECRET|PASSWORD|PASSWD|PASS|AUTH|CRED|COOKIE")

    private val lumenaStateLine = Regex("""(?m)^[^\n]*/\.lumena(?![\w.-])[^\n]*$""")
    private val bearer = Regex("""(?i)\b(bearer)\s+([A-Za-z0-9._~+/=-]{8,})""")
    private val skKey = Regex("""\bsk-[A-Za-z0-9_-]{16,}""")
    private val githubToken = Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})""")
    private val envAssignment = Regex("""(?<![A-Za-z0-9_$.])([A-Z][A-Z0-9_]{1,80})=("[^"\n]*"|'[^'\n]*'|[^\s'"]+)""")
    private val jsonEnvEntry = Regex(""""([A-Z][A-Z0-9_]{1,80})"(\s*:\s*)"([^"\n]*)"""")

    fun mask(text: String): MaskResult {
        val counts = mutableMapOf<SecretKind, Int>()
        fun hit(kind: SecretKind) {
            counts[kind] = (counts[kind] ?: 0) + 1
        }

        var out = lumenaStateLine.replace(text) {
            hit(SecretKind.LUMENA_STATE_LINE)
            "[MASKED:lumena-state line]"
        }
        out = bearer.replace(out) {
            hit(SecretKind.BEARER)
            it.groupValues[1] + " [MASKED:bearer]"
        }
        out = skKey.replace(out) {
            hit(SecretKind.SK_KEY)
            "[MASKED:sk]"
        }
        out = githubToken.replace(out) {
            hit(SecretKind.GITHUB_TOKEN)
            "[MASKED:github]"
        }
        out = envAssignment.replace(out) {
            val (name, value) = it.destructured
            if (!secretName.containsMatchIn(name) || value.trim('"', '\'').isEmpty() || MASK in value) {
                it.value
            } else {
                hit(SecretKind.ENV_SECRET)
                "$name=[MASKED:env]"
            }
        }
        out = jsonEnvEntry.replace(out) {
            val (name, colon, value) = it.destructured
            if (!secretName.containsMatchIn(name) || value.isEmpty() || value.startsWith(MASK)) {
                it.value
            } else {
                hit(SecretKind.ENV_SECRET)
                "\"$name\"$colon\"[MASKED:env]\""
            }
        }
        return MaskResult(out, counts.toMap())
    }
}

data class CloudMaskingSnapshot(
    val cloudRequests: Long = 0,
    val requestsWithMasks: Long = 0,
    val maskedValues: Long = 0,
    val byKind: Map<SecretKind, Long> = emptyMap(),
    val lastCloudModel: String? = null
)

/** Process-wide counters for Diagnostics; they reset when the app restarts. */
object CloudSecretMaskingStats {
    private val cloudRequests = AtomicLong()
    private val requestsWithMasks = AtomicLong()
    private val maskedValues = AtomicLong()
    private val byKind = SecretKind.entries.associateWith { AtomicLong() }

    @Volatile
    private var lastCloudModel: String? = null

    fun record(model: String, results: List<MaskResult>) {
        cloudRequests.incrementAndGet()
        lastCloudModel = model.take(120)
        val total = results.sumOf { it.masks }
        if (total == 0) return
        requestsWithMasks.incrementAndGet()
        maskedValues.addAndGet(total.toLong())
        results.forEach { result ->
            result.byKind.forEach { (kind, count) -> byKind.getValue(kind).addAndGet(count.toLong()) }
        }
    }

    fun snapshot(): CloudMaskingSnapshot = CloudMaskingSnapshot(
        cloudRequests = cloudRequests.get(),
        requestsWithMasks = requestsWithMasks.get(),
        maskedValues = maskedValues.get(),
        byKind = byKind.mapValues { it.value.get() },
        lastCloudModel = lastCloudModel
    )
}
