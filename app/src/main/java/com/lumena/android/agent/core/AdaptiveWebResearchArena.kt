package com.lumena.android.agent.core

import java.security.MessageDigest
import kotlin.math.abs

/**
 * Bounded synthetic arena for evaluating the adaptive web router.
 *
 * AlphaGo-inspired only in structure:
 * state -> admitted moves -> value/ranking -> outcome -> update.
 *
 * It never executes tools. Every candidate comes from
 * AdaptiveWebStrategyFactory, which admits only registered READ_ONLY web tools.
 */
enum class ResearchArenaFaultDimension {
    NONE,
    QUERY_FORMULATION,
    SEARCH_PROVIDER,
    SOURCE_ACCESS,
    SOURCE_RELEVANCE,
    LATENCY,
    LOCAL_CONFIGURATION,
    SOURCE_DRIFT
}

data class ResearchArenaRouteTruth(
    val accessProbability: Double,
    val relevanceOnSuccess: Double,
    val latencyMs: Long,
    val failureClass: String? = null,
    val calibrationEligible: Boolean = true,
    val faultDimension: ResearchArenaFaultDimension = ResearchArenaFaultDimension.NONE
) {
    init {
        require(accessProbability in 0.0..1.0)
        require(relevanceOnSuccess in 0.0..1.0)
        require(latencyMs >= 0)
    }

    fun expectedUtility(): Double {
        val latencyScore =
            (1.0 / (1.0 + latencyMs.toDouble() / 4_000.0))
                .coerceIn(0.0, 1.0)
        return (
            accessProbability *
                (0.55 + 0.45 * relevanceOnSuccess) *
                (0.85 + 0.15 * latencyScore)
            ).coerceIn(0.0, 1.0)
    }
}

data class ResearchArenaPhase(
    val startEpisode: Int,
    val routes: Map<String, ResearchArenaRouteTruth>
) {
    init {
        require(startEpisode >= 0)
        require(routes.isNotEmpty())
    }
}

data class ResearchArenaScenario(
    val name: String,
    val goal: String,
    val sourceUrls: List<String> = emptyList(),
    val episodes: Int,
    val budgetPerEpisode: Int = 3,
    val minUsefulRelevance: Double = 0.55,
    val phases: List<ResearchArenaPhase>
) {
    init {
        require(name.isNotBlank())
        require(goal.isNotBlank())
        require(episodes > 0)
        require(budgetPerEpisode in 1..8)
        require(minUsefulRelevance in 0.0..1.0)
        require(phases.isNotEmpty())
        require(phases.first().startEpisode == 0)
        require(phases.zipWithNext().all { (a, b) ->
            b.startEpisode > a.startEpisode
        })
        require(phases.last().startEpisode < episodes)
    }
}

data class ResearchArenaAttempt(
    val episode: Int,
    val phaseStartEpisode: Int,
    val candidateId: String,
    val predictedAccess: Double,
    val predictedUtility: Double,
    val oracleCandidateId: String,
    val actualOk: Boolean,
    val actualRelevance: Double?,
    val latencyMs: Long,
    val calibrationEligible: Boolean,
    val faultDimension: ResearchArenaFaultDimension
)

data class ResearchArenaPhaseRecovery(
    val phaseStartEpisode: Int,
    val oracleCandidateId: String,
    val recoveredAtEpisode: Int?,
    val episodesToRecovery: Int?
)

data class ResearchArenaReport(
    val scenarioName: String,
    val episodes: Int,
    val attempts: Int,
    val successWithinBudgetRate: Double,
    val oracleFirstChoiceRate: Double,
    val averageRegret: Double,
    val brierScore: Double,
    val expectedCalibrationError: Double,
    val averageCallsPerEpisode: Double,
    val wastedCalls: Int,
    val uncalibratedFirstChoiceRate: Double,
    val safetyInvariantViolations: Int,
    val faultCounts: Map<ResearchArenaFaultDimension, Int>,
    val phaseRecoveries: List<ResearchArenaPhaseRecovery>,
    val finalRanking: WebStrategyRanking
) {
    init {
        require(successWithinBudgetRate in 0.0..1.0)
        require(oracleFirstChoiceRate in 0.0..1.0)
        require(averageRegret >= 0.0)
        require(brierScore in 0.0..1.0)
        require(expectedCalibrationError in 0.0..1.0)
        require(averageCallsPerEpisode >= 0.0)
        require(wastedCalls >= 0)
        require(uncalibratedFirstChoiceRate in 0.0..1.0)
        require(safetyInvariantViolations >= 0)
    }
}

object BoundedWebResearchArena {
    private const val MAX_OBSERVATIONS = 2_048
    private const val RECOVERY_STREAK = 5
    private const val CALIBRATION_BINS = 10

    fun run(
        scenario: ResearchArenaScenario,
        startTimeMs: Long = 1_800_000_000_000L,
        episodeStepMs: Long = 60_000L
    ): ResearchArenaReport {
        require(startTimeMs > 0)
        require(episodeStepMs > 0)

        val candidates = AdaptiveWebStrategyFactory.candidates(
            goal = scenario.goal,
            sourceUrls = scenario.sourceUrls
        )
        require(candidates.isNotEmpty()) {
            "Arena scenario produced no constitution-admitted web candidates"
        }

        val observations = ArrayDeque<WebStrategyObservation>()
        val attempts = mutableListOf<ResearchArenaAttempt>()
        val firstChoices = mutableListOf<String>()
        val oracleChoices = mutableListOf<String>()
        val firstChoiceCalibrated = mutableListOf<Boolean>()
        val episodeSucceeded = BooleanArray(scenario.episodes)
        var safetyViolations = 0
        var wastedCalls = 0

        candidates.forEach { candidate ->
            val spec = ToolRegistry.get(candidate.tool)
            if (spec == null || spec.risk != ToolRisk.READ_ONLY) {
                safetyViolations += 1
            }
        }

        for (episode in 0 until scenario.episodes) {
            val now = startTimeMs + episode * episodeStepMs
            val phase = phaseFor(scenario.phases, episode)
            val truths = candidates.associateWith { candidate ->
                phase.routes[candidate.id] ?: neutralTruth(candidate)
            }
            val oracle = truths.maxWithOrNull(
                compareBy<Map.Entry<WebStrategyCandidate, ResearchArenaRouteTruth>> {
                    it.value.expectedUtility()
                }.thenBy {
                    it.key.id
                }
            )!!.key
            oracleChoices += oracle.id

            val rankingBefore = JevLikeWebCalibrationRanker.rank(
                candidates = candidates,
                observations = observations.toList(),
                now = now
            )
            val first = rankingBefore.best()
                ?: error("Arena ranker returned no candidate")
            firstChoices += first.candidate.id
            firstChoiceCalibrated += first.calibrated

            val tried = linkedSetOf<String>()
            var calls = 0
            var useful = false

            while (
                calls < scenario.budgetPerEpisode &&
                tried.size < candidates.size &&
                !useful
            ) {
                val ranking = JevLikeWebCalibrationRanker.rank(
                    candidates = candidates.filter { it.id !in tried },
                    observations = observations.toList(),
                    now = now + calls
                )
                val score = ranking.best() ?: break
                val candidate = score.candidate
                val truth = truths.getValue(candidate)
                tried += candidate.id
                calls += 1

                val ok = deterministicUnit(
                    scenario.name,
                    phase.startEpisode.toString(),
                    episode.toString(),
                    calls.toString(),
                    candidate.id,
                    "access"
                ) < truth.accessProbability

                val relevance = if (ok) {
                    deterministicRelevance(
                        truth = truth,
                        scenario = scenario,
                        phaseStart = phase.startEpisode,
                        episode = episode,
                        calls = calls,
                        candidateId = candidate.id
                    )
                } else {
                    null
                }

                val actualLatency = jitterLatency(
                    base = truth.latencyMs,
                    scenario = scenario,
                    phaseStart = phase.startEpisode,
                    episode = episode,
                    calls = calls,
                    candidateId = candidate.id
                )

                attempts += ResearchArenaAttempt(
                    episode = episode,
                    phaseStartEpisode = phase.startEpisode,
                    candidateId = candidate.id,
                    predictedAccess = score.accessProbability,
                    predictedUtility = score.utility,
                    oracleCandidateId = oracle.id,
                    actualOk = ok,
                    actualRelevance = relevance,
                    latencyMs = actualLatency,
                    calibrationEligible = truth.calibrationEligible,
                    faultDimension = if (
                        ok &&
                        (relevance ?: 0.0) >= scenario.minUsefulRelevance
                    ) {
                        ResearchArenaFaultDimension.NONE
                    } else {
                        truth.faultDimension
                    }
                )

                observations.addLast(
                    WebStrategyObservation(
                        id = "arena-${scenario.name}-$episode-$calls-${candidate.id}",
                        kind = candidate.kind,
                        tool = candidate.tool,
                        host = candidate.host,
                        queryClass = candidate.queryClass,
                        ok = ok,
                        relevance = relevance,
                        elapsedMs = actualLatency,
                        failureClass = if (ok) null else truth.failureClass,
                        dependency = null,
                        calibrationEligible = truth.calibrationEligible,
                        at = now + calls
                    )
                )
                while (observations.size > MAX_OBSERVATIONS) {
                    observations.removeFirst()
                }

                useful =
                    ok &&
                        (relevance ?: 0.0) >= scenario.minUsefulRelevance
            }

            episodeSucceeded[episode] = useful
            if (calls > 1) wastedCalls += calls - 1
            if (!useful) wastedCalls += 1
        }

        val eligibleAttempts = attempts.filter {
            it.calibrationEligible
        }

        val brier = if (eligibleAttempts.isEmpty()) {
            0.0
        } else {
            eligibleAttempts
                .map { attempt ->
                    val actual = if (attempt.actualOk) 1.0 else 0.0
                    val delta = attempt.predictedAccess - actual
                    delta * delta
                }
                .average()
                .coerceIn(0.0, 1.0)
        }

        val ece = expectedCalibrationError(eligibleAttempts)

        val regrets = firstChoices.indices.map { episode ->
            val phase = phaseFor(scenario.phases, episode)
            val firstId = firstChoices[episode]
            val oracleId = oracleChoices[episode]
            val firstCandidate = candidates.first { it.id == firstId }
            val oracleCandidate = candidates.first { it.id == oracleId }
            val firstTruth =
                phase.routes[firstId] ?: neutralTruth(firstCandidate)
            val oracleTruth =
                phase.routes[oracleId] ?: neutralTruth(oracleCandidate)
            (
                oracleTruth.expectedUtility() -
                    firstTruth.expectedUtility()
                ).coerceAtLeast(0.0)
        }

        val faultCounts = attempts
            .filter {
                !it.actualOk ||
                    (it.actualRelevance ?: 0.0) <
                        scenario.minUsefulRelevance
            }
            .groupingBy(ResearchArenaAttempt::faultDimension)
            .eachCount()
            .toSortedMap(compareBy { it.ordinal })

        val phaseRecoveries = recoveryMetrics(
            scenario = scenario,
            candidates = candidates,
            firstChoices = firstChoices
        )

        val finalNow =
            startTimeMs + scenario.episodes * episodeStepMs
        val finalRanking = JevLikeWebCalibrationRanker.rank(
            candidates = candidates,
            observations = observations.toList(),
            now = finalNow
        )

        val oracleMatches = firstChoices.indices.count {
            firstChoices[it] == oracleChoices[it]
        }

        return ResearchArenaReport(
            scenarioName = scenario.name,
            episodes = scenario.episodes,
            attempts = attempts.size,
            successWithinBudgetRate =
                episodeSucceeded.count { it }
                    .toDouble() / scenario.episodes.toDouble(),
            oracleFirstChoiceRate =
                oracleMatches.toDouble() / scenario.episodes.toDouble(),
            averageRegret =
                regrets.average().coerceAtLeast(0.0),
            brierScore = brier,
            expectedCalibrationError = ece,
            averageCallsPerEpisode =
                attempts.size.toDouble() / scenario.episodes.toDouble(),
            wastedCalls = wastedCalls,
            uncalibratedFirstChoiceRate =
                firstChoiceCalibrated.count { !it }
                    .toDouble() / scenario.episodes.toDouble(),
            safetyInvariantViolations = safetyViolations,
            faultCounts = faultCounts,
            phaseRecoveries = phaseRecoveries,
            finalRanking = finalRanking
        )
    }

    private fun recoveryMetrics(
        scenario: ResearchArenaScenario,
        candidates: List<WebStrategyCandidate>,
        firstChoices: List<String>
    ): List<ResearchArenaPhaseRecovery> =
        scenario.phases.drop(1).map { phase ->
            val truths = candidates.associateWith { candidate ->
                phase.routes[candidate.id] ?: neutralTruth(candidate)
            }
            val oracle = truths.maxWithOrNull(
                compareBy<Map.Entry<WebStrategyCandidate, ResearchArenaRouteTruth>> {
                    it.value.expectedUtility()
                }.thenBy {
                    it.key.id
                }
            )!!.key.id

            val nextPhaseStart = scenario.phases
                .firstOrNull { it.startEpisode > phase.startEpisode }
                ?.startEpisode
                ?: scenario.episodes

            var recoveredAt: Int? = null
            val lastStart =
                (nextPhaseStart - RECOVERY_STREAK)
                    .coerceAtLeast(phase.startEpisode)
            if (phase.startEpisode <= lastStart) {
                for (episode in phase.startEpisode..lastStart) {
                    val streak = (episode until episode + RECOVERY_STREAK)
                        .all { index ->
                            index < firstChoices.size &&
                                firstChoices[index] == oracle
                        }
                    if (streak) {
                        recoveredAt = episode
                        break
                    }
                }
            }

            ResearchArenaPhaseRecovery(
                phaseStartEpisode = phase.startEpisode,
                oracleCandidateId = oracle,
                recoveredAtEpisode = recoveredAt,
                episodesToRecovery = recoveredAt
                    ?.minus(phase.startEpisode)
            )
        }

    private fun expectedCalibrationError(
        attempts: List<ResearchArenaAttempt>
    ): Double {
        if (attempts.isEmpty()) return 0.0

        data class Bin(
            val predictions: MutableList<Double> = mutableListOf(),
            val outcomes: MutableList<Double> = mutableListOf()
        )

        val bins = List(CALIBRATION_BINS) { Bin() }
        attempts.forEach { attempt ->
            val index = (
                attempt.predictedAccess *
                    CALIBRATION_BINS
                ).toInt().coerceIn(0, CALIBRATION_BINS - 1)
            bins[index].predictions += attempt.predictedAccess
            bins[index].outcomes +=
                if (attempt.actualOk) 1.0 else 0.0
        }

        val total = attempts.size.toDouble()
        return bins.sumOf { bin ->
            if (bin.predictions.isEmpty()) {
                0.0
            } else {
                val confidence = bin.predictions.average()
                val accuracy = bin.outcomes.average()
                abs(confidence - accuracy) *
                    (bin.predictions.size.toDouble() / total)
            }
        }.coerceIn(0.0, 1.0)
    }

    private fun phaseFor(
        phases: List<ResearchArenaPhase>,
        episode: Int
    ): ResearchArenaPhase =
        phases.last { it.startEpisode <= episode }

    private fun neutralTruth(
        candidate: WebStrategyCandidate
    ): ResearchArenaRouteTruth =
        ResearchArenaRouteTruth(
            accessProbability = 0.50,
            relevanceOnSuccess = 0.50,
            latencyMs = when (candidate.kind) {
                WebStrategyKind.SEARCH_CURRENT_LANGUAGE,
                WebStrategyKind.SEARCH_ENGLISH_TECHNICAL -> 1_000
                WebStrategyKind.READ_DIRECT -> 900
                WebStrategyKind.HTTP_GET -> 850
                WebStrategyKind.HTTP_JSON -> 700
            }
        )

    private fun deterministicRelevance(
        truth: ResearchArenaRouteTruth,
        scenario: ResearchArenaScenario,
        phaseStart: Int,
        episode: Int,
        calls: Int,
        candidateId: String
    ): Double {
        val jitter =
            (
                deterministicUnit(
                    scenario.name,
                    phaseStart.toString(),
                    episode.toString(),
                    calls.toString(),
                    candidateId,
                    "relevance"
                ) - 0.5
                ) * 0.10
        return (truth.relevanceOnSuccess + jitter)
            .coerceIn(0.0, 1.0)
    }

    private fun jitterLatency(
        base: Long,
        scenario: ResearchArenaScenario,
        phaseStart: Int,
        episode: Int,
        calls: Int,
        candidateId: String
    ): Long {
        val unit = deterministicUnit(
            scenario.name,
            phaseStart.toString(),
            episode.toString(),
            calls.toString(),
            candidateId,
            "latency"
        )
        val factor = 0.85 + unit * 0.30
        return (base.toDouble() * factor)
            .toLong()
            .coerceAtLeast(0)
    }

    private fun deterministicUnit(
        vararg parts: String
    ): Double {
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(
                parts.joinToString("|")
                    .toByteArray(Charsets.UTF_8)
            )
        var value = 0L
        for (i in 0 until 7) {
            value =
                (value shl 8) or
                    (digest[i].toLong() and 0xffL)
        }
        return (
            value.toDouble() /
                0x00ffffffffffffffL.toDouble()
            ).coerceIn(0.0, 1.0)
    }
}
