package com.lumena.android.listing

import com.squareup.moshi.Moshi
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pracuj.pl job search as a listing source for the attention skill.
 *
 * Reads the public search page a person would open (robots.txt allows
 * /praca/), at low frequency, with an honest user agent. If the page has no
 * offer data (a block or challenge page), Lumena reports it and stops: no
 * TLS impersonation, user-agent rotation or challenge solving.
 */
data class PracujSearch(
    val city: String = "legionowo",
    val radiusKm: Int = 10,
    val keywords: String = ""
)

sealed class PracujPage {
    data class Offers(val notices: List<ListingNotice>) : PracujPage()

    /** The page carried no offer data; the reason is shown to the owner. */
    data class Blocked(val reason: String) : PracujPage()
}

object PracujSource {
    const val SOURCE = "pracuj.pl"
    private const val REPUBLISHED_AFTER_MS = 14L * 24 * 60 * 60 * 1000
    private val NEXT_DATA = Regex(
        """<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val polish = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z'
    )
    private val day = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC)
    private val json = Moshi.Builder().build().adapter(Any::class.java)

    // Explicit, bounded city aliases. Unknown Cyrillic must not silently turn
    // into the default Legionowo (or a partial ASCII slug).
    private val cityAliases = mapOf(
        "варшава" to "warszawa",
        "варшаві" to "warszawa",
        "легіоново" to "legionowo",
        "леґіоново" to "legionowo",
        "легионово" to "legionowo"
    )

    private fun hasNonLatinLetters(raw: String): Boolean =
        raw.any { c ->
            c.isLetter() &&
                Character.UnicodeScript.of(c.code) != Character.UnicodeScript.LATIN
        }

    /** Reject invalid city/keyword text rather than searching a different place. */
    fun searchUrl(search: PracujSearch): String {
        val cityRaw = search.city.trim()
        require(cityRaw.isNotEmpty()) { "Вкажи місто пошуку" }
        val city = cityAliases[cityRaw.lowercase(Locale.ROOT)] ?: run {
            require(!hasNonLatinLetters(cityRaw)) {
                "Невідома назва міста кирилицею — введи польську назву латиницею"
            }
            slug(cityRaw)
        }
        require(city.any(Char::isLetter)) { "Місто має містити літери" }
        val keywordsRaw = search.keywords.trim()
        require(!hasNonLatinLetters(keywordsRaw)) {
            "Ключові слова для Pracuj.pl потрібно вводити польською латиницею"
        }
        val keywords = slug(keywordsRaw)
        require(keywordsRaw.isEmpty() || keywords.isNotEmpty()) {
            "Ключові слова нечитабельні"
        }
        val radius = search.radiusKm.coerceIn(0, 100)
        val path = if (keywords.isEmpty()) "$city;wp" else "$keywords;kw/$city;wp"
        return "https://www.pracuj.pl/praca/$path?rd=$radius"
    }

    fun parse(html: String, now: Long): PracujPage {
        val raw = NEXT_DATA.find(html)?.groupValues?.get(1)
            ?: return PracujPage.Blocked("на сторінці немає даних вакансій (можливо, захист від ботів); Lumena його не обходить")
        val root = runCatching { json.fromJson(raw) }.getOrNull()
            ?: return PracujPage.Blocked("дані сторінки нечитабельні")
        val lists = mutableListOf<List<*>>()
        collectGroupedOffers(root, lists)
        if (lists.isEmpty()) {
            return PracujPage.Blocked("на сторінці немає списку вакансій (змінився формат або доступ обмежено)")
        }
        val offers = lists.maxByOrNull { it.size }.orEmpty()
        val notices = offers.flatMap { (it as? Map<*, *>)?.let { offer -> toNotices(offer, now) } ?: emptyList() }
        return PracujPage.Offers(notices.distinctBy { it.url ?: (it.title + it.text) })
    }

    private fun collectGroupedOffers(node: Any?, out: MutableList<List<*>>, depth: Int = 0) {
        if (depth > 12) return
        when (node) {
            is Map<*, *> -> node.forEach { (key, value) ->
                if (key == "groupedOffers" && value is List<*>) out += value
                collectGroupedOffers(value, out, depth + 1)
            }
            is List<*> -> node.forEach { collectGroupedOffers(it, out, depth + 1) }
        }
    }

    private fun toNotices(offer: Map<*, *>, now: Long): List<ListingNotice> {
        val title = (offer["jobTitle"] as? String)?.trim().orEmpty()
        if (title.isEmpty()) return emptyList()
        val groupedPlaces = (offer["offers"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.take(20)
            .orEmpty()
        val places: List<Map<*, *>?> = if (groupedPlaces.isEmpty()) listOf(null) else groupedPlaces
        val last = instant(offer["lastPublicated"])
        val first = instant(offer["initialPublicated"])
        val attributes = (offer["primaryAttributes"] as? List<*>).orEmpty().mapNotNull { attr ->
            ((attr as? Map<*, *>)?.get("label") as? Map<*, *>)?.get("text") as? String
        }
        val republished = if (first != null && last != null && last - first >= REPUBLISHED_AFTER_MS) {
            "odnawiane ogłoszenie (pierwsza publikacja ${day.format(Instant.ofEpochMilli(first))})"
        } else null
        return places.map { place ->
            val text = listOfNotNull(
                offer["companyName"] as? String,
                place?.get("displayWorkplace") as? String,
                offer["salaryDisplayText"] as? String,
                strings(offer["typesOfContract"]),
                strings(offer["workSchedules"]),
                strings(offer["positionLevels"]),
                attributes.joinToString(", ").ifBlank { null },
                republished,
                (offer["jobDescription"] as? String)?.take(400)
            ).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
            ListingNotice(
                source = SOURCE,
                title = title,
                text = text,
                postedAt = last ?: now,
                url = (place?.get("offerAbsoluteUri") as? String)?.takeIf {
                    it.startsWith("https://www.pracuj.pl/")
                },
                observedAt = now
            )
        }
    }

    private fun strings(value: Any?): String? =
        (value as? List<*>)?.filterIsInstance<String>()?.joinToString(", ")?.ifBlank { null }

    private fun instant(value: Any?): Long? =
        (value as? String)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun slug(raw: String): String =
        raw.trim().lowercase(Locale.ROOT)
            .map { polish[it] ?: it }
            .joinToString("")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
