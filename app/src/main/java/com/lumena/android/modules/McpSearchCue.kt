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

    private data class Mention(val match: MatchResult, val requested: Boolean)

    // Respect the latest explicit directive, including a later prohibition.
    // Do not borrow a search verb from the next clause or an MCP-looking URL.
    private fun directives(text: String): List<Mention> =
        protocol.findAll(text).mapNotNull { hit ->
            val start = clauseStart(text, hit.range.first)
            val prefix = text.substring(start, hit.range.first).takeLast(150).trim()
            val end = text.indexOfAny(charArrayOf(',', ';', '.', '!', '?', '\n'), hit.range.last + 1)
                .let { if (it < 0) text.length else it }
            val local = text.substring(start, end)
            val preceding = text.getOrNull(hit.range.first - 1)
            if (preceding == '/' || preceding == '=' || preceding == '?') {
                null
            } else if (negative.containsMatchIn(prefix)) {
                Mention(hit, false)
            } else if (searchAction.containsMatchIn(local)) {
                Mention(hit, true)
            } else {
                null
            }
        }.toList()

    /** A later negated MCP instruction overrides earlier positive mentions. */
    fun searchRequested(raw: String): Boolean =
        directives(raw.take(3000)).lastOrNull()?.requested == true

    fun searchQuery(raw: String): String {
        val text = raw.take(3000)
        val active = directives(text).lastOrNull()?.takeIf { it.requested }?.match
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
