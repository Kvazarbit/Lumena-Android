package com.lumena.android.ollama

/**
 * Commitment-aware context selection.
 *
 * Runtime use goes through the Layer Governor (layer "commitments"): only a
 * randomized share of tasks gets the pinned reminder, so its effect on
 * success and tokens is measured rather than assumed.
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
 * The offline benchmark compares commitment loss of both selections under an
 * identical budget.
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

    private val revokeCue = Regex(
        "(?iu)(?:\\bтепер\\s+можна|\\bможна\\b|\\bдозволяю\\b|\\bдозволено\\b|\\bскасовую\\b|" +
            "\\bзабудь\\b|\\bзамість\\b|\\bможно\\b|\\bразрешаю\\b|\\bвместо\\b|" +
            "\\byou may\\b|\\ballowed\\b|\\binstead of\\b|\\bforget\\b|\\bignore (?:that|my)\\b)"
    )

    /** Generic verbs that must not link unrelated commitments. */
    private val genericWords = setOf(
        "використовуй", "використай", "використовувати", "змінювати", "перевіряй",
        "напиши", "зроби", "используй", "использовать", "сделай", "please", "always"
    )

    /** Object words a commitment is about: files, versions and long nouns. */
    fun keyTokens(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}_./-]+")
            .findAll(text.lowercase())
            .map { it.value.trim('.', '-', '/') }
            .filter { token ->
                val objectLike = '.' in token || '/' in token ||
                    (token.length >= 2 && token.any { it.isDigit() }) ||
                    token.length >= 7
                objectLike && token !in genericWords &&
                    !cue.containsMatchIn(token) && !revokeCue.containsMatchIn(token)
            }
            .toSet()

    /**
     * Commitments from earlier human turns that are still in force.
     * A later turn revokes or replaces a commitment when it carries a
     * revocation/replacement cue and names the same object ("тепер можна
     * чіпати config.json", "замість v1 використовуй v2"). History is not
     * erased; the revoked line is simply no longer pinned.
     */
    fun activeCommitments(humanTurnsChronological: List<String>): List<String> {
        val active = mutableListOf<Pair<Int, String>>()
        humanTurnsChronological.forEachIndexed { index, turn ->
            val sentences = turn
                .split(Regex("(?<=[.!?;])\\s+|\\n"))
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
            if (sentences.any { revokeCue.containsMatchIn(it) }) {
                val tokens = keyTokens(turn)
                active.removeAll { (_, line) -> keyTokens(line).any { it in tokens } }
            }
            sentences
                .filter { cue.containsMatchIn(it) && !revokeCue.containsMatchIn(it) }
                .forEach { active += index to it.take(MAX_COMMITMENT_CHARS) }
        }
        return active
            .asReversed()
            .map { it.second }
            .distinctBy { it.lowercase() }
            .take(MAX_COMMITMENTS)
    }

    /** Reminder message for the model; bounded and explicitly user-sourced. */
    fun reminder(lines: List<String>, maxChars: Int = 600): String? {
        if (lines.isEmpty()) return null
        val header = "PINNED USER COMMITMENTS (typed by the user earlier in this session; still binding unless revoked):"
        val text = buildString {
            append(header)
            for (line in lines) {
                val next = "\n- $line"
                if (length + next.length > maxChars) break
                append(next)
            }
        }
        return text.takeIf { it.length > header.length }
    }

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
