package com.lumena.android.ollama

/**
 * Commitment-aware context selection (experimental, not wired into runtime).
 *
 * OllamaContextPolicy.compact keeps the newest turns that fit the budget, so
 * a user prohibition stated early in a long session ("не чіпай config.json")
 * silently disappears from the model request. This variant spends at most a
 * small share of the same budget on short commitment sentences extracted from
 * user turns that would otherwise be dropped.
 *
 * Tool results, recovery feedback and app context also travel with role
 * "user", so role alone cannot identify the human. Only turns whose exact text
 * the human typed (the chat bubbles) may become commitments; a web page that
 * says "never ..." must not be pinned as a user rule.
 *
 * It is an offline experiment: the benchmark compares commitment loss of both
 * selections under an identical budget before any runtime use.
 */
object CommitmentPinning {
    const val DEFAULT_RESERVE_FRACTION = 0.15
    const val MAX_COMMITMENTS = 8
    const val MAX_COMMITMENT_CHARS = 200

    private val cue = Regex(
        "(?iu)(?:" +
            "\\bне\\s+(?:чіпай|змінюй|видаляй|запускай|використовуй|роби|публікуй|комітай|пуш|відправляй|трогай|меняй|удаляй|запускай)|" +
            "\\bніколи\\b|\\bникогда\\b|\\bnever\\b|" +
            "\\bтільки\\b|\\bлише\\b|\\bтолько\\b|\\bonly\\b|" +
            "\\bзаборон|\\bзапрещ|\\bforbid|" +
            "\\bзавжди\\b|\\bвсегда\\b|\\balways\\b|" +
            "\\bdon't\\b|\\bdo not\\b|\\bmust not\\b|\\bnie\\s+(?:zmieniaj|usuwaj|uruchamiaj)" +
            ")"
    )

    /** Commitment sentences from human-typed turns, newest first, deduplicated. */
    fun commitments(
        messages: List<OllamaMessage>,
        humanTurns: Set<String>
    ): List<String> =
        messages
            .filter { it.role == "user" && it.content.trim() in humanTurns }
            .asReversed()
            .flatMap { message ->
                message.content
                    // Split on sentence ends followed by whitespace, so file
                    // names like config.json stay inside their sentence.
                    .split(Regex("(?<=[.!?;])\\s+|\\n"))
                    .map { it.replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isNotBlank() && cue.containsMatchIn(it) }
            }
            .map { it.take(MAX_COMMITMENT_CHARS) }
            .distinctBy { it.lowercase() }
            .take(MAX_COMMITMENTS)

    fun compact(
        messages: List<OllamaMessage>,
        budget: OllamaRequestBudget,
        humanTurns: Set<String>,
        reserveFraction: Double = DEFAULT_RESERVE_FRACTION
    ): List<OllamaMessage> {
        val baseline = OllamaContextPolicy.compact(messages, budget)
        val keptUser = baseline.filter { it.role == "user" }.map { it.content }.toSet()
        val dropped = messages.filter { message ->
            message.role == "user" && keptUser.none { kept -> kept == message.content }
        }
        val pinnedLines = commitments(dropped, humanTurns.map { it.trim() }.toSet())
        if (pinnedLines.isEmpty()) return baseline

        val reserve = (budget.maxChars * reserveFraction.coerceIn(0.0, 0.3)).toInt()
        val header = "PINNED USER COMMITMENTS (from earlier turns; still binding unless the user revoked them):"
        val pinned = buildString {
            append(header)
            for (line in pinnedLines) {
                val next = "\n- $line"
                if (length + next.length > reserve) break
                append(next)
            }
        }
        if (pinned.length <= header.length) return baseline

        val smaller = budget.copy(maxChars = (budget.maxChars - pinned.length).coerceAtLeast(1))
        val selected = OllamaContextPolicy.compact(messages, smaller)
        val system = selected.firstOrNull { it.role == "system" }
        return buildList {
            system?.let(::add)
            add(OllamaMessage("user", pinned))
            addAll(selected.filterNot { it.role == "system" })
        }
    }
}
