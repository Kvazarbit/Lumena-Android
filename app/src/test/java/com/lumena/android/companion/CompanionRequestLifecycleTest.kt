package com.lumena.android.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRequestLifecycleTest {
    @Test
    fun completedRequestIsNeitherAcceptedNorVisible() {
        assertFalse(
            CompanionRequestLifecycle.shouldAccept(
                fingerprint = "same",
                handledFingerprint = "same",
                activeFingerprint = null,
                busy = false
            )
        )
        assertFalse(
            CompanionRequestLifecycle.isVisible(
                fingerprint = "same",
                handledFingerprint = "same",
                activeFingerprint = null
            )
        )
    }

    @Test
    fun activeRequestCannotBeReentered() {
        assertFalse(
            CompanionRequestLifecycle.shouldAccept(
                fingerprint = "active",
                handledFingerprint = null,
                activeFingerprint = "active",
                busy = false
            )
        )
        assertFalse(
            CompanionRequestLifecycle.isVisible(
                fingerprint = "active",
                handledFingerprint = null,
                activeFingerprint = "active"
            )
        )
    }

    @Test
    fun newRequestAfterCompletionIsAcceptedAndVisible() {
        assertTrue(
            CompanionRequestLifecycle.shouldAccept(
                fingerprint = "new",
                handledFingerprint = "old",
                activeFingerprint = null,
                busy = false
            )
        )
        assertTrue(
            CompanionRequestLifecycle.isVisible(
                fingerprint = "new",
                handledFingerprint = "old",
                activeFingerprint = null
            )
        )
    }

    @Test
    fun busyStateBlocksAcceptance() {
        assertFalse(
            CompanionRequestLifecycle.shouldAccept(
                fingerprint = "new",
                handledFingerprint = null,
                activeFingerprint = null,
                busy = true
            )
        )
    }

    @Test
    fun autoScanWaitsForGenerationToFinishAndUiToSettle() {
        assertFalse(
            CompanionRequestLifecycle.shouldAutoScan(
                isGenerating = true,
                lastUpdatedAtMs = 1_000,
                nowMs = 5_000
            )
        )
        assertFalse(
            CompanionRequestLifecycle.shouldAutoScan(
                isGenerating = false,
                lastUpdatedAtMs = 4_500,
                nowMs = 5_000
            )
        )
        assertTrue(
            CompanionRequestLifecycle.shouldAutoScan(
                isGenerating = false,
                lastUpdatedAtMs = 4_000,
                nowMs = 5_000
            )
        )
    }
}
