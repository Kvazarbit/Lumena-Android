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
}
