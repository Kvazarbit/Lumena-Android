package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveWebResearchArenaTest {
    private val goal =
        "Знайди в інтернеті актуальну технічну документацію Android Vulkan API"

    private val source =
        "https://docs.example.org/android/vulkan"

    private val currentId = "search:current:MIXED"
    private val englishId = "search:english-technical"
    private val readId = "read:docs.example.org"
    private val httpGetId = "http-get:docs.example.org"

    @Test
    fun thousandEpisodeArenaLearnsAndRecoversAcrossTwoDrifts() {
        val scenario = ResearchArenaScenario(
            name = "technical-research-1000",
            goal = goal,
            sourceUrls = listOf(source),
            episodes = 1_000,
            budgetPerEpisode = 3,
            minUsefulRelevance = 0.55,
            phases = listOf(
                ResearchArenaPhase(
                    startEpisode = 0,
                    routes = mapOf(
                        currentId to ResearchArenaRouteTruth(
                            accessProbability = 0.58,
                            relevanceOnSuccess = 0.42,
                            latencyMs = 900,
                            failureClass = "IRRELEVANT_RESULTS",
                            faultDimension =
                                ResearchArenaFaultDimension.QUERY_FORMULATION
                        ),
                        englishId to ResearchArenaRouteTruth(
                            accessProbability = 0.92,
                            relevanceOnSuccess = 0.94,
                            latencyMs = 820,
                            failureClass = "SEARCH_EXHAUSTED",
                            faultDimension =
                                ResearchArenaFaultDimension.SEARCH_PROVIDER
                        ),
                        readId to ResearchArenaRouteTruth(
                            accessProbability = 0.82,
                            relevanceOnSuccess = 0.96,
                            latencyMs = 720,
                            failureClass = "DEPENDENCY_EXHAUSTED",
                            faultDimension =
                                ResearchArenaFaultDimension.SOURCE_ACCESS
                        ),
                        httpGetId to ResearchArenaRouteTruth(
                            accessProbability = 0.73,
                            relevanceOnSuccess = 0.88,
                            latencyMs = 620,
                            failureClass = "TRANSIENT_HTTP",
                            faultDimension =
                                ResearchArenaFaultDimension.SOURCE_ACCESS
                        )
                    )
                ),
                ResearchArenaPhase(
                    startEpisode = 400,
                    routes = mapOf(
                        currentId to ResearchArenaRouteTruth(
                            accessProbability = 0.62,
                            relevanceOnSuccess = 0.50,
                            latencyMs = 900,
                            failureClass = "IRRELEVANT_RESULTS",
                            faultDimension =
                                ResearchArenaFaultDimension.QUERY_FORMULATION
                        ),
                        englishId to ResearchArenaRouteTruth(
                            accessProbability = 0.36,
                            relevanceOnSuccess = 0.70,
                            latencyMs = 1_100,
                            failureClass = "SEARCH_EXHAUSTED",
                            faultDimension =
                                ResearchArenaFaultDimension.SOURCE_DRIFT
                        ),
                        readId to ResearchArenaRouteTruth(
                            accessProbability = 0.94,
                            relevanceOnSuccess = 0.97,
                            latencyMs = 690,
                            failureClass = "DEPENDENCY_EXHAUSTED",
                            faultDimension =
                                ResearchArenaFaultDimension.SOURCE_ACCESS
                        ),
                        httpGetId to ResearchArenaRouteTruth(
                            accessProbability = 0.79,
                            relevanceOnSuccess = 0.90,
                            latencyMs = 640,
                            failureClass = "TRANSIENT_HTTP",
                            faultDimension =
                                ResearchArenaFaultDimension.SOURCE_ACCESS
                        )
                    )
                ),
                ResearchArenaPhase(
                    startEpisode = 700,
                    routes = mapOf(
                        currentId to ResearchArenaRouteTruth(
                            accessProbability = 0.60,
                            relevanceOnSuccess = 0.52,
                            latencyMs = 920,
                            failureClass = "IRRELEVANT_RESULTS",
                            faultDimension =
                                ResearchArenaFaultDimension.QUERY_FORMULATION
                        ),
                        englishId to ResearchArenaRouteTruth(
                            accessProbability = 0.68,
                            relevanceOnSuccess = 0.84,
                            latencyMs = 980,
                            failureClass = "SEARCH_EXHAUSTED",
                            faultDimension =
                                ResearchArenaFaultDimension.SEARCH_PROVIDER
                        ),
                        readId to ResearchArenaRouteTruth(
                            accessProbability = 0.18,
                            relevanceOnSuccess = 0.96,
                            latencyMs = 520,
                            failureClass = "DEPENDENCY_EXHAUSTED",
                            faultDimension =
                                ResearchArenaFaultDimension.SOURCE_DRIFT
                        ),
                        httpGetId to ResearchArenaRouteTruth(
                            accessProbability = 0.93,
                            relevanceOnSuccess = 0.93,
                            latencyMs = 610,
                            failureClass = "TRANSIENT_HTTP",
                            faultDimension =
                                ResearchArenaFaultDimension.SOURCE_ACCESS
                        )
                    )
                )
            )
        )

        val report = BoundedWebResearchArena.run(
            scenario = scenario,
            episodeStepMs = 6L * 60L * 60L * 1_000L
        )

        assertEquals(1_000, report.episodes)
        assertEquals(0, report.safetyInvariantViolations)
        assertTrue(report.successWithinBudgetRate >= 0.90)
        assertTrue(report.brierScore <= 0.24)
        assertTrue(report.expectedCalibrationError <= 0.16)
        assertTrue(report.averageRegret <= 0.10)
        assertTrue(report.oracleFirstChoiceRate >= 0.60)
        assertTrue(report.averageCallsPerEpisode <= 1.55)

        assertEquals(2, report.phaseRecoveries.size)
        report.phaseRecoveries.forEach { recovery ->
            assertNotNull(
                "Expected bounded recovery after drift at episode " +
                    recovery.phaseStartEpisode,
                recovery.episodesToRecovery
            )
            assertTrue(
                "Drift recovery took too long: $recovery",
                (recovery.episodesToRecovery ?: Int.MAX_VALUE) <= 80
            )
        }

        assertEquals(
            httpGetId,
            report.finalRanking.best()?.candidate?.id
        )
        assertTrue(
            report.faultCounts.keys.any {
                it == ResearchArenaFaultDimension.QUERY_FORMULATION
            }
        )
        assertTrue(
            report.faultCounts.keys.any {
                it == ResearchArenaFaultDimension.SOURCE_DRIFT
            }
        )
    }

    @Test
    fun localConfigurationOutageDoesNotPoisonPublicRouteCalibration() {
        val scenario = ResearchArenaScenario(
            name = "local-auth-outage",
            goal = goal,
            sourceUrls = listOf(source),
            episodes = 360,
            budgetPerEpisode = 2,
            phases = listOf(
                ResearchArenaPhase(
                    startEpisode = 0,
                    routes = mapOf(
                        readId to ResearchArenaRouteTruth(
                            accessProbability = 0.0,
                            relevanceOnSuccess = 0.95,
                            latencyMs = 20,
                            failureClass = "AUTH_OR_CONFIG",
                            calibrationEligible = false,
                            faultDimension =
                                ResearchArenaFaultDimension.LOCAL_CONFIGURATION
                        ),
                        englishId to ResearchArenaRouteTruth(
                            accessProbability = 0.74,
                            relevanceOnSuccess = 0.84,
                            latencyMs = 850,
                            faultDimension =
                                ResearchArenaFaultDimension.SEARCH_PROVIDER
                        )
                    )
                ),
                ResearchArenaPhase(
                    startEpisode = 120,
                    routes = mapOf(
                        readId to ResearchArenaRouteTruth(
                            accessProbability = 0.95,
                            relevanceOnSuccess = 0.97,
                            latencyMs = 650,
                            faultDimension =
                                ResearchArenaFaultDimension.SOURCE_ACCESS
                        ),
                        englishId to ResearchArenaRouteTruth(
                            accessProbability = 0.74,
                            relevanceOnSuccess = 0.84,
                            latencyMs = 850,
                            faultDimension =
                                ResearchArenaFaultDimension.SEARCH_PROVIDER
                        )
                    )
                )
            )
        )

        val report = BoundedWebResearchArena.run(
            scenario = scenario,
            episodeStepMs = 12L * 60L * 60L * 1_000L
        )

        assertEquals(0, report.safetyInvariantViolations)
        assertTrue(
            (report.faultCounts[
                ResearchArenaFaultDimension.LOCAL_CONFIGURATION
            ] ?: 0) > 0
        )
        val recovery = report.phaseRecoveries.single()
        assertNotNull(recovery.episodesToRecovery)
        assertTrue(
            (recovery.episodesToRecovery ?: Int.MAX_VALUE) <= 45
        )
        assertEquals(
            readId,
            report.finalRanking.best()?.candidate?.id
        )
    }

    @Test
    fun arenaIsDeterministicForSameScenarioAndSeedTime() {
        val scenario = ResearchArenaScenario(
            name = "determinism",
            goal = goal,
            sourceUrls = listOf(source),
            episodes = 120,
            budgetPerEpisode = 2,
            phases = listOf(
                ResearchArenaPhase(
                    startEpisode = 0,
                    routes = mapOf(
                        englishId to ResearchArenaRouteTruth(
                            accessProbability = 0.85,
                            relevanceOnSuccess = 0.90,
                            latencyMs = 800
                        ),
                        readId to ResearchArenaRouteTruth(
                            accessProbability = 0.76,
                            relevanceOnSuccess = 0.95,
                            latencyMs = 650
                        )
                    )
                )
            )
        )

        val first = BoundedWebResearchArena.run(scenario)
        val second = BoundedWebResearchArena.run(scenario)

        assertEquals(first, second)
    }
}
