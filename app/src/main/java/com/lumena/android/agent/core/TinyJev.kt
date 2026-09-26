package com.lumena.android.agent.core

import java.util.Properties
import kotlin.math.exp
import kotlin.math.ln

/**
 * TinyJev is Lumena's local System-1-style bounded decision scorer.
 *
 * It is NOT the proprietary TypeSafe Jev model. It is a small, APK-local,
 * auditable linear scorer that ranks only candidates supplied by Lumena.
 * It has no executor, no permission API and no authority over ToolGate or
 * ConstitutionKernel.
 */
data class TinyJevCandidate(
    val id: String,
    val description: String,
    val tags: Set<String> = emptySet()
) {
    init {
        require(id.isNotBlank())
        require(description.isNotBlank())
    }
}

data class TinyJevRequest(
    val state: String,
    val candidates: List<TinyJevCandidate>
) {
    init {
        require(candidates.isNotEmpty())
        require(candidates.map { it.id }.distinct().size == candidates.size)
    }
}

data class TinyJevCandidateScore(
    val id: String,
    val logit: Double,
    val probability: Double
) {
    init {
        require(probability in 0.0..1.0)
    }
}

data class TinyJevDecision(
    val scores: List<TinyJevCandidateScore>,
    val confidence: Double,
    val normalizedCertainty: Double,
    val modelVersion: String,
    val calibrated: Boolean = false
) {
    init {
        require(scores.isNotEmpty())
        require(confidence in 0.0..1.0)
        require(normalizedCertainty in 0.0..1.0)
    }

    fun best(): TinyJevCandidateScore =
        scores.maxWith(
            compareBy<TinyJevCandidateScore> { it.probability }
                .thenByDescending { it.id }
        )
}

data class TinyJevWeights(
    val version: String,
    val temperature: Double,
    val values: Map<String, Double>
) {
    init {
        require(version.isNotBlank())
        require(temperature > 0.0)
        require(values.isNotEmpty())
    }

    fun weight(name: String): Double = values[name] ?: 0.0

    companion object {
        fun embeddedFallback(): TinyJevWeights = TinyJevWeights(
            version = "tinyjev-linear-v1-fallback",
            temperature = 0.78,
            values = mapOf(
                "bias" to 0.0,
                "lexical_overlap" to 1.20,
                "exact_id" to 1.60,
                "conservative" to 0.10,
                "retryable_retry" to 1.90,
                "transient_retry" to 2.20,
                "invalid_retry" to 1.40,
                "exhausted_alternative" to 2.20,
                "state_drift_alternative" to 1.50,
                "challenge_alternative" to 1.80,
                "unknown_effect_stop" to 3.20,
                "policy_stop" to 3.00,
                "resource_planner" to 2.00,
                "context_planner" to 1.90,
                "model_runtime_planner" to 1.80,
                "auth_planner" to 1.60,
                "high_attempt_stop" to 1.20,
                "partial_partial" to 0.80
            )
        )

        fun parseProperties(text: String): TinyJevWeights {
            val properties = Properties()
            properties.load(text.reader())

            val version = properties.getProperty("model.version")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: error("TinyJev model.version is required")
            val temperature = properties.getProperty("model.temperature")
                ?.trim()
                ?.toDoubleOrNull()
                ?: error("TinyJev model.temperature is required")

            val values = properties.stringPropertyNames()
                .filter { it.startsWith("w.") }
                .associate { key ->
                    val value = properties.getProperty(key)
                        ?.trim()
                        ?.toDoubleOrNull()
                        ?: error("Invalid TinyJev weight: " + key)
                    key.removePrefix("w.") to value
                }

            require(values.isNotEmpty()) { "TinyJev model has no weights" }
            return TinyJevWeights(
                version = version,
                temperature = temperature,
                values = values
            )
        }
    }
}

class TinyJevModel(
    private val weights: TinyJevWeights = TinyJevWeights.embeddedFallback()
) {
    fun rank(request: TinyJevRequest): TinyJevDecision {
        val state = normalize(request.state)
        val stateTokens = tokenize(state)

        val logits = request.candidates.map { candidate ->
            candidate to scoreCandidate(
                state = state,
                stateTokens = stateTokens,
                candidate = candidate
            )
        }

        val maxLogit = logits.maxOf { it.second }
        val expValues = logits.map { (_, logit) ->
            exp((logit - maxLogit) / weights.temperature)
        }
        val denominator = expValues.sum().takeIf { it > 0.0 } ?: 1.0

        val scores = logits.mapIndexed { index, (candidate, logit) ->
            TinyJevCandidateScore(
                id = candidate.id,
                logit = logit,
                probability = (expValues[index] / denominator).coerceIn(0.0, 1.0)
            )
        }

        val top = scores.maxOf { it.probability }
        val entropy = scores.sumOf { score ->
            val p = score.probability.coerceAtLeast(1e-12)
            -p * ln(p)
        }
        val maxEntropy = if (scores.size <= 1) 0.0 else ln(scores.size.toDouble())
        val certainty = if (maxEntropy <= 0.0) {
            1.0
        } else {
            (1.0 - entropy / maxEntropy).coerceIn(0.0, 1.0)
        }

        return TinyJevDecision(
            scores = scores.sortedByDescending { it.probability },
            confidence = top,
            normalizedCertainty = certainty,
            modelVersion = weights.version,
            calibrated = false
        )
    }

    private fun scoreCandidate(
        state: String,
        stateTokens: Set<String>,
        candidate: TinyJevCandidate
    ): Double {
        val candidateText = normalize(
            candidate.id + " " +
                candidate.description + " " +
                candidate.tags.joinToString(" ")
        )
        val candidateTokens = tokenize(candidateText)
        val overlap = if (candidateTokens.isEmpty()) {
            0.0
        } else {
            candidateTokens.count { it in stateTokens }.toDouble() /
                candidateTokens.size.toDouble()
        }

        val retry = "retry" in candidate.tags
        val alternative = "alternative" in candidate.tags
        val planner = "planner" in candidate.tags
        val partial = "partial" in candidate.tags
        val stop = "stop" in candidate.tags
        val conservative = "conservative" in candidate.tags

        val retryable = "retryable=true" in state
        val transient = containsAny(
            state,
            "transient_transport",
            "timeout",
            "rate_limit"
        )
        val invalid = "invalid_input" in state
        val exhausted = "dependency_exhausted" in state
        val drift = "state_drift" in state
        val challenge = "provider_challenge" in state
        val unknownEffect =
            "unknown_effect" in state ||
                "outcomeunknown=true" in state
        val policyDenied = "policy_denied" in state
        val resource = "resource_pressure" in state
        val context = "context_pressure" in state
        val runtime = "model_runtime" in state
        val auth = "auth_or_config" in state
        val attempt = extractAttempt(state)

        val features = mapOf(
            "bias" to 1.0,
            "lexical_overlap" to overlap,
            "exact_id" to if (state.contains(normalize(candidate.id))) 1.0 else 0.0,
            "conservative" to if (conservative) 1.0 else 0.0,
            "retryable_retry" to if (retryable && retry) 1.0 else 0.0,
            "transient_retry" to if (transient && retry) 1.0 else 0.0,
            "invalid_retry" to if (invalid && retry) 1.0 else 0.0,
            "exhausted_alternative" to if (exhausted && alternative) 1.0 else 0.0,
            "state_drift_alternative" to if (drift && alternative) 1.0 else 0.0,
            "challenge_alternative" to if (challenge && alternative) 1.0 else 0.0,
            "unknown_effect_stop" to if (unknownEffect && stop) 1.0 else 0.0,
            "policy_stop" to if (policyDenied && stop) 1.0 else 0.0,
            "resource_planner" to if (resource && planner) 1.0 else 0.0,
            "context_planner" to if (context && planner) 1.0 else 0.0,
            "model_runtime_planner" to if (runtime && planner) 1.0 else 0.0,
            "auth_planner" to if (auth && planner) 1.0 else 0.0,
            "high_attempt_stop" to if (attempt >= 3 && stop) 1.0 else 0.0,
            "partial_partial" to if (partial && attempt >= 2) 1.0 else 0.0
        )

        return features.entries.sumOf { (name, value) ->
            weights.weight(name) * value
        }
    }

    private fun extractAttempt(state: String): Int =
        Regex("""attempt\s*=\s*(\d+)""")
            .find(state)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

    private fun containsAny(value: String, vararg needles: String): Boolean =
        needles.any(value::contains)

    private fun normalize(value: String): String =
        value
            .lowercase()
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun tokenize(value: String): Set<String> =
        value
            .lowercase()
            .split(Regex("""[^\p{L}\p{N}._-]+"""))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
}

/**
 * Adapter from Lumena's failure/recovery domain to TinyJev.
 *
 * The caller MUST pass only options already supported by verified local
 * experience and already admitted by ReflexKernel/ConstitutionKernel.
 */
object TinyJevReflexAdapter {
    fun rank(
        model: TinyJevModel,
        goal: String,
        event: FailureEvent,
        supportedOptions: Set<ReflexOption>
    ): TinyJevDecision? {
        if (supportedOptions.isEmpty()) return null

        val state = buildString {
            append("goal=").append(goal.take(1_500)).append('\n')
            append("source=").append(event.source.name).append('\n')
            append("failureClass=").append(event.failureClass.name).append('\n')
            append("retryable=").append(event.retryable).append('\n')
            append("outcomeUnknown=").append(event.outcomeUnknown).append('\n')
            append("attempt=").append(event.attempt).append('\n')
            event.actionFamily
                ?.takeIf { it.isNotBlank() }
                ?.let { append("actionFamily=").append(it).append('\n') }
            append("evidence=").append(event.evidence.take(1_000))
        }

        val candidates = supportedOptions
            .sortedBy { it.ordinal }
            .map(::candidateFor)

        return model.rank(
            TinyJevRequest(
                state = state,
                candidates = candidates
            )
        )
    }

    fun bestOption(decision: TinyJevDecision?): ReflexOption? =
        decision
            ?.best()
            ?.id
            ?.let { raw ->
                runCatching { ReflexOption.valueOf(raw) }.getOrNull()
            }

    private fun candidateFor(option: ReflexOption): TinyJevCandidate = when (option) {
        ReflexOption.RETRY_VARIANT -> TinyJevCandidate(
            id = option.name,
            description = "retry the same action using a corrected or delayed variant",
            tags = setOf("retry")
        )
        ReflexOption.TRY_ALTERNATIVE -> TinyJevCandidate(
            id = option.name,
            description = "switch provider, dependency, route or tool family",
            tags = setOf("alternative")
        )
        ReflexOption.ASK_PLANNER -> TinyJevCandidate(
            id = option.name,
            description = "escalate to the deliberative planner for a new plan",
            tags = setOf("planner", "conservative")
        )
        ReflexOption.DEGRADE_PARTIAL -> TinyJevCandidate(
            id = option.name,
            description = "return the verified partial result without risky continuation",
            tags = setOf("partial", "conservative")
        )
        ReflexOption.STOP -> TinyJevCandidate(
            id = option.name,
            description = "stop because the outcome is unknown, denied or unsafe to continue",
            tags = setOf("stop", "conservative")
        )
    }
}
