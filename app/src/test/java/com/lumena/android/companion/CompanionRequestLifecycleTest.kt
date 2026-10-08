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
    fun autoScanWaitsForStablePostGenerationQuietWindow() {
        assertFalse(
            CompanionRequestLifecycle.shouldAutoScan(
                isGenerating = true,
                lastUpdatedAtMs = 1_000,
                lastGeneratingAtMs = 4_900,
                nowMs = 10_000
            )
        )
        assertFalse(
            CompanionRequestLifecycle.shouldAutoScan(
                isGenerating = false,
                lastUpdatedAtMs = 7_000,
                lastGeneratingAtMs = 0,
                nowMs = 10_000
            )
        )
        assertFalse(
            CompanionRequestLifecycle.shouldAutoScan(
                isGenerating = false,
                lastUpdatedAtMs = 1_000,
                lastGeneratingAtMs = 7_000,
                nowMs = 10_000
            )
        )
        assertTrue(
            CompanionRequestLifecycle.shouldAutoScan(
                isGenerating = false,
                lastUpdatedAtMs = 1_000,
                lastGeneratingAtMs = 4_000,
                nowMs = 10_000
            )
        )
    }

    @Test
    fun transientFalseGeneratingStateCannotTriggerOldNineHundredMsRace() {
        assertFalse(
            CompanionRequestLifecycle.shouldAutoScan(
                isGenerating = false,
                lastUpdatedAtMs = 8_500,
                lastGeneratingAtMs = 8_800,
                nowMs = 10_000
            )
        )
    }
}
