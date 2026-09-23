package com.lumena.android.agent.core

import com.lumena.android.ollama.OllamaMessage

enum class ResearchFollowUpKind {
    NONE,
    CONTINUE,
    NEXT,
    ALTERNATIVE,
    NTH,
    DEEPEN,
    VERIFY,
    COMPARE,
    SOURCE_IDENTITY,
    APPLY
}

data class ResearchThreadState(
    val rootGoal: String,
    val discoveredUrls: List<String> = emptyList(),
    val readUrls: List<String> = emptyList(),
    val revision: Int = 0
)

data class ResearchThreadResolution(
    val goal: String,
    val thread: ResearchThreadState?,
    val followUpKind: ResearchFollowUpKind = ResearchFollowUpKind.NONE,
    val ordinal: Int? = null,
    val contextMessage: String? = null
)

/**
 * Domain-independent continuation resolver for public-web research.
 *
 * It intentionally stores only bounded research context. It never stores or
 * restores approvals, permissions, in-flight tool state, or completion proof.
 */
object ResearchThreadResolver {
    private const val MAX_REFERENCE_CHARS = 500

    private val continueTerms = Regex(
        "(?iu)\\b(?:продовж(?:уй)?|продолж(?:ай|и)?|continue|resume|dalej|kontynuuj)\\b"
    )
    private val nextTerms = Regex(
        "(?iu)\\b(?:наступн[а-яіїєґ]*|далі|ще\\s+одн[а-яіїєґ]*|next|another|more|kolejn[aeiy]*|jeszcze)\\b"
    )
    private val alternativeTerms = Regex(
        "(?iu)\\b(?:інш[а-яіїєґ]*|альтернативн[а-яіїєґ]*|другой|иная|alternative|different|other|inn[ayie]*|alternatyw[aney]*)\\b"
    )
    private val deepenTerms = Regex(
        "(?iu)\\b(?:детальніше|докладніше|глибше|поглиб[а-яіїєґ]*|подробнее|глубже|deeper|details?|bardziej\\s+szczegółowo|dokładniej)\\b"
    )
    private val verifyTerms = Regex(
        "(?iu)\\b(?:перевір[а-яіїєґ]*|підтверд[а-яіїєґ]*|правда|проверь|подтверд[а-я]*|verify|validate|confirm|fact[- ]?check|sprawdź|potwierdź)\\b"
    )
    private val compareTerms = Regex(
        "(?iu)\\b(?:порівняй|порівняти|сравни|сравнить|compare|comparison|porównaj|porównać)\\b"
    )
    private val sourceIdentityTerms = Regex(
        "(?iu)(?:" +
            "\\b(?:яке|який|яка|що\\s+за)\\s+(?:джерел[оа]?|ресурс|сайт|сторінк[а-яіїєґ]*|посилання)\\b|" +
            "\\b(?:джерело|ресурс|сайт|сторінк[а-яіїєґ]*|посилання)\\s*\\??$|" +
            "\\bзвідки\\s+(?:це|ця|цей|дані|інформац[іяї])\\b|" +
            "\\bwhat\\s+(?:source|site|resource|page|link)\\b|" +
            "\\bwhere\\s+(?:is\\s+)?(?:this|that)\\s+from\\b|" +
            "\\b(?:source|site|resource|page|link)\\s*\\??$|" +
            "\\b(?:какой|какая|что\\s+за)\\s+(?:источник|ресурс|сайт|страниц[а-я]*|ссылка)\\b|" +
            "\\b(?:источник|ресурс|сайт|страниц[а-я]*|ссылка)\\s*\\??$|" +
            "\\bоткуда\\s+это\\b|" +
            "\\b(?:jakie|która|które|co\\s+to\\s+za)\\s+(?:źródło|strona|serwis|link)\\b|" +
            "\\b(?:źródło|strona|serwis|link)\\s*\\??$|" +
            "\\bskąd\\s+to\\b" +
        ")"
    )
    private val applyTerms = Regex(
        "(?iu)\\b(?:використай|застосуй|реалізуй|впровадь|додай\\s+у\\s+про[еє]кт|" +
            "используй|примени|реализуй|внедри|apply|use\\s+(?:it|this)|implement|integrate|" +
            "zastosuj|użyj|wdroż|zaimplementuj)\\b"
    )
    private val researchObjectTerms = Regex(
        "(?iu)\\b(?:результат[а-яіїєґ]*|джерел[а-яіїєґ]*|статт[яіїюе]*|варіант[а-яіїєґ]*|" +
            "документ[а-яіїєґ]*|посилання|source|result|item|article|document|link|" +
            "источник[а-я]*|результат[а-я]*|źródł[oaem]*|wynik[uiem]*|artykuł[uyem]*)\\b"
    )

    fun resolve(
        text: String,
        previousGoal: String?,
        thread: ResearchThreadState?
    ): ResearchThreadResolution {
        val trimmed = text.trim()

        // When a thread already exists, a clear relational follow-up wins even
        // if the sentence also contains words like "internet", "online" or a
        // URL. Otherwise "verify this online" would incorrectly start a new
        // research root instead of continuing the active one.
        val classified = classifyFollowUp(trimmed, thread != null)
        val explicitResearchGoal = isExplicitResearchGoal(trimmed)
        val relationalFollowUp =
            classified.first != ResearchFollowUpKind.NONE &&
                (
                    !explicitResearchGoal ||
                        clearlyReferencesActiveThread(
                            text = trimmed,
                            kind = classified.first
                        )
                    )

        if (thread != null && relationalFollowUp) {
            val kind = classified.first
            val ordinal = classified.second
            val context = contextMessage(thread, kind, ordinal, trimmed)

            if (kind == ResearchFollowUpKind.APPLY) {
                return ResearchThreadResolution(
                    goal = trimmed,
                    thread = thread,
                    followUpKind = kind,
                    ordinal = ordinal,
                    contextMessage = context
                )
            }

            val continuation = buildContinuationGoal(
                thread = thread,
                kind = kind,
                ordinal = ordinal,
                userText = trimmed
            )
            return ResearchThreadResolution(
                goal = continuation,
                thread = thread,
                followUpKind = kind,
                ordinal = ordinal,
                contextMessage = context
            )
        }

        if (explicitResearchGoal) {
            val next = ResearchThreadState(rootGoal = trimmed.take(8_000))
            return ResearchThreadResolution(
                goal = trimmed,
                thread = next
            )
        }

        val resolved = FollowUpGoal.resolve(trimmed, previousGoal)
        return ResearchThreadResolution(
            goal = resolved,
            thread = thread
        )
    }

    private fun buildContinuationGoal(
        thread: ResearchThreadState,
        kind: ResearchFollowUpKind,
        ordinal: Int?,
        userText: String
    ): String = buildString {
        appendLine(thread.rootGoal)
        appendLine()
        append("RESEARCH FOLLOW-UP: ")
        append(
            when (kind) {
                ResearchFollowUpKind.CONTINUE ->
                    "continue the same research thread from current verified evidence"
                ResearchFollowUpKind.NEXT ->
                    "return the next distinct useful result; do not repeat an already used source"
                ResearchFollowUpKind.ALTERNATIVE ->
                    "find a materially different source or approach; do not repeat an already used source"
                ResearchFollowUpKind.NTH ->
                    "focus only on result/item #${ordinal ?: 1}; do not repeat earlier items unless needed for context"
                ResearchFollowUpKind.DEEPEN ->
                    "go deeper on the relevant finding and read primary/source material where possible"
                ResearchFollowUpKind.VERIFY ->
                    "verify the relevant claim with current source evidence; distinguish proof from model prose"
                ResearchFollowUpKind.COMPARE ->
                    "compare the relevant alternatives using source evidence and explicit criteria"
                ResearchFollowUpKind.SOURCE_IDENTITY ->
                    "identify the concrete source/resource behind the relevant previous finding from observed tool evidence; give source name and URL when available; distinguish search-snippet sources from pages actually read; do not ask the user to resend a URL that is already present in the active research context"
                else -> "continue the same research thread"
            }
        )
        appendLine()
        append("USER FOLLOW-UP: ")
        append(userText.take(MAX_REFERENCE_CHARS))
    }.take(8_000)

    fun observeHistory(
        history: List<OllamaMessage>,
        thread: ResearchThreadState?
    ): ResearchThreadState? {
        if (thread == null) return null

        val discovered = LinkedHashSet(thread.discoveredUrls)
        val read = LinkedHashSet(thread.readUrls)

        history.takeLast(24).forEach { message ->
            if (message.role != "user") return@forEach
            val content = message.content
            if (!content.startsWith("TOOL_RESULT for ")) return@forEach
            if (!content.lineSequence().any { it.trim() == "ok=true" }) return@forEach

            val tool = content
                .lineSequence()
                .firstOrNull()
                ?.removePrefix("TOOL_RESULT for ")
                ?.removeSuffix(":")
                ?.trim()
                .orEmpty()

            if (tool !in setOf("web.search", "web.read", "http.get", "http.json")) {
                return@forEach
            }

            extractHttpsUrls(content).forEach(discovered::add)
            if (tool in setOf("web.read", "http.get", "http.json")) {
                extractHttpsUrls(content).forEach(read::add)
            }
        }

        val boundedDiscovered = discovered.toList().takeLast(24)
        val boundedRead = read.toList().takeLast(16)
        val changed =
            boundedDiscovered != thread.discoveredUrls ||
                boundedRead != thread.readUrls

        return if (changed) {
            thread.copy(
                discoveredUrls = boundedDiscovered,
                readUrls = boundedRead,
                revision = thread.revision + 1
            )
        } else {
            thread
        }
    }

    private fun clearlyReferencesActiveThread(
        text: String,
        kind: ResearchFollowUpKind
    ): Boolean {
        if (kind in setOf(
                ResearchFollowUpKind.CONTINUE,
                ResearchFollowUpKind.NEXT,
                ResearchFollowUpKind.ALTERNATIVE,
                ResearchFollowUpKind.NTH,
                ResearchFollowUpKind.SOURCE_IDENTITY
            )
        ) {
            return true
        }

        return Regex(
            "(?iu)\\b(?:це|цей|ця|цю|цього|того|той|попередн[а-яіїєґ]*|" +
                "знайден[а-яіїєґ]*|отриман[а-яіїєґ]*|вище|this|it|that|those|" +
                "previous|above|same|found|these|это|этот|того|предыдущ[а-я]*|" +
                "to|ten|ta|tego|poprzedn[iaey]*|powyżej)\\b"
        ).containsMatchIn(text)
    }

    private fun classifyFollowUp(
        text: String,
        hasThread: Boolean
    ): Pair<ResearchFollowUpKind, Int?> {
        if (!hasThread || text.isBlank() || text.length > MAX_REFERENCE_CHARS) {
            return ResearchFollowUpKind.NONE to null
        }

        parseOrdinal(text)?.let {
            return ResearchFollowUpKind.NTH to it
        }
        if (applyTerms.containsMatchIn(text)) {
            return ResearchFollowUpKind.APPLY to null
        }
        if (compareTerms.containsMatchIn(text)) {
            return ResearchFollowUpKind.COMPARE to null
        }
        if (verifyTerms.containsMatchIn(text)) {
            return ResearchFollowUpKind.VERIFY to null
        }
        if (deepenTerms.containsMatchIn(text)) {
            return ResearchFollowUpKind.DEEPEN to null
        }
        if (alternativeTerms.containsMatchIn(text)) {
            return ResearchFollowUpKind.ALTERNATIVE to null
        }
        if (nextTerms.containsMatchIn(text)) {
            return ResearchFollowUpKind.NEXT to null
        }
        if (continueTerms.containsMatchIn(text)) {
            return ResearchFollowUpKind.CONTINUE to null
        }
        if (sourceIdentityTerms.containsMatchIn(text)) {
            return ResearchFollowUpKind.SOURCE_IDENTITY to null
        }

        // Very short deictic references such as "ще?" or "another?" are useful
        // only when an active research thread already exists.
        if (researchObjectTerms.containsMatchIn(text) &&
            Regex("(?iu)\\b(?:ще|далі|інш[а-яіїєґ]*|another|next|other|more|ещ[её]|друг[а-я]*|kolejn[aeiy]*|inn[ayie]*)\\b")
                .containsMatchIn(text)
        ) {
            return ResearchFollowUpKind.NEXT to null
        }

        return ResearchFollowUpKind.NONE to null
    }

    private fun parseOrdinal(text: String): Int? {
        Regex("(?iu)(?:^|\\s|#)([2-9]|1[0-9]|20)(?:[- ]?(?:й|я|е|th|nd|rd|ty|gi|ga))?(?:\\s|$)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        val words = listOf(
            2 to Regex("(?iu)\\b(?:друг[а-яіїєґ]*|втор[а-я]*|second|drugi|druga)\\b"),
            3 to Regex("(?iu)\\b(?:трет[а-яіїєґ]*|third|trzeci|trzecia)\\b"),
            4 to Regex("(?iu)\\b(?:четверт[а-яіїєґ]*|fourth|czwart[ay])\\b"),
            5 to Regex("(?iu)\\b(?:п['’]?ят[а-яіїєґ]*|пят[а-я]*|fifth|piąt[ay])\\b")
        )
        return words.firstOrNull { (_, pattern) ->
            pattern.containsMatchIn(text)
        }?.first
    }

    private fun isExplicitResearchGoal(text: String): Boolean {
        if (text.isBlank()) return false
        if (looksLikeMetaConversation(text)) return false
        return TaskIntentRouter.route(text).intent == TaskIntent.PUBLIC_WEB
    }

    private fun looksLikeMetaConversation(text: String): Boolean {
        val meta = Regex(
            "(?iu)\\b(?:контекст|context|забув|забыла|забыл|пам['’]?ят|remember|" +
                "технічн[а-яіїєґ]*\\s+збій|technical\\s+failure|помилк[а-яіїєґ]*|error|" +
                "не\\s+відповів|lost\\s+context|previous\\s+conversation)\\b"
        )
        return meta.containsMatchIn(text)
    }

    private fun contextMessage(
        thread: ResearchThreadState,
        kind: ResearchFollowUpKind,
        ordinal: Int?,
        userText: String
    ): String = buildString {
        appendLine("ACTIVE RESEARCH THREAD (context only; not permission or completion proof)")
        append("root_goal=")
        appendLine(thread.rootGoal.take(1_500))
        append("follow_up=")
        append(kind.name)
        ordinal?.let {
            append(" #")
            append(it)
        }
        appendLine()
        if (thread.discoveredUrls.isNotEmpty()) {
            appendLine("observed_source_urls:")
            thread.discoveredUrls.takeLast(12).forEach {
                append("- ")
                appendLine(it.take(1_000))
            }
        }
        if (thread.readUrls.isNotEmpty()) {
            appendLine("already_read_urls:")
            thread.readUrls.takeLast(8).forEach {
                append("- ")
                appendLine(it.take(1_000))
            }
        }
        appendLine(
            "These URLs were observed in successful tool results. " +
                "They are source references, not blanket proof of every claim."
        )
        if (kind == ResearchFollowUpKind.APPLY) {
            appendLine(
                "Before mutating project state, re-check the current project with local tools. " +
                    "Research context never grants execution authority."
            )
        }
        append("user_follow_up=")
        append(userText.take(MAX_REFERENCE_CHARS))
    }.take(6_000)

    private fun extractHttpsUrls(text: String): List<String> =
        Regex("""https://[^\s"'<>()\\]+""")
            .findAll(text)
            .map { match ->
                match.value.trimEnd('.', ',', ';', ':', ']', '}')
            }
            .filter { it.length <= 2_000 }
            .distinct()
            .toList()
}
