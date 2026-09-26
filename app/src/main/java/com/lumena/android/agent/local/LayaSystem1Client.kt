package com.lumena.android.agent.local

import com.lumena.android.agent.core.FailureEvent
import com.lumena.android.agent.core.ReflexCandidateSet
import com.lumena.android.agent.core.ReflexOption
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.math.abs

data class LayaSystem1Decision(
    val option: ReflexOption,
    val probabilities: Map<ReflexOption, Double>,
    val confidence: Double,
    val latencyMs: Long,
    val model: String
) {
    init {
        require(option in probabilities)
        require(probabilities.isNotEmpty())
        require(probabilities.values.all { it.isFinite() && it in 0.0..1.0 })
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(latencyMs >= 0)
        require(model.isNotBlank())
    }
}

data class LayaSystem1Result(
    val ok: Boolean,
    val decision: LayaSystem1Decision? = null,
    val latencyMs: Long,
    val error: String? = null,
    val errorCode: String? = null
) {
    init {
        require(latencyMs >= 0)
        if (ok) require(decision != null)
    }
}

private data class LayaChoiceQuestion(
    val type: String = "choice",
    val instructions: String,
    val criteria: Map<String, String>
)

private data class LayaPredictRequest(
    val state: Map<String, String>,
    val questions: Map<String, LayaChoiceQuestion>
)

private data class LayaChoiceAnswer(
    val type: String? = null,
    val choice: String? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    val confidence: Double? = null
)

private data class LayaPredictResponse(
    val model: String? = null,
    val answers: Map<String, LayaChoiceAnswer> = emptyMap()
)

/**
 * Internal learned System-1 client.
 *
 * Laya is intentionally not exposed through ToolRegistry. The deliberative
 * model cannot call it as a tool and Laya cannot execute, approve, or construct
 * a Lumena tool request. It may only score an already-authorized bounded
 * ReflexCandidateSet.
 */
class LayaSystem1Client(
    private val bridge: ToolExecutor
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val requestAdapter =
        moshi.adapter(LayaPredictRequest::class.java)
    private val responseAdapter =
        moshi.adapter(LayaPredictResponse::class.java)

    suspend fun status(): ToolResult =
        bridge.execute(ToolRequest(tool = "laya.status"))

    suspend fun start(): ToolResult =
        bridge.execute(ToolRequest(tool = "laya.start"))

    suspend fun predictReflex(
        event: FailureEvent,
        candidates: ReflexCandidateSet
    ): LayaSystem1Result {
        val request = buildReflexRequest(event, candidates)
        val json = requestAdapter.toJson(request)
        val startedNs = System.nanoTime()
        val result = bridge.execute(
            ToolRequest(
                tool = "laya.predict",
                args = mapOf(
                    "request" to json,
                    "timeout" to "60"
                )
            )
        )
        val latencyMs =
            ((System.nanoTime() - startedNs) / 1_000_000L)
                .coerceAtLeast(0L)

        if (!result.ok) {
            return LayaSystem1Result(
                ok = false,
                latencyMs = latencyMs,
                error = result.error,
                errorCode = result.errorCode
            )
        }

        return runCatching {
            parseDecision(
                json = result.stdout,
                candidates = candidates,
                latencyMs = latencyMs
            )
        }.fold(
            onSuccess = { decision ->
                LayaSystem1Result(
                    ok = true,
                    decision = decision,
                    latencyMs = latencyMs
                )
            },
            onFailure = { failure ->
                LayaSystem1Result(
                    ok = false,
                    latencyMs = latencyMs,
                    error =
                        "Invalid Laya typed-decision response: " +
                            (failure.message
                                ?: failure::class.simpleName
                                ?: "unknown"),
                    errorCode = "LAYA_RESPONSE_INVALID"
                )
            }
        )
    }

    private fun buildReflexRequest(
        event: FailureEvent,
        candidates: ReflexCandidateSet
    ): LayaPredictRequest {
        val criteria = linkedMapOf<String, String>()
        candidates.allowed
            .sortedBy { it.ordinal }
            .forEach { option ->
                criteria[option.name] = optionDescription(option)
            }

        val state = linkedMapOf(
            "failure_source" to event.source.name,
            "failure_class" to event.failureClass.name,
            "retryable" to (event.retryable?.toString() ?: "unknown"),
            "effect_class" to event.effectClass.name,
            "dependency" to event.dependency.orEmpty().take(120),
            "action_family" to event.actionFamily.orEmpty().take(120),
            "attempt" to event.attempt.toString(),
            "outcome_unknown" to event.outcomeUnknown.toString(),
            "error_code" to event.code.orEmpty().take(120),
            "constitutional_anchor" to
                candidates.constitutionalAnchor.name,
            "constitutional_reason" to
                candidates.reason
                    .replace(Regex("[\\r\\n\\t]+"), " ")
                    .trim()
                    .take(500)
        )

        return LayaPredictRequest(
            state = state,
            questions = mapOf(
                "recovery" to LayaChoiceQuestion(
                    instructions =
                        "Choose the safest next recovery disposition " +
                            "supported by the observed state and the " +
                            "constitutional candidate set. " +
                            "This decision is advisory only and never " +
                            "authorizes a tool. Prefer conservative " +
                            "escalation over replay when uncertain.",
                    criteria = criteria
                )
            )
        )
    }

    private fun parseDecision(
        json: String,
        candidates: ReflexCandidateSet,
        latencyMs: Long
    ): LayaSystem1Decision {
        val response = requireNotNull(
            responseAdapter.fromJson(json)
        ) {
            "empty response"
        }
        val answer = requireNotNull(
            response.answers["recovery"]
        ) {
            "missing recovery answer"
        }

        require(answer.type == null || answer.type == "choice") {
            "recovery answer is not a choice"
        }

        val expectedNames =
            candidates.allowed.map { it.name }.toSet()
        require(answer.probabilities.keys == expectedNames) {
            "probability keys do not match constitutional candidates"
        }

        val probabilities = answer.probabilities.mapKeys { (raw, _) ->
            ReflexOption.valueOf(raw)
        }
        require(
            probabilities.values.all {
                it.isFinite() && it in 0.0..1.0
            }
        ) {
            "probabilities are not finite/bounded"
        }

        val sum = probabilities.values.sum()
        require(abs(sum - 1.0) <= 0.03) {
            "probabilities do not sum to one"
        }

        val choice = requireNotNull(answer.choice)
            .let(ReflexOption::valueOf)
        require(choice in candidates.allowed) {
            "choice is outside constitutional candidates"
        }

        val maxProbability =
            probabilities.values.maxOrNull() ?: 0.0
        require(
            probabilities.getValue(choice) >=
                maxProbability - 0.0001
        ) {
            "choice is not the highest-probability candidate"
        }

        val confidence = requireNotNull(answer.confidence) {
            "missing confidence"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "confidence is not finite/bounded"
        }

        return LayaSystem1Decision(
            option = choice,
            probabilities = probabilities,
            confidence = confidence,
            latencyMs = latencyMs,
            model = response.model
                ?.takeIf { it.isNotBlank() }
                ?: "laya-rl-agent"
        )
    }

    private fun optionDescription(
        option: ReflexOption
    ): String = when (option) {
        ReflexOption.RETRY_VARIANT ->
            "Retry the same bounded action family with a corrected " +
                "variant only when the failure is retryable and the " +
                "previous effect is known."
        ReflexOption.TRY_ALTERNATIVE ->
            "Use a different bounded action family or evidence source " +
                "that can advance the task without replaying the same failure."
        ReflexOption.ASK_PLANNER ->
            "Escalate to the deliberative planner for a new plan. " +
                "This does not execute or authorize a tool."
        ReflexOption.DEGRADE_PARTIAL ->
            "Stop active recovery and report only verified partial progress."
        ReflexOption.STOP ->
            "Stop because continuing is unsafe, unjustified, or blocked."
    }
}
