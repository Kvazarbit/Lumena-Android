package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveWebResearchTest {
    private val now = 1_800_000_000_000L

    @Test
    fun candidateFactoryEmitsOnlyRegisteredReadOnlyWebTools() {
        val candidates = AdaptiveWebStrategyFactory.candidates(
            goal = "Знайди в інтернеті офіційну документацію Android WorkManager",
            sourceUrls = listOf(
                "https://developer.android.com/develop/background-work/background-tasks/persistent",
                "https://example.org/api/status.json"
            )
        )

        assertTrue(candidates.isNotEmpty())
        candidates.forEach { candidate ->
            val spec = ToolRegistry.get(candidate.tool)
            assertTrue(spec != null)
            assertEquals(ToolRisk.READ_ONLY, spec!!.risk)
            assertTrue(
                candidate.tool in setOf(
                    "web.search",
                    "web.read",
                    "http.get",
                    "http.json"
                )
            )
        }
    }

    @Test
    fun strongVerifiedHostHistoryRaisesDirectReadRanking() {
        val candidates = AdaptiveWebStrategyFactory.candidates(
            goal = "Find current Android WorkManager documentation online",
            sourceUrls = listOf(
                "https://developer.android.com/docs",
                "https://other.example/docs"
            )
        )
        val observations = (1..12).map { index ->
            WebStrategyObservation(
                id = "ok-$index",
                kind = WebStrategyKind.READ_DIRECT,
                tool = "web.read",
                host = "developer.android.com",
                ok = true,
                relevance = 0.95,
                elapsedMs = 700,
                at = now - index * 1_000L
            )
        }

        val ranking = JevLikeWebCalibrationRanker.rank(
            candidates,
            observations,
            now
        )

        val developer = ranking.scores.first {
            it.candidate.id == "read:developer.android.com"
        }
        val other = ranking.scores.first {
            it.candidate.id == "read:other.example"
        }

        assertTrue(developer.accessProbability > other.accessProbability)
        assertTrue(developer.relevanceProbability > other.relevanceProbability)
        assertTrue(developer.utility > other.utility)
        assertTrue(developer.calibrationConfidence > other.calibrationConfidence)
        assertTrue(developer.calibrated)
    }

    @Test
    fun localAuthFailureDoesNotDamageDomainCalibration() {
        val candidate = WebStrategyCandidate(
            id = "read:python.org",
            kind = WebStrategyKind.READ_DIRECT,
            tool = "web.read",
            host = "python.org",
            guidance = "read"
        )
        val observations = listOf(
            WebStrategyObservation(
                id = "auth",
                kind = WebStrategyKind.READ_DIRECT,
                tool = "web.read",
                host = "python.org",
                ok = false,
                relevance = null,
                elapsedMs = 20,
                failureClass = "AUTH_OR_CONFIG",
                dependency = "termux_bridge",
                calibrationEligible = false,
                at = now - 2_000
            ),
            WebStrategyObservation(
                id = "ok",
                kind = WebStrategyKind.READ_DIRECT,
                tool = "web.read",
                host = "python.org",
                ok = true,
                relevance = 0.9,
                elapsedMs = 600,
                at = now - 1_000
            )
        )

        val score = JevLikeWebCalibrationRanker.rank(
            listOf(candidate),
            observations,
            now
        ).best()!!

        assertEquals(1, score.evidenceCount)
        assertTrue(score.accessProbability > 0.5)
    }

    @Test
    fun repeatedChallengeFailurePenalizesThatDirectRoute() {
        val candidates = listOf(
            WebStrategyCandidate(
                id = "read:blocked.example",
                kind = WebStrategyKind.READ_DIRECT,
                tool = "web.read",
                host = "blocked.example",
                guidance = "read blocked"
            ),
            WebStrategyCandidate(
                id = "read:open.example",
                kind = WebStrategyKind.READ_DIRECT,
                tool = "web.read",
                host = "open.example",
                guidance = "read open"
            )
        )
        val observations = buildList {
            repeat(8) { index ->
                add(
                    WebStrategyObservation(
                        id = "blocked-$index",
                        kind = WebStrategyKind.READ_DIRECT,
                        tool = "web.read",
                        host = "blocked.example",
                        ok = false,
                        relevance = null,
                        elapsedMs = 500,
                        failureClass = "DEPENDENCY_EXHAUSTED",
                        at = now - index * 2_000L
                    )
                )
                add(
                    WebStrategyObservation(
                        id = "open-$index",
                        kind = WebStrategyKind.READ_DIRECT,
                        tool = "web.read",
                        host = "open.example",
                        ok = true,
                        relevance = 0.8,
                        elapsedMs = 800,
                        at = now - index * 2_000L
                    )
                )
            }
        }

        val ranking = JevLikeWebCalibrationRanker.rank(
            candidates,
            observations,
            now
        )

        assertEquals("read:open.example", ranking.best()!!.candidate.id)
        assertTrue(
            ranking.scores.first { it.candidate.id == "read:open.example" }
                .utility >
                ranking.scores.first { it.candidate.id == "read:blocked.example" }
                    .utility
        )
    }

    @Test
    fun verifiedEnglishSearchCanOutrankWeakCurrentLanguageSearch() {
        val candidates = AdaptiveWebStrategyFactory.candidates(
            goal = "Знайди в інтернеті актуальну документацію Python packaging"
        )
        assertTrue(
            candidates.any {
                it.kind == WebStrategyKind.SEARCH_ENGLISH_TECHNICAL
            }
        )

        val observations = buildList {
            repeat(10) { index ->
                add(
                    WebStrategyObservation(
                        id = "en-$index",
                        kind = WebStrategyKind.SEARCH_ENGLISH_TECHNICAL,
                        tool = "web.search",
                        queryClass = WebQueryClass.ENGLISH,
                        ok = true,
                        relevance = 0.95,
                        elapsedMs = 900,
                        at = now - index * 1_000L
                    )
                )
            }
            repeat(6) { index ->
                add(
                    WebStrategyObservation(
                        id = "ua-$index",
                        kind = WebStrategyKind.SEARCH_CURRENT_LANGUAGE,
                        tool = "web.search",
                        queryClass = WebQueryClass.CYRILLIC,
                        ok = true,
                        relevance = 0.20,
                        elapsedMs = 850,
                        at = now - index * 1_000L
                    )
                )
            }
        }

        val ranking = JevLikeWebCalibrationRanker.rank(
            candidates,
            observations,
            now
        )

        assertEquals(
            WebStrategyKind.SEARCH_ENGLISH_TECHNICAL,
            ranking.best()!!.candidate.kind
        )
    }

    @Test
    fun oneObservationNeverPretendsToBeWellCalibrated() {
        val candidate = WebStrategyCandidate(
            id = "read:new.example",
            kind = WebStrategyKind.READ_DIRECT,
            tool = "web.read",
            host = "new.example",
            guidance = "read"
        )
        val score = JevLikeWebCalibrationRanker.rank(
            listOf(candidate),
            listOf(
                WebStrategyObservation(
                    id = "one",
                    kind = WebStrategyKind.READ_DIRECT,
                    tool = "web.read",
                    host = "new.example",
                    ok = true,
                    relevance = 1.0,
                    elapsedMs = 200,
                    at = now
                )
            ),
            now
        ).best()!!

        assertFalse(score.calibrated)
        assertTrue(score.calibrationConfidence < 0.20)
        assertEquals(1, score.evidenceCount)
    }

    @Test
    fun olderEvidenceDecaysRelativeToRecentEvidence() {
        val candidate = WebStrategyCandidate(
            id = "read:changing.example",
            kind = WebStrategyKind.READ_DIRECT,
            tool = "web.read",
            host = "changing.example",
            guidance = "read"
        )
        val oldSuccess = (1..10).map { index ->
            WebStrategyObservation(
                id = "old-$index",
                kind = WebStrategyKind.READ_DIRECT,
                tool = "web.read",
                host = "changing.example",
                ok = true,
                relevance = 0.8,
                elapsedMs = 700,
                at = now - 120L * 86_400_000L - index
            )
        }
        val recentFailures = (1..5).map { index ->
            WebStrategyObservation(
                id = "new-$index",
                kind = WebStrategyKind.READ_DIRECT,
                tool = "web.read",
                host = "changing.example",
                ok = false,
                elapsedMs = 600,
                failureClass = "DEPENDENCY_EXHAUSTED",
                at = now - index * 1_000L
            )
        }

        val score = JevLikeWebCalibrationRanker.rank(
            listOf(candidate),
            oldSuccess + recentFailures,
            now
        ).best()!!

        assertTrue(score.accessProbability < 0.5)
    }
}
