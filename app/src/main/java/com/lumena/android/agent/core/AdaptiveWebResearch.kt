package com.lumena.android.agent.core

import java.net.URI
import kotlin.math.pow
import kotlin.math.sqrt

enum class WebQueryClass {
    ENGLISH,
    CYRILLIC,
    LATIN_OTHER,
    MIXED,
    UNKNOWN
}

enum class WebStrategyKind {
    SEARCH_CURRENT_LANGUAGE,
    SEARCH_ENGLISH_TECHNICAL,
    READ_DIRECT,
    HTTP_GET,
    HTTP_JSON
}

data class WebStrategyCandidate(
    val id: String,
    val kind: WebStrategyKind,
    val tool: String,
    val host: String? = null,
    val queryClass: WebQueryClass = WebQueryClass.UNKNOWN,
    val guidance: String
)

data class WebStrategyObservation(
    val id: String,
    val kind: WebStrategyKind,
    val tool: String,
    val host: String? = null,
    val queryClass: WebQueryClass = WebQueryClass.UNKNOWN,
    val ok: Boolean,
    val relevance: Double? = null,
    val elapsedMs: Long,
    val failureClass: String? = null,
    val dependency: String? = null,
    val calibrationEligible: Boolean = true,
    val at: Long
) {
    init {
        require(relevance == null || relevance in 0.0..1.0)
        require(elapsedMs >= 0)
        require(at > 0)
    }
}

data class WebStrategyScore(
    val candidate: WebStrategyCandidate,
    val accessProbability: Double,
    val relevanceProbability: Double,
    val latencyScore: Double,
    val utility: Double,
    val calibrationConfidence: Double,
    val evidenceCount: Int,
    val effectiveEvidence: Double,
    val calibrated: Boolean
) {
    init {
        require(accessProbability in 0.0..1.0)
        require(relevanceProbability in 0.0..1.0)
        require(latencyScore in 0.0..1.0)
        require(utility in 0.0..1.0)
        require(calibrationConfidence in 0.0..1.0)
        require(evidenceCount >= 0)
        require(effectiveEvidence >= 0.0)
    }
}

data class WebStrategyRanking(
    val scores: List<WebStrategyScore>
) {
    fun best(): WebStrategyScore? = scores.firstOrNull()
}

/**
 * Constitution-preserving candidate factory.
 *
 * It can only emit registered READ_ONLY routes. It cannot call a tool, grant a
 * permission, enlarge budgets, bypass confirmation, or create authority.
 */
object AdaptiveWebStrategyFactory {
    private val webTools = setOf(
        "web.search",
        "web.read",
        "http.get",
        "http.json"
    )

    fun candidates(
        goal: String,
        sourceUrls: List<String> = emptyList()
    ): List<WebStrategyCandidate> {
        if (TaskIntentRouter.route(goal).intent != TaskIntent.PUBLIC_WEB) {
            return emptyList()
        }

        val out = linkedMapOf<String, WebStrategyCandidate>()
        val queryClass = classifyQuery(goal)

        addIfAdmitted(
            out,
            WebStrategyCandidate(
                id = "search:current:${queryClass.name}",
                kind = WebStrategyKind.SEARCH_CURRENT_LANGUAGE,
                tool = "web.search",
                queryClass = queryClass,
                guidance = "Search with a concise query preserving exact entities, versions and technical terms."
            )
        )

        if (queryClass != WebQueryClass.ENGLISH) {
            addIfAdmitted(
                out,
                WebStrategyCandidate(
                    id = "search:english-technical",
                    kind = WebStrategyKind.SEARCH_ENGLISH_TECHNICAL,
                    tool = "web.search",
                    queryClass = WebQueryClass.ENGLISH,
                    guidance = "Try a concise technical-English query while preserving names, versions and constraints."
                )
            )
        }

        sourceUrls
            .asSequence()
            .mapNotNull(::normalizedHost)
            .distinct()
            .take(6)
            .forEach { host ->
                addIfAdmitted(
                    out,
                    WebStrategyCandidate(
                        id = "read:$host",
                        kind = WebStrategyKind.READ_DIRECT,
                        tool = "web.read",
                        host = host,
                        guidance = "Read a relevant public page from $host directly."
                    )
                )
                addIfAdmitted(
                    out,
                    WebStrategyCandidate(
                        id = "http-get:$host",
                        kind = WebStrategyKind.HTTP_GET,
                        tool = "http.get",
                        host = host,
                        guidance = "Use bounded public HTTPS text fetch for $host if direct page extraction is unsuitable."
                    )
                )
            }

        sourceUrls
            .asSequence()
            .filter(::looksLikeJsonApi)
            .mapNotNull(::normalizedHost)
            .distinct()
            .take(4)
            .forEach { host ->
                addIfAdmitted(
                    out,
                    WebStrategyCandidate(
                        id = "http-json:$host",
                        kind = WebStrategyKind.HTTP_JSON,
                        tool = "http.json",
                        host = host,
                        guidance = "Use the public JSON/API representation on $host when the discovered URL indicates one."
                    )
                )
            }

        return out.values.toList()
    }

    fun classifyQuery(text: String): WebQueryClass {
        if (text.isBlank()) return WebQueryClass.UNKNOWN

        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return WebQueryClass.UNKNOWN

        val cyrillic = letters.count { ch ->
            ch.code in 0x0400..0x052F
        }
        val latin = letters.count { ch ->
            ch.code in 0x0041..0x024F
        }

        if (cyrillic > 0 && latin > 0) return WebQueryClass.MIXED
        if (cyrillic > 0) return WebQueryClass.CYRILLIC

        if (latin > 0) {
            val lower = " " + text.lowercase() + " "
            val englishSignals = listOf(
                " the ", " and ", " latest ", " official ", " documentation ",
                " docs ", " release ", " benchmark ", " programming ",
                " library ", " github ", " api ", " issue ", " how "
            ).count(lower::contains)
            return if (englishSignals > 0) {
                WebQueryClass.ENGLISH
            } else {
                WebQueryClass.LATIN_OTHER
            }
        }

        return WebQueryClass.UNKNOWN
    }

    fun normalizedHost(url: String): String? = runCatching {
        URI(url.trim()).host
            ?.lowercase()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun looksLikeJsonApi(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/api/") ||
            lower.endsWith(".json") ||
            "format=json" in lower ||
            "output=json" in lower
    }

    private fun addIfAdmitted(
        out: MutableMap<String, WebStrategyCandidate>,
        candidate: WebStrategyCandidate
    ) {
        val canonical = ToolRegistry.canonicalize(candidate.tool)
        val spec = ToolRegistry.get(canonical) ?: return
        if (canonical !in webTools || spec.risk != ToolRisk.READ_ONLY) return

        val admitted = candidate.copy(tool = canonical)
        out.putIfAbsent(admitted.id, admitted)
    }
}

/**
 * JEV-inspired fast ranking layer.
 *
 * Scores are Bayesian-shrunk estimates over verified local TOOL_RESULT
 * outcomes. "calibrated" here means there is enough effective local support for
 * the posterior to be used as a calibrated routing hint. It is never authority.
 */
object JevLikeWebCalibrationRanker {
    const val HALF_LIFE_DAYS = 30.0
    const val CALIBRATION_SUPPORT = 8.0

    /**
     * Bounded optimism for under-explored routes.
     *
     * The posterior means below remain the calibrated estimates shown to the
     * user. These Z values affect selection utility only. They are analogous to
     * a small UCB/PUCT-style exploration pressure inside the already-admitted
     * READ_ONLY strategy set.
     */
    const val ACCESS_UCB_Z = 1.50
    const val RELEVANCE_UCB_Z = 1.00

    fun rank(
        candidates: List<WebStrategyCandidate>,
        observations: List<WebStrategyObservation>,
        now: Long
    ): WebStrategyRanking {
        require(now > 0)

        val scores = candidates.map { candidate ->
            score(candidate, observations, now)
        }.sortedWith(
            compareByDescending<WebStrategyScore> { it.utility }
                .thenByDescending { it.calibrationConfidence }
                .thenBy { it.candidate.id }
        )
        return WebStrategyRanking(scores)
    }

    private fun score(
        candidate: WebStrategyCandidate,
        observations: List<WebStrategyObservation>,
        now: Long
    ): WebStrategyScore {
        data class Weighted(
            val observation: WebStrategyObservation,
            val weight: Double
        )

        val weighted = observations
            .asSequence()
            .filter(WebStrategyObservation::calibrationEligible)
            .mapNotNull { observation ->
                val similarity = similarity(candidate, observation)
                if (similarity <= 0.0) return@mapNotNull null

                val ageDays =
                    ((now - observation.at).coerceAtLeast(0L).toDouble() /
                        86_400_000.0)
                val decay = 0.5.pow(ageDays / HALF_LIFE_DAYS)
                val weight = similarity * decay
                if (weight < 0.01) null else Weighted(observation, weight)
            }
            .toList()

        var accessAlpha = 1.0
        var accessBeta = 1.0
        var relevanceAlpha = 1.0
        var relevanceBeta = 1.0
        var relevanceWeight = 0.0
        var latencyWeighted = 0.0
        var latencyWeight = 0.0

        weighted.forEach { item ->
            val observation = item.observation
            val weight = item.weight
            if (observation.ok) accessAlpha += weight else accessBeta += weight

            observation.relevance?.let { relevance ->
                relevanceAlpha += weight * relevance
                relevanceBeta += weight * (1.0 - relevance)
                relevanceWeight += weight
            }

            if (observation.ok) {
                latencyWeighted += observation.elapsedMs.toDouble() * weight
                latencyWeight += weight
            }
        }

        val accessProbability =
            accessAlpha / (accessAlpha + accessBeta)
        val relevanceProbability =
            relevanceAlpha / (relevanceAlpha + relevanceBeta)

        val averageLatencyMs =
            if (latencyWeight > 0.0) latencyWeighted / latencyWeight else null
        val latencyScore = averageLatencyMs
            ?.let { 1.0 / (1.0 + it / 4_000.0) }
            ?.coerceIn(0.0, 1.0)
            ?: 0.50

        val effectiveEvidence = weighted.sumOf { it.weight }
        val calibrationConfidence =
            (effectiveEvidence / (effectiveEvidence + CALIBRATION_SUPPORT))
                .coerceIn(0.0, 1.0)

        val evidenceCount = weighted.size

        // Posterior means above are the calibrated estimates. Selection uses
        // a bounded upper-confidence value so an under-explored admitted route
        // can still be sampled instead of being permanently starved by an
        // early winner. This is ranking only: it creates no execution authority.
        val accessStdDev = sqrt(
            (
                accessProbability *
                    (1.0 - accessProbability) /
                    (effectiveEvidence + 3.0)
                ).coerceAtLeast(0.0)
        )
        val relevanceStdDev = sqrt(
            (
                relevanceProbability *
                    (1.0 - relevanceProbability) /
                    (relevanceWeight + 3.0)
                ).coerceAtLeast(0.0)
        )
        val selectionAccess =
            (
                accessProbability +
                    ACCESS_UCB_Z * accessStdDev
                ).coerceIn(0.0, 1.0)
        val selectionRelevance =
            (
                relevanceProbability +
                    RELEVANCE_UCB_Z * relevanceStdDev
                ).coerceIn(0.0, 1.0)

        // Unknown relevance still shrinks to 0.5 in the posterior. Latency is a
        // smaller preference; access + relevance dominate the routing score.
        val utility =
            (
                selectionAccess *
                    (0.55 + 0.45 * selectionRelevance) *
                    (0.85 + 0.15 * latencyScore)
                ).coerceIn(0.0, 1.0)

        return WebStrategyScore(
            candidate = candidate,
            accessProbability = accessProbability.coerceIn(0.0, 1.0),
            relevanceProbability = relevanceProbability.coerceIn(0.0, 1.0),
            latencyScore = latencyScore,
            utility = utility,
            calibrationConfidence = calibrationConfidence,
            evidenceCount = evidenceCount,
            effectiveEvidence = effectiveEvidence,
            calibrated =
                effectiveEvidence >= CALIBRATION_SUPPORT &&
                    calibrationConfidence >= 0.50
        )
    }

    private fun similarity(
        candidate: WebStrategyCandidate,
        observation: WebStrategyObservation
    ): Double {
        if (ToolRegistry.canonicalize(candidate.tool) !=
            ToolRegistry.canonicalize(observation.tool)
        ) {
            return 0.0
        }

        return when (candidate.kind) {
            WebStrategyKind.SEARCH_CURRENT_LANGUAGE -> {
                when {
                    observation.kind !in setOf(
                        WebStrategyKind.SEARCH_CURRENT_LANGUAGE,
                        WebStrategyKind.SEARCH_ENGLISH_TECHNICAL
                    ) -> 0.0
                    observation.queryClass == candidate.queryClass -> 1.0
                    else -> 0.20
                }
            }

            WebStrategyKind.SEARCH_ENGLISH_TECHNICAL -> {
                when {
                    observation.kind !in setOf(
                        WebStrategyKind.SEARCH_CURRENT_LANGUAGE,
                        WebStrategyKind.SEARCH_ENGLISH_TECHNICAL
                    ) -> 0.0
                    observation.queryClass == WebQueryClass.ENGLISH -> 1.0
                    else -> 0.15
                }
            }

            WebStrategyKind.READ_DIRECT,
            WebStrategyKind.HTTP_GET,
            WebStrategyKind.HTTP_JSON -> {
                if (observation.kind != candidate.kind) {
                    0.0
                } else if (
                    candidate.host != null &&
                    observation.host == candidate.host
                ) {
                    1.0
                } else {
                    0.15
                }
            }
        }
    }
}
