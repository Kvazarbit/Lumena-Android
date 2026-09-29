package com.lumena.android.agent.core

/**
 * A small, deterministic world-model contract. Implementations describe an
 * environment; they do not grant tool permissions and cannot mutate the
 * Constitution on their own.
 */
interface CognitiveEnvironment<S, A> {
    fun legalActions(state: S): List<A>
    fun predict(state: S, action: A): S?
    fun goalScore(state: S): Double

    /** Exact by default; environments may override for observational equality. */
    fun equivalent(expected: S, actual: S): Boolean = expected == actual

    /** Stable, coarse context identity used only for shadow-learning diversity. */
    fun contextKey(state: S): String = state.toString()
}

data class PredictedTrajectory<S, A>(
    val initial: S,
    val actions: List<A>,
    val states: List<S>,
    val score: Double,
    val complete: Boolean
) {
    val finalState: S get() = states.lastOrNull() ?: initial
}

object WorldModelKernel {
    fun <S, A> rollout(
        environment: CognitiveEnvironment<S, A>,
        initial: S,
        actions: List<A>
    ): PredictedTrajectory<S, A> {
        var state = initial
        val states = mutableListOf<S>()
        for ((index, action) in actions.withIndex()) {
            if (action !in environment.legalActions(state)) {
                return PredictedTrajectory(
                    initial = initial,
                    actions = actions.take(index),
                    states = states.toList(),
                    score = environment.goalScore(state),
                    complete = false
                )
            }
            val next = environment.predict(state, action)
                ?: return PredictedTrajectory(
                    initial = initial,
                    actions = actions.take(index),
                    states = states.toList(),
                    score = environment.goalScore(state),
                    complete = false
                )
            state = next
            states += next
        }
        return PredictedTrajectory(
            initial = initial,
            actions = actions,
            states = states.toList(),
            score = environment.goalScore(state),
            complete = true
        )
    }
}

data class DeliberationResult<S, A>(
    val best: PredictedTrajectory<S, A>?,
    val exploredNodes: Int,
    val horizon: Int,
    val budgetExhausted: Boolean
)

/**
 * Bounded breadth-first lookahead. Candidate generation and judging stay
 * deterministic; a language model can propose candidates without becoming the
 * verifier.
 */
object DeliberationKernel {
    private data class Node<S, A>(
        val state: S,
        val actions: List<A>,
        val states: List<S>
    )

    fun <S, A> search(
        environment: CognitiveEnvironment<S, A>,
        initial: S,
        horizon: Int,
        maxNodes: Int = 512
    ): DeliberationResult<S, A> {
        require(horizon >= 1) { "horizon must be >= 1" }
        require(maxNodes >= 1) { "maxNodes must be >= 1" }

        var explored = 0
        var budgetExhausted = false
        var frontier = listOf(Node<S, A>(initial, emptyList(), emptyList()))
        var best: PredictedTrajectory<S, A>? = null

        for (depth in 1..horizon) {
            val nextFrontier = mutableListOf<Node<S, A>>()
            for (node in frontier) {
                for (action in environment.legalActions(node.state)) {
                    if (explored >= maxNodes) {
                        budgetExhausted = true
                        break
                    }
                    explored += 1
                    val next = environment.predict(node.state, action) ?: continue
                    val actions = node.actions + action
                    val states = node.states + next
                    val candidate = PredictedTrajectory(
                        initial = initial,
                        actions = actions,
                        states = states,
                        score = environment.goalScore(next),
                        complete = depth == horizon
                    )
                    val current = best
                    if (current == null ||
                        candidate.score > current.score ||
                        (candidate.score == current.score && candidate.actions.size > current.actions.size)
                    ) {
                        best = candidate
                    }
                    if (depth < horizon) {
                        nextFrontier += Node(next, actions, states)
                    }
                }
                if (budgetExhausted) break
            }
            if (budgetExhausted || nextFrontier.isEmpty()) break
            frontier = nextFrontier
        }

        return DeliberationResult(
            best = best,
            exploredNodes = explored,
            horizon = horizon,
            budgetExhausted = budgetExhausted
        )
    }
}

data class TrajectoryVerification<S, A>(
    val accepted: Boolean,
    val expected: S,
    val actual: S,
    val actions: List<A>,
    val reason: String
)

object VerifierKernel {
    fun <S, A> verify(
        environment: CognitiveEnvironment<S, A>,
        prediction: PredictedTrajectory<S, A>,
        actual: S
    ): TrajectoryVerification<S, A> {
        val expected = prediction.finalState
        val accepted = prediction.complete && environment.equivalent(expected, actual)
        return TrajectoryVerification(
            accepted = accepted,
            expected = expected,
            actual = actual,
            actions = prediction.actions,
            reason = when {
                !prediction.complete -> "prediction_incomplete"
                accepted -> "verified_match"
                else -> "state_mismatch"
            }
        )
    }
}

data class VerifiedCognitiveExperience<S, A>(
    val before: S,
    val actions: List<A>,
    val after: S,
    val contextKey: String,
    val score: Double
)

data class ConstitutionShadowCandidate(
    val ruleId: String,
    val statement: String,
    val verifiedEvidenceCount: Int,
    val distinctContexts: Int,
    val active: Boolean = false,
    val source: String = "VERIFIED_TRAJECTORY"
)

/**
 * Shadow-only evidence buffer. Nothing here can promote or activate a
 * Constitution gene. It merely emits a candidate once evidence is sufficiently
 * repeated across distinct contexts.
 */
class CognitiveShadowLedger<S, A>(
    private val environment: CognitiveEnvironment<S, A>
) {
    private val accepted = mutableListOf<VerifiedCognitiveExperience<S, A>>()

    fun record(
        prediction: PredictedTrajectory<S, A>,
        verification: TrajectoryVerification<S, A>
    ): Boolean {
        if (!verification.accepted) return false
        accepted += VerifiedCognitiveExperience(
            before = prediction.initial,
            actions = prediction.actions,
            after = verification.actual,
            contextKey = environment.contextKey(prediction.initial),
            score = environment.goalScore(verification.actual)
        )
        return true
    }

    fun experiences(): List<VerifiedCognitiveExperience<S, A>> = accepted.toList()

    fun shadowCandidate(
        ruleId: String,
        statement: String,
        minEvidence: Int = 3,
        minDistinctContexts: Int = 2
    ): ConstitutionShadowCandidate? {
        require(minEvidence >= 1)
        require(minDistinctContexts >= 1)
        val distinct = accepted.map { it.contextKey }.toSet().size
        if (accepted.size < minEvidence || distinct < minDistinctContexts) return null
        return ConstitutionShadowCandidate(
            ruleId = ruleId,
            statement = statement,
            verifiedEvidenceCount = accepted.size,
            distinctContexts = distinct
        )
    }
}
