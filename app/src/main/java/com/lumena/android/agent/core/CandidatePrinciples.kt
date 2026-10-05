package com.lumena.android.agent.core

/**
 * Candidate additions to Core DNA, measured before they become law.
 *
 * Core DNA is shown in every task and is never governed, so adding a rule
 * there changes behavior without evidence. These candidates instead run as
 * the governed PRINCIPLES layer: a stable 20% of tasks run without them and
 * the Layer Governor compares success and token cost. A candidate moves into
 * Core DNA only after a KEEP verdict on the owner's model.
 *
 * They are guidance only. They grant no permission and replace no gate.
 */
object CandidatePrinciples {
    const val VERSION = "lumena-candidate-principles-v1"

    data class Candidate(val id: String, val gap: String, val instruction: String)

    val candidates = listOf(
        Candidate(
            id = "C",
            gap = "Guessing on ambiguous turns (the bare 'реалізуй' incident)",
            instruction = "If the request is ambiguous or refers to a plan you cannot see, ask one short question instead of guessing; do not ask when the next step is clear."
        ),
        Candidate(
            id = "Min",
            gap = "Over-editing and irreversible steps",
            instruction = "Make the smallest change that reaches the goal, keep the user's existing work, prefer reversible steps."
        ),
        Candidate(
            id = "F",
            gap = "Acting on stale state",
            instruction = "Re-check state that may have changed before acting on it; earlier results are history, not current proof."
        ),
        Candidate(
            id = "K",
            gap = "Overconfidence and agreeing with a false premise",
            instruction = "Say how sure you are and what would change your answer; correct a false premise politely instead of agreeing."
        ),
        Candidate(
            id = "Z",
            gap = "Treating silence as a result",
            instruction = "Missing output or a missing record proves neither success nor failure; a new fact closes an old claim without erasing it."
        )
    )

    /** One line, so it fits the constitution section without displacing rules. */
    fun promptLine(): String =
        "CANDIDATE PRINCIPLES $VERSION (experimental guidance under measurement, not permission): " +
            candidates.joinToString(" ") { "${it.id}: ${it.instruction}" }
}
