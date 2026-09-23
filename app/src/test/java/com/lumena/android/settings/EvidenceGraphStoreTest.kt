package com.lumena.android.settings

import com.lumena.android.agent.core.EvidenceClaimNode
import com.lumena.android.agent.core.EvidenceGraphReducer
import com.lumena.android.agent.core.EvidenceGraphState
import com.lumena.android.agent.core.EvidenceSourceNode
import com.lumena.android.agent.core.EvidenceSourceKind
import com.lumena.android.agent.core.EvidenceVerificationState
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGraphStoreTest {
    private val now = 1_800_000_000_000L

    private fun task(goal: String) = TaskState(
        id = "evidence-test",
        projectId = "lumena",
        goal = goal,
        status = TaskStatus.WAITING_MODEL
    )

    @Test
    fun codecRoundTripPreservesTypedState() {
        var state = EvidenceGraphState()
        val observation = EvidenceGraphProjector.fromToolResult(
            task = task("Find Python documentation online"),
            request = ToolRequest(
                tool = "web.read",
                args = mapOf(
                    "url" to "https://www.python.org/docs/"
                ),
                requestId = "r1"
            ),
            result = ToolResult(
                ok = true,
                stdout =
                    """{"url":"https://www.python.org/docs/","title":"Python docs","text":"Official Python documentation"}"""
            ),
            evidenceId = "ev-1",
            now = now
        ).single()

        state = EvidenceGraphReducer.record(
            state,
            observation
        ).state

        val encoded = EvidenceGraphCodec.encode(state)
        val decoded = EvidenceGraphCodec.decode(encoded)

        assertEquals(state, decoded)
        assertFalse(encoded.contains("requestId"))
    }

    @Test
    fun searchProjectsBoundedSourceEvidence() {
        val result = ToolResult(
            ok = true,
            stdout =
                """{
                  "query":"Bitcoin current price",
                  "provider":"duckduckgo-lite",
                  "results":[
                    {
                      "title":"Bitcoin price today",
                      "url":"https://www.binance.com/en/price/bitcoin/",
                      "snippet":"Bitcoin current price and market data"
                    },
                    {
                      "title":"Bitcoin price",
                      "url":"https://www.coindesk.com/price/bitcoin/",
                      "snippet":"Latest Bitcoin price"
                    }
                  ]
                }"""
        )

        val observations = EvidenceGraphProjector.fromToolResult(
            task = task("Bitcoin current price online"),
            request = ToolRequest(
                tool = "web.search",
                args = mapOf("query" to "Bitcoin current price"),
                requestId = "search-1"
            ),
            result = result,
            evidenceId = "ev-search",
            now = now
        )

        assertEquals(2, observations.size)
        assertTrue(
            observations.all {
                it.sourceKind == EvidenceSourceKind.SEARCH_SNIPPET
            }
        )
        assertEquals(
            "https://binance.com/en/price/bitcoin",
            observations.first().sourceUri
        )
        assertTrue(
            observations.first().projectRelevance > 0.5
        )
        assertTrue(
            observations.all {
                it.statement.length <= 1_600
            }
        )
    }

    @Test
    fun searchThenReadSameUrlUpgradeOneSourceClaimToRetrieved() {
        val search = EvidenceGraphProjector.fromToolResult(
            task = task("Python latest release online"),
            request = ToolRequest(
                tool = "web.search",
                args = mapOf("query" to "Python latest release"),
                requestId = "s1"
            ),
            result = ToolResult(
                ok = true,
                stdout =
                    """{"results":[{"title":"Python Releases","url":"https://www.python.org/downloads/","snippet":"Latest Python release"}]}"""
            ),
            evidenceId = "ev-s",
            now = now
        ).single()

        val read = EvidenceGraphProjector.fromToolResult(
            task = task("Python latest release online"),
            request = ToolRequest(
                tool = "web.read",
                args = mapOf(
                    "url" to "https://python.org/downloads"
                ),
                requestId = "r1"
            ),
            result = ToolResult(
                ok = true,
                stdout =
                    """{"url":"https://www.python.org/downloads/","title":"Download Python","text":"Latest Python releases"}"""
            ),
            evidenceId = "ev-r",
            now = now + 1
        ).single()

        assertEquals(search.claimKey, read.claimKey)

        var state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            search
        ).state
        state = EvidenceGraphReducer.record(
            state,
            read
        ).state

        assertEquals(1, state.claims.size)
        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            state.claims.single().verificationState
        )
    }

    @Test
    fun failedUnknownOrUntraceableResultsAreNotProjected() {
        val request = ToolRequest(
            tool = "web.read",
            args = mapOf("url" to "https://example.org/a"),
            requestId = "r1"
        )
        val currentTask = task("Read documentation online")

        assertTrue(
            EvidenceGraphProjector.fromToolResult(
                currentTask,
                request,
                ToolResult(ok = false, error = "timeout"),
                evidenceId = "ev-f",
                now = now
            ).isEmpty()
        )

        assertTrue(
            EvidenceGraphProjector.fromToolResult(
                currentTask,
                request,
                ToolResult(
                    ok = true,
                    stdout = "body",
                    outcomeUnknown = true
                ),
                evidenceId = "ev-u",
                now = now
            ).isEmpty()
        )

        assertTrue(
            EvidenceGraphProjector.fromToolResult(
                currentTask,
                request,
                ToolResult(ok = true, stdout = "body"),
                evidenceId = null,
                now = now
            ).isEmpty()
        )
    }

    @Test
    fun httpJsonBecomesRetrievedPublicApiEvidence() {
        val observation = EvidenceGraphProjector.fromToolResult(
            task = task("Find current API data online"),
            request = ToolRequest(
                tool = "http.json",
                args = mapOf(
                    "url" to "https://api.example.org/v1/status.json"
                ),
                requestId = "api-1"
            ),
            result = ToolResult(
                ok = true,
                stdout = """{"status":"ok","version":"1.2.3"}"""
            ),
            evidenceId = "ev-api",
            now = now
        ).single()

        assertEquals(
            EvidenceSourceKind.PUBLIC_API,
            observation.sourceKind
        )
        assertEquals(
            "http.json",
            observation.retrievalMethod
        )

        val state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            observation
        ).state
        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            state.claims.single().verificationState
        )
    }

    @Test
    fun urlNormalizationKeepsStableSourceIdentity() {
        assertEquals(
            "https://example.org/docs",
            EvidenceGraphProjector.normalizeUrl(
                "https://www.EXAMPLE.org:443/docs/"
            )
        )
        assertEquals(
            "source:https://example.org/docs",
            EvidenceGraphProjector.sourceClaimKey(
                "https://www.example.org/docs/"
            )
        )
        assertEquals(
            "",
            EvidenceGraphProjector.normalizeUrl(
                "file:///data/local"
            )
        )
    }

    @Test
    fun fullRawBodyIsNotPersistedUnbounded() {
        val hugeMarker = "UNIQUE-END-MARKER"
        val hugeText =
            "Python documentation ".repeat(2_000) +
                hugeMarker

        val observation = EvidenceGraphProjector.fromToolResult(
            task = task("Python documentation online"),
            request = ToolRequest(
                tool = "web.read",
                args = mapOf(
                    "url" to "https://docs.example.org/python"
                )
            ),
            result = ToolResult(
                ok = true,
                stdout =
                    """{"url":"https://docs.example.org/python","title":"Python docs","text":"${hugeText}"}"""
            ),
            evidenceId = "ev-big",
            now = now
        ).single()

        val state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            observation
        ).state
        val encoded = EvidenceGraphCodec.encode(state)

        assertTrue(observation.statement.length <= 1_600)
        assertFalse(encoded.contains(hugeMarker))
        assertTrue(encoded.length < 8_000)
    }

    @Test
    fun trimNeverLeavesClaimWithPartialSourceSet() {
        val newestIds = (1..300).map { "new-source-$it" }
        val olderIds = (1..300).map { "old-source-$it" }

        val sources = (
            newestIds.mapIndexed { index, id ->
                EvidenceSourceNode(
                    id = id,
                    uri = "https://new$index.example/item",
                    host = "new$index.example",
                    kind = EvidenceSourceKind.WEB_PAGE,
                    retrievalMethod = "web.read",
                    evidenceIds = listOf("e-new-$index"),
                    firstObservedAt = now + 100,
                    lastObservedAt = now + 100
                )
            } +
                olderIds.mapIndexed { index, id ->
                    EvidenceSourceNode(
                        id = id,
                        uri = "https://old$index.example/item",
                        host = "old$index.example",
                        kind = EvidenceSourceKind.WEB_PAGE,
                        retrievalMethod = "web.read",
                        evidenceIds = listOf("e-old-$index"),
                        firstObservedAt = now,
                        lastObservedAt = now
                    )
                }
            )

        val newest = EvidenceClaimNode(
            id = "new-claim",
            claimKey = "new-claim",
            statement = "newest",
            verificationState =
                EvidenceVerificationState.CORROBORATED,
            supportSourceIds = newestIds,
            evidenceIds = listOf("e-new"),
            firstObservedAt = now + 100,
            lastObservedAt = now + 100
        )
        val older = EvidenceClaimNode(
            id = "old-claim",
            claimKey = "old-claim",
            statement = "older",
            verificationState =
                EvidenceVerificationState.CORROBORATED,
            supportSourceIds = olderIds,
            evidenceIds = listOf("e-old"),
            firstObservedAt = now,
            lastObservedAt = now
        )

        val trimmed = EvidenceGraphStore.trim(
            EvidenceGraphState(
                claims = listOf(older, newest),
                sources = sources
            )
        )

        assertEquals(listOf("new-claim"), trimmed.claims.map { it.id })
        assertEquals(300, trimmed.sources.size)
        assertEquals(
            newestIds.toSet(),
            trimmed.sources.map { it.id }.toSet()
        )
        assertEquals(
            newestIds,
            trimmed.claims.single().supportSourceIds
        )
    }

    @Test
    fun searchProjectionIsBoundedToTwelveSources() {
        val results = (1..20).joinToString(",") { index ->
            """{"title":"Result $index","url":"https://host$index.example/a","snippet":"Android Vulkan result $index"}"""
        }

        val observations = EvidenceGraphProjector.fromToolResult(
            task = task("Android Vulkan online"),
            request = ToolRequest(
                tool = "web.search",
                args = mapOf("query" to "Android Vulkan")
            ),
            result = ToolResult(
                ok = true,
                stdout = """{"results":[$results]}"""
            ),
            evidenceId = "ev-many",
            now = now
        )

        assertEquals(12, observations.size)
    }
}
