package com.lumena.android.listing

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Listing attention: decides which marketplace listings deserve the owner's
 * attention right now.
 *
 * Input is only what a marketplace app (OLX) already pushes to this phone as
 * a notification; nothing is fetched or scraped. The target is a rare,
 * private, skill-matching offer that is gone within minutes, not an offer
 * reposted again and again because nobody stays in that job.
 *
 * Learning honesty: the model changes ONLY on the owner's explicit 👍/👎.
 * Ingesting listings, alerting, and the model's own scores never update it,
 * so the skill cannot confirm itself. Red flags are what the listing text
 * declares, not a verified judgement about the employer.
 */
data class ListingNotice(
    val source: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** Public page of the listing, when the source provides one. */
    val url: String? = null,
    /** When Lumena saw it; independent of a site's publication/republication date. */
    val observedAt: Long = postedAt
)

enum class ListingDecision {
    /** Rare and matching: alert loudly now. */
    ALERT,

    /** Possibly worth a look: shown in the panel, no sound. */
    DIGEST,

    /** Background: kept only for rarity and repost statistics. */
    QUIET
}

data class ListingRecord(
    /** Unique per sighting: content key plus first-seen time. */
    val id: String,
    /** Same text from the same source gives the same key. */
    val contentKey: String,
    val source: String,
    val title: String,
    val text: String,
    val seenAt: Long,
    val tokens: List<String> = emptyList(),
    val features: Map<String, Double> = emptyMap(),
    val score: Double = 0.0,
    val decision: ListingDecision = ListingDecision.QUIET,
    val reasons: List<String> = emptyList(),
    /** Owner label: +1 worth attention, -1 not; null until labelled. */
    val feedback: Int? = null,
    val feedbackAt: Long? = null,
    val url: String? = null
)

/** Weights missing from [weights] fall back to [ListingAttentionPolicy.PRIOR_WEIGHTS]. */
data class ListingLearner(
    val weights: Map<String, Double> = emptyMap(),
    val tokenWeights: Map<String, Double> = emptyMap(),
    val updates: Int = 0
)

/** Raw notification fields, kept briefly so parsing can be checked against the real format. */
data class ListingRawCapture(
    val source: String,
    val category: String? = null,
    val groupSummary: Boolean = false,
    val messaging: Boolean = false,
    val title: String = "",
    val text: String = "",
    val lines: List<String> = emptyList(),
    val at: Long = 0
)

data class ListingAttentionState(
    val version: Int = 1,
    val records: List<ListingRecord> = emptyList(),
    val learner: ListingLearner = ListingLearner(),
    val rawCaptures: List<ListingRawCapture> = emptyList()
)

data class ListingIngestResult(
    val state: ListingAttentionState,
    val record: ListingRecord?,
    val duplicate: Boolean
)

data class ListingAttentionMetrics(
    val seen: Int,
    val alerts: Int,
    val digests: Int,
    val labelled: Int,
    val alertsUseful: Int,
    val alertsUseless: Int,
    /** 👍 on something that was NOT alerted: the skill missed it. */
    val missed: Int,
    val learningUpdates: Int,
    /** 0..1: how much of the rarity baseline exists yet. */
    val archiveWarmup: Double
) {
    /** Share of labelled alerts the owner found worth attention; null until any exist. */
    fun alertPrecision(): Double? {
        val labelled = alertsUseful + alertsUseless
        return if (labelled == 0) null else alertsUseful.toDouble() / labelled
    }
}

/** Turns notification fields into listing notices. Private chat never becomes a notice. */
object ListingNoticeExtractor {
    const val OLX_PL_PACKAGE = "pl.tablica"
    private const val CATEGORY_MESSAGE = "msg"

    // Privacy boundary: package-name substring matching accepted unrelated apps
    // (including foreign-market packages). Only explicitly verified IDs belong here.
    fun isWatchedSource(packageName: String): Boolean =
        packageName == OLX_PL_PACKAGE

    /** Chat with sellers or buyers is private; it is neither scored nor stored. */
    fun isPrivateMessage(raw: ListingRawCapture): Boolean =
        raw.messaging || raw.category == CATEGORY_MESSAGE

    fun extract(raw: ListingRawCapture): List<ListingNotice> {
        if (!isWatchedSource(raw.source) || isPrivateMessage(raw)) return emptyList()
        // A group summary repeats its children, which arrive on their own.
        if (raw.groupSummary) return emptyList()
        val lines = raw.lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1) {
            return lines.map { line -> ListingNotice(raw.source, line, "", raw.at) }
        }
        val title = raw.title.trim()
        val text = raw.text.trim()
        if (title.isEmpty() && text.isEmpty()) return emptyList()
        return listOf(ListingNotice(raw.source, title, text, raw.at))
    }
}

internal object ListingText {
    private val polish = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z'
    )

    private val stopwords = setOf(
        "dla", "oraz", "jest", "sie", "nie", "lub", "ale", "jak", "the", "and",
        "praca", "pracy", "prace", "osoba", "osoby", "osobe", "osob", "ogloszenie",
        "nowe", "nowa", "nowy", "ogloszenia", "olx", "tel", "kontakt", "zapraszam",
        "zapraszamy", "robota", "роботу", "робота", "для", "або"
    )

    /**
     * Lowercase; every run of non-letters/digits becomes one space; padded
     * with spaces so " cue" matches at a word start. With [fold], Polish
     * letters lose their diacritics (ł → l).
     */
    fun normalize(raw: String, fold: Boolean): String {
        val lower = raw.lowercase(Locale.ROOT)
        val out = StringBuilder(lower.length + 2).append(' ')
        var space = true
        for (ch in lower) {
            val c = if (fold) polish[ch] ?: ch else ch
            if (c.isLetterOrDigit()) {
                out.append(c)
                space = false
            } else if (!space) {
                out.append(' ')
                space = true
            }
        }
        if (!space) out.append(' ')
        return out.toString()
    }

    fun has(normalized: String, cue: String): Boolean = normalized.contains(" $cue")

    /**
     * Content words cut to a 6-letter prefix: a crude stem, so Polish
     * inflections (przeprowadzka / przeprowadzce) learn as one word.
     */
    fun tokens(folded: String): List<String> =
        folded.split(' ')
            .filter { it.length >= 3 && it !in stopwords }
            .map { it.take(STEM_LENGTH) }
            .distinct()
            .take(40)

    private const val STEM_LENGTH = 6

    fun key(source: String, folded: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$source|${folded.trim()}".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }
}

object ListingAttentionPolicy {
    const val ALERT_AT = 0.60
    const val DIGEST_AT = 0.35
    const val DUPLICATE_WINDOW_MS = 48L * 60 * 60 * 1000
    const val CHURN_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    const val RETENTION_MS = 60L * 24 * 60 * 60 * 1000
    const val MAX_RECORDS = 1500
    const val MAX_RAW_CAPTURES = 30
    const val REPOST_SIMILARITY = 0.6
    const val WARMUP_RECORDS = 50
    /** Global features move slowly; specific words learn faster. */
    const val FEATURE_LEARNING_RATE = 0.15
    const val TOKEN_LEARNING_RATE = 0.6
    const val L2 = 0.02
    const val MAX_TOKEN_WEIGHTS = 3000
    private const val COMMON_TOKEN_SHARE = 0.3
    private const val MIN_RECORDS_FOR_COMMON = 20
    private const val MAX_TITLE = 200
    private const val MAX_TEXT = 600

    /**
     * Hand-set starting point taken from the owner's own words. Feedback
     * moves these; L2 pulls them back toward this prior so a few labels
     * cannot swing the skill to an extreme.
     */
    val PRIOR_WEIGHTS: Map<String, Double> = mapOf(
        "bias" to -1.3,
        "skill" to 2.0,
        "private" to 1.2,
        "interest" to 1.0,
        "novelty" to 0.5,
        "company" to -0.6,
        "mass_hiring" to -1.0,
        "red_flag" to -2.0,
        "churn" to -2.0
    )

    /** Owner's skills. Cues are folded (no Polish diacritics) and match at a word start. */
    internal val SKILL_GROUPS: Map<String, List<String>> = mapOf(
        "водіння" to listOf(
            "kierowc", "kierowanie", "prawo jazdy", "prawem jazdy", "kat b",
            "kategorii b", "samochod", "dowoz", "busem", "воді", "водител"
        ),
        "електрика" to listOf("elektry", "elektromont", "електр"),
        "гідравліка" to listOf("hydraul", "гідравл", "гидравл", "сантехн"),
        "інструмент" to listOf(
            "zlota raczka", "zlotej raczki", "konserwat", "remont", "narzedzi",
            "montaz", "monter", "majster", "fachow", "wykonczen", "ремонт",
            "монтаж", "майстер"
        ),
        "декоративна штукатурка" to listOf(
            "tynk", "sztukater", "stiuk", "dekoracyjn", "wenecki",
            "beton architekt", "gladz", "штукатур", "декоратив"
        )
    )

    /** First-person / household wording. Matched WITH diacritics: poszukuję ≠ poszukuje. */
    internal val PRIVATE_CUES = listOf(
        "szukam", "poszukuję", "potrzebuję", "prywatn", "osobist", "dla mnie",
        "mojej", "mojego", "mój", "moja", "шукаю", "ищу"
    )

    internal val COMPANY_CUES = listOf(
        "agencj", "rekrutac", "cv", "aplikuj", "oferujemy", "zapewniamy",
        "wymagania", "wymagamy", "sp z o o", "firma", "spolka", "zespol",
        "benefit", "zakwaterowan", "kandydat", "obowiazki", "stanowisk"
    )

    /** Wording of constant hiring: high turnover, anyone is taken. */
    internal val MASS_HIRING_CUES = listOf(
        "od zaraz", "od jutra", "bez doswiadczenia", "dla kazdego", "dla par",
        "dla obcokrajowcow", "stala rekrutacja", "wiele wolnych", "nabor",
        "zatrudnimy", "przyjmiemy", "wielu kandydat", "odnawiane ogloszenie"
    )

    /** Declared conditions the owner wants to avoid, with a readable label. */
    internal val RED_FLAGS: List<Pair<String, String>> = listOf(
        "w nocy" to "нічна робота",
        "nocn" to "нічна робота",
        "нічн" to "нічна робота",
        "12h" to "зміни по 12 год",
        "12 h" to "зміни по 12 год",
        "12 godz" to "зміни по 12 год",
        "zmianow" to "змінний графік",
        "na zmiany" to "змінний графік",
        "weekend" to "робота у вихідні",
        "6 dni" to "6 днів на тиждень",
        "szesc dni" to "6 днів на тиждень",
        "akord" to "відрядна оплата",
        "odpornosc na stres" to "стрес / тиск",
        "pod presj" to "стрес / тиск",
        "dynamiczn" to "стрес / тиск",
        "nadgodzin" to "понаднормові",
        "toalet" to "прибирання туалетів",
        "sprzatan" to "прибирання",
        "прибиран" to "прибирання",
        "zmywak" to "мийка посуду",
        "ubojn" to "бійня",
        "chlodni" to "холодильна камера",
        "mrozn" to "холодильна камера",
        "bez umowy" to "без договору",
        "na czarno" to "без договору",
        "без договор" to "без договору"
    )

    internal val INTEREST_CUES = listOf(
        "roznych zadan", "rozne zadania", "rozne prace", "roznorodn",
        "do dyspozycji", "asystent", "elastyczn", "rozwoj", "szkolen",
        "przyucz", "dlugoterm", "stala wspolprac", "samodzieln",
        "різні завдання", "різних завдань"
    )

    fun ingest(state: ListingAttentionState, notice: ListingNotice): ListingIngestResult {
        val title = notice.title.trim().take(MAX_TITLE)
        val text = notice.text.trim().take(MAX_TEXT)
        if (title.isEmpty() && text.isEmpty()) return ListingIngestResult(state, null, duplicate = false)
        val now = notice.observedAt
        val retained = state.records.filter { now - it.seenAt <= RETENTION_MS }

        val key = ListingText.key(notice.source, ListingText.normalize("$title $text", fold = true))
        if (retained.any { it.contentKey == key && abs(now - it.seenAt) <= DUPLICATE_WINDOW_MS }) {
            return ListingIngestResult(state, null, duplicate = true)
        }

        // A title shared by earlier notices is a saved-search label or a
        // reposted ad, not this listing's content: score the text alone.
        val labelTitle = title.isNotEmpty() && text.isNotEmpty() &&
            retained.count { it.title.equals(title, ignoreCase = true) } >= 2
        val content = if (labelTitle) text else "$title $text"
        val folded = ListingText.normalize(content, fold = true)
        val plain = ListingText.normalize(content, fold = false)
        // Similarity always compares the full notice, so a repost matches its
        // earlier copies; shared label words are dropped by commonTokens().
        val tokens = ListingText.tokens(ListingText.normalize("$title $text", fold = true))

        val reasons = mutableListOf<String>()
        val features = linkedMapOf<String, Double>()

        val skills = SKILL_GROUPS.filterValues { cues -> cues.any { ListingText.has(folded, it) } }.keys
        features["skill"] = min(skills.size, 2) / 2.0
        if (skills.isNotEmpty()) reasons += "навички: ${skills.joinToString(", ")}"

        val privatePerson = PRIVATE_CUES.any { ListingText.has(plain, it) }
        features["private"] = if (privatePerson) 1.0 else 0.0
        if (privatePerson) reasons += "схоже на приватну особу"

        val interest = INTEREST_CUES.count { ListingText.has(folded, it) }
        features["interest"] = min(1.0, interest / 2.0)
        if (interest > 0) reasons += "різноманітна / з перспективою"

        val company = COMPANY_CUES.count { ListingText.has(folded, it) }
        features["company"] = min(1.0, company / 3.0)
        if (company > 0) reasons += "ознаки фірми / відбору по CV"

        val mass = MASS_HIRING_CUES.filter { ListingText.has(folded, it) }
        features["mass_hiring"] = min(1.0, mass.size / 2.0)
        if (mass.isNotEmpty()) reasons += "масовий набір: ${mass.joinToString(", ")}"

        val flags = RED_FLAGS.filter { (cue, _) -> ListingText.has(folded, cue) }.map { it.second }.distinct()
        features["red_flag"] = min(1.0, flags.size / 2.0)
        if (flags.isNotEmpty()) reasons += "заявлено: ${flags.joinToString(", ")}"

        val common = commonTokens(retained)
        val own = tokens.toSet() - common
        val recent = retained.filter { (now - it.seenAt) in 0L..CHURN_WINDOW_MS }
        val reposts = recent.count { ListingText.jaccard(own, it.tokens.toSet() - common) >= REPOST_SIMILARITY }
        features["churn"] = min(1.0, reposts / 2.0)
        if (reposts > 0) reasons += "перепублікується: $reposts схожих за 30 днів"

        val warmup = min(1.0, retained.size.toDouble() / WARMUP_RECORDS)
        val maxSimilarity = retained.maxOfOrNull { ListingText.jaccard(own, it.tokens.toSet() - common) } ?: 0.0
        features["novelty"] = (1.0 - maxSimilarity) * warmup
        if (warmup < 1.0) {
            reasons += "архів ще накопичується (${retained.size}/$WARMUP_RECORDS)"
        } else if (maxSimilarity < 0.3) {
            reasons += "рідкісне: схожого не було"
        }

        val probability = score(state.learner, features, tokens)
        learnedReason(state.learner, tokens)?.let { reasons += it }
        val decision = when {
            probability >= ALERT_AT -> ListingDecision.ALERT
            probability >= DIGEST_AT -> ListingDecision.DIGEST
            else -> ListingDecision.QUIET
        }
        val record = ListingRecord(
            id = "$key@$now",
            contentKey = key,
            source = notice.source,
            title = title,
            text = text,
            seenAt = now,
            tokens = tokens,
            features = features,
            score = probability,
            decision = decision,
            reasons = reasons,
            url = notice.url
        )
        val records = (retained + record).takeLast(MAX_RECORDS)
        return ListingIngestResult(state.copy(records = records), record, duplicate = false)
    }

    /**
     * The only way the skill learns: one explicit owner label.
     * One logistic-regression step on the stored features, pulled toward the prior.
     */
    fun feedback(
        state: ListingAttentionState,
        recordId: String,
        useful: Boolean,
        now: Long
    ): ListingAttentionState {
        val index = state.records.indexOfFirst { it.id == recordId }
        if (index < 0) return state
        val record = state.records[index]
        val label = if (useful) 1 else -1
        if (record.feedback == label) return state

        val learner = state.learner
        val error = (if (useful) 1.0 else 0.0) - score(learner, record.features, record.tokens)
        val weights = PRIOR_WEIGHTS.mapValues { (name, prior) ->
            val w = weight(learner, name)
            val x = if (name == "bias") 1.0 else record.features[name] ?: 0.0
            w + FEATURE_LEARNING_RATE * (error * x - L2 * (w - prior))
        }
        val scale = tokenScale(record.tokens)
        val tokenWeights = learner.tokenWeights.toMutableMap()
        record.tokens.forEach { token ->
            val v = tokenWeights[token] ?: 0.0
            tokenWeights[token] = v + TOKEN_LEARNING_RATE * (error * scale - L2 * v)
        }
        val bounded = if (tokenWeights.size <= MAX_TOKEN_WEIGHTS) {
            tokenWeights.toMap()
        } else {
            tokenWeights.entries
                .sortedByDescending { abs(it.value) }
                .take(MAX_TOKEN_WEIGHTS)
                .associate { it.key to it.value }
        }

        val records = state.records.toMutableList()
        records[index] = record.copy(feedback = label, feedbackAt = now)
        return state.copy(
            records = records,
            learner = ListingLearner(weights, bounded, learner.updates + 1)
        )
    }

    fun capture(state: ListingAttentionState, raw: ListingRawCapture): ListingAttentionState {
        if (ListingNoticeExtractor.isPrivateMessage(raw)) return state
        val trimmed = raw.copy(
            title = raw.title.take(MAX_TITLE),
            text = raw.text.take(MAX_TEXT),
            lines = raw.lines.take(10).map { it.take(MAX_TITLE) }
        )
        return state.copy(rawCaptures = (state.rawCaptures + trimmed).takeLast(MAX_RAW_CAPTURES))
    }

    fun metrics(state: ListingAttentionState): ListingAttentionMetrics {
        val records = state.records
        val alerts = records.filter { it.decision == ListingDecision.ALERT }
        return ListingAttentionMetrics(
            seen = records.size,
            alerts = alerts.size,
            digests = records.count { it.decision == ListingDecision.DIGEST },
            labelled = records.count { it.feedback != null },
            alertsUseful = alerts.count { it.feedback == 1 },
            alertsUseless = alerts.count { it.feedback == -1 },
            missed = records.count { it.feedback == 1 && it.decision != ListingDecision.ALERT },
            learningUpdates = state.learner.updates,
            archiveWarmup = min(1.0, records.size.toDouble() / WARMUP_RECORDS)
        )
    }

    fun score(learner: ListingLearner, features: Map<String, Double>, tokens: List<String>): Double {
        var z = weight(learner, "bias")
        features.forEach { (name, x) -> z += weight(learner, name) * x }
        val scale = tokenScale(tokens)
        tokens.forEach { z += (learner.tokenWeights[it] ?: 0.0) * scale }
        return 1.0 / (1.0 + exp(-z))
    }

    private fun weight(learner: ListingLearner, name: String): Double =
        learner.weights[name] ?: PRIOR_WEIGHTS[name] ?: 0.0

    private fun tokenScale(tokens: List<String>): Double =
        if (tokens.isEmpty()) 0.0 else 1.0 / sqrt(tokens.size.toDouble())

    /** Tokens in a large share of the archive (location, saved-search name) say nothing about rarity. */
    private fun commonTokens(records: List<ListingRecord>): Set<String> {
        if (records.size < MIN_RECORDS_FOR_COMMON) return emptySet()
        val counts = HashMap<String, Int>()
        records.forEach { record -> record.tokens.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val limit = records.size * COMMON_TOKEN_SHARE
        return counts.filterValues { it > limit }.keys
    }

    private fun learnedReason(learner: ListingLearner, tokens: List<String>): String? {
        val scale = tokenScale(tokens)
        val strong = tokens
            .map { it to (learner.tokenWeights[it] ?: 0.0) * scale }
            .filter { abs(it.second) >= 0.15 }
            .sortedByDescending { abs(it.second) }
            .take(3)
        if (strong.isEmpty()) return null
        return "з ваших оцінок: " + strong.joinToString(", ") { (token, c) ->
            (if (c > 0) "+" else "−") + token
        }
    }
}
