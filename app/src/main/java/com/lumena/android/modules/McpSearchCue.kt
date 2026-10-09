package com.lumena.android.modules

import com.lumena.android.agent.core.TaskIntentRouter

/**
 * Bounded correction of SEARCH-ROUTING cues only. Raw subjects, city names,
 * URL targets and tool permissions are never silently rewritten.
 *
 * This decides only whether a READ_ONLY MCP search recipe is relevant.
 * ToolRegistry/ToolGate still own execution authority.
 */
internal object McpSearchCue {
    private const val PROTOCOL_PATTERN =
        """(?<![\p{L}\p{N}_])(?:[mм][cс][pрп]|model\s+context\s+protocol)(?![\p{L}\p{N}_])"""
    private val protocol = Regex(PROTOCOL_PATTERN, RegexOption.IGNORE_CASE)
    private val searchAction = Regex(
        """(?iu)(?<![\p{L}\p{N}_])(?:знайд\p{L}*|знайти|найд\p{L}*|пошук\p{L}*|пошукай|шукай|шукати|подивись|глянь|find|search|lookup|query|znajd\p{L}*|wyszuk\p{L}*|sprawd\p{L}*)(?![\p{L}\p{N}_])"""
    )
    private val negative = Regex(
        """(?iu)(?:^|\s)(?:не\s+(?:шукай|пошукай|знайди|шукати|знаходь|використовуй|використовувати|запускай|виконуй|роби)|не\s+(?:через|черз|чирез)|без|do\s+not\s+(?:use|search)|don't\s+(?:use|search)|never\s+(?:use|search)|nie\s+(?:szukaj|uzywaj|używaj))(?:\s+[\p{L}]+){0,8}\s*$"""
    )
    private val viaProtocol = Regex(
        """(?iu)(?<![\p{L}])(?:через|черз|чирез|via|using|przez|за\s+допомогою)\s+$PROTOCOL_PATTERN"""
    )
    private val leadingProtocol = Regex(
        """(?iu)^\s*$PROTOCOL_PATTERN\s+(?:пошук\p{L}*|search|знайд\p{L}*)\s*"""
    )

    private fun clauseStart(text: String, before: Int): Int {
        val i = text.take(before).indexOfLast { it in listOf(',', ';', '.', '!', '?', '\n') }
        return i + 1
    }

    private fun positiveHits(text: String): List<MatchResult> =
        protocol.findAll(text).filter { hit ->
            val start = clauseStart(text, hit.range.first)
            val prefix = text.substring(start, hit.range.first).takeLast(150).trim()
            val local = text.substring(start, minOf(text.length, hit.range.last + 100))
            searchAction.containsMatchIn(local) && !negative.containsMatchIn(prefix)
        }.toList()

    /** A negated mention is never a positive permission or an MCP recipe. */
    fun searchRequested(raw: String): Boolean = positiveHits(raw.take(3000)).isNotEmpty()

    fun searchQuery(raw: String): String {
        val text = raw.take(3000)
        val active = positiveHits(text).lastOrNull()
        // When one clause forbids MCP and a later one positively requests it,
        // do not send the earlier negative clause as search-provider query text.
        val current = active?.let { text.substring(clauseStart(text, it.range.first)) } ?: text
        var query = current.trim()
            .replace(Regex("""(?iu)^(?:а|але|but|then)\s+"""), "")
        query = TaskIntentRouter.publicSearchQuery(query)
        query = query.replace(viaProtocol, " ")
        query = query.replace(leadingProtocol, " ")
        return query.replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim(' ', '.', ',', ':', ';', '-', '—')
            .take(240)
    }
}
