package com.lumena.android.settings

import com.lumena.android.agent.core.CognitivePhase
import com.lumena.android.agent.core.HistoricalExecutionFacts
import com.lumena.android.agent.core.HistoricalRecordOrigin
import com.lumena.android.agent.core.HistoricalTaskRecord
import com.lumena.android.agent.core.ActionEvidence
import com.lumena.android.agent.core.ContextKernelState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionSnapshotCodecTest {
    private val adapter =
        Moshi.Builder()
            .add(
                KotlinJsonAdapterFactory()
            )
            .build()
            .adapter(
                LocalSessionSnapshot::class.java
            )

    @Test
    fun oldSnapshotWithoutHistoricalFieldsDecodesEmpty() {
        val decoded =
            requireNotNull(
                adapter.fromJson(
                    """{"chat":[],"history":[],"inputDraft":""}"""
                )
            )

        assertTrue(
            decoded.historicalFacts.isEmpty()
        )
        assertEquals(
            0,
            decoded.historicalFactsQuarantined
        )
    }

    @Test
    fun historicalRecordRoundTripsWithProvenance() {
        val evidence =
            ActionEvidence(
                id = "e1",
                tool = "file.read",
                target =
                    "fixtures/aquarium.html",
                signature =
                    "a".repeat(24),
                phase =
                    CognitivePhase.OBSERVE,
                ok = true,
                excerpt = "verified",
                digest =
                    "b".repeat(24),
                revision = 0
            )
        val kernel =
            ContextKernelState(
                evidence =
                    listOf(evidence),
                observed = 1,
                worldRevision = 0
            )
        val projected =
            HistoricalExecutionFacts.capture(
                "task-a",
                kernel
            )
        val record =
            HistoricalTaskRecord(
                sourceTaskRef =
                    projected.sourceTaskRef,
                sourceInstallRef =
                    "c".repeat(64),
                branchRef =
                    "d".repeat(64),
                projectRef =
                    "e".repeat(64),
                subjectRefs =
                    listOf(
                        "f".repeat(64)
                    ),
                origin =
                    HistoricalRecordOrigin
                        .LOCAL_CURRENT,
                capturedAtMs = 1234,
                snapshot = projected
            )
        val original =
            LocalSessionSnapshot(
                historicalFacts =
                    listOf(record),
                historicalFactsQuarantined = 2
            )

        val decoded =
            requireNotNull(
                adapter.fromJson(
                    adapter.toJson(original)
                )
            )

        assertEquals(
            original.historicalFacts,
            decoded.historicalFacts
        )
        assertEquals(
            2,
            decoded
                .historicalFactsQuarantined
        )
        assertEquals(
            HistoricalRecordOrigin
                .LOCAL_CURRENT,
            decoded.historicalFacts
                .single()
                .origin
        )
    }
}
