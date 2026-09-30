package com.lumena.android.settings

import com.lumena.android.agent.local.CauseProbeExecutionIntent
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionCauseProbeTest {
    private val adapter =
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(LocalSessionSnapshot::class.java)

    @Test
    fun pendingApprovalPersistsOnlyHashedCauseIntent() {
        val rawHypothesis =
            "A long model explanation that must not enter pending metadata"
        val hash =
            "0123456789abcdef01234567"
        val snapshot =
            LocalSessionSnapshot(
                pending =
                    PersistedPendingTool(
                        tool = "file.read",
                        args =
                            mapOf(
                                "path" to
                                    "demo.py"
                            ),
                        requestId = "req-1",
                        reason = "probe",
                        causeProbeIntent =
                            CauseProbeExecutionIntent(
                                hypothesisHash =
                                    hash,
                                onSuccess =
                                    "REJECTS",
                                onFailure =
                                    "SUPPORTS"
                            )
                    )
            )

        val json =
            adapter.toJson(snapshot)
        assertTrue(json.contains(hash))
        assertFalse(
            json.contains(rawHypothesis)
        )

        val decoded =
            requireNotNull(
                adapter.fromJson(json)
            )
        assertEquals(
            hash,
            decoded.pending
                ?.causeProbeIntent
                ?.hypothesisHash
        )
        assertEquals(
            "REJECTS",
            decoded.pending
                ?.causeProbeIntent
                ?.onSuccess
        )
    }

    @Test
    fun legacySessionJsonWithoutWorkThreadsDefaultsSafely() {
        val legacy =
            """
            {
              "chat":[],
              "history":[],
              "task":null,
              "pending":null,
              "inputDraft":"",
              "codeGoal":"Create HTML aquarium"
            }
            """.trimIndent()

        val decoded =
            requireNotNull(
                adapter.fromJson(legacy)
            )

        assertTrue(
            decoded.workThreads
                .anchors
                .isEmpty()
        )
        assertEquals(
            "Create HTML aquarium",
            decoded.codeGoal
        )
    }

    @Test
    fun legacyPendingJsonWithoutCauseIntentDefaultsToNull() {
        val legacy =
            """
            {
              "chat":[],
              "history":[],
              "task":null,
              "pending":{
                "tool":"file.read",
                "args":{"path":"demo.py"},
                "requestId":"req-old",
                "reason":"inspect",
                "control":null,
                "history":[],
                "images":[]
              },
              "inputDraft":""
            }
            """.trimIndent()

        val decoded =
            requireNotNull(
                adapter.fromJson(legacy)
            )

        assertNull(
            decoded.pending
                ?.causeProbeIntent
        )
    }
}
