package com.lumena.android.companion

internal object CompanionRequestLifecycle {
    fun shouldAccept(
        fingerprint: String,
        handledFingerprint: String?,
        activeFingerprint: String?,
        busy: Boolean
    ): Boolean =
        !busy &&
            fingerprint != handledFingerprint &&
            fingerprint != activeFingerprint

    fun isVisible(
        fingerprint: String,
        handledFingerprint: String?,
        activeFingerprint: String?
    ): Boolean =
        fingerprint != handledFingerprint &&
            fingerprint != activeFingerprint

    fun shouldAutoScan(
        isGenerating: Boolean,
        lastUpdatedAtMs: Long,
        nowMs: Long,
        quietMs: Long = 900L
    ): Boolean {
        if (isGenerating) return false
        if (lastUpdatedAtMs <= 0L || nowMs < lastUpdatedAtMs) return false
        return nowMs - lastUpdatedAtMs >= quietMs
    }
}
