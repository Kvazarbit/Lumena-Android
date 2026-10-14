package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.net.URI

/**
 * Deterministic typed evidence checks. These checks do not grant permission or
 * turn a model's prose into proof. They only decide whether recorded external
 * evidence supports narrow acceptance criteria.
 *
 * Visual matching is deliberately METADATA ONLY: titles can be wrong. This
 * is a conservative mismatch filter, not a replacement for pixel inspection.
 */
internal object SemanticGoalEvidence {
    private val mapAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val word = Regex("[\\p{L}\\p{N}]+")
    private val stop = setOf(
        "знайди", "знайти", "знайдеш", "зображення", "фото", "фотографію",
        "фотографії", "покажи", "показати", "пошукай", "пошук", "мені",
        "інтернеті", "інтернет", "будь", "ласка", "картинку",
        "find", "show", "search", "me", "a", "an", "the", "of", "to",
        "and", "with", "in", "on", "at", "for", "please", "photo",
        "photos", "image", "images", "picture", "pictures", "online",
        "internet", "znajdź", "znajdz", "pokaż", "pokaz", "zdjęcie",
        "zdjecie", "zdjęcia", "zdjecia", "w", "na", "i", "z", "do",
        "та", "але", "що", "для", "на", "в", "у", "і", "и", "та", "це"
    )

    private fun canonical(term: String): String {
        val t = term.lowercase()
        return when {
            t in setOf("cat", "cats", "kitten", "kittens") ||
                t.startsWith("кот") || t.startsWith("кота") ||
                t.startsWith("кошк") || t.startsWith("кіт") ||
                t.startsWith("кіш") || t.startsWith("koc") || t.startsWith("kot") -> "cat"
            t in setOf("woman", "women", "female", "lady", "ladies") ||
                t.startsWith("жін") || t.startsWith("жен") ||
                t.startsWith("kobiet") -> "woman"
            t in setOf("man", "men", "male") || t.startsWith("чолов") -> "man"
            t in setOf("car", "cars", "automobile", "vehicle") ||
                t.startsWith("автомоб") || t.startsWith("машин") -> "car"
            t in setOf("red", "reddish") || t.startsWith("червон") ||
                t.startsWith("красн") || t.startsWith("czerw") -> "red"
            t in setOf("beach", "beaches") || t.startsWith("пляж") ||
                t.startsWith("plaż") -> "beach"
            t in setOf("bikini", "bikinis", "мікробікіні", "microbikini") -> "bikini"
            t == "micro" || t.startsWith("мікро") -> "micro"
            else -> t
        }
    }

    private fun tokens(s: String): Set<String> =
        word.findAll(s.lowercase())
            .map { canonical(it.value) }
            .filter { it.length >= 3 && it !in stop }
            .take(24)
            .toSet()

    /**
     * Require every significant goal term in the SAME candidate's title/alt.
     * Never treat echoed query, matched_query, or the source URL as proof:
     * those are search inputs/provenance, not independent image descriptors.
     */
    fun imageMetadataSupportsGoal(goal: String, stdout: String): Boolean {
        val required = tokens(VisualGoalRouter.route(goal)?.query ?: goal)
        if (required.isEmpty()) return false
        val payload = runCatching { mapAdapter.fromJson(stdout) }.getOrNull()
            ?: return false
        if (payload["display_ready"] != true) return false
        val images = payload["images"] as? List<*> ?: return false
        return images.any { item ->
            val image = item as? Map<*, *> ?: return@any false
            val description = listOf("title", "alt", "description")
                .mapNotNull { image[it] as? String }
                .joinToString(" ")
            val observed = tokens(description)
            observed.isNotEmpty() && required.all { it in observed }
        }
    }

    /** Only enforce explicitly named authoritative government host domains. */
    fun requiredOfficialHost(goal: String): String? {
        val rx = Regex(
            "(?i)(?<![a-z0-9.-])((?:[a-z0-9-]+\\.)*(?:gov\\.pl|gov\\.uk|gov\\.au|gov))(?![a-z0-9.-])"
        )
        return rx.find(goal)?.groupValues?.get(1)?.lowercase()
    }

    /**
     * Bridge must have returned the FINAL HTTPS URL after any redirects.
     * An input URL in args alone does not prove which domain was read.
     */
    fun fetchedFromRequiredHost(call: AgentDecision.ToolCall, stdout: String, host: String): Boolean {
        if (ToolRegistry.canonicalize(call.tool) !in
            setOf("web.read", "http.get", "http.json")) return false
        val payload = runCatching { mapAdapter.fromJson(stdout) }.getOrNull()
            ?: return false
        val finalUrl = payload["url"] as? String ?: return false
        val uri = runCatching { URI(finalUrl) }.getOrNull() ?: return false
        val actualHost = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            (actualHost == host || actualHost.endsWith("." + host))
    }

    fun explicitKotlinCompilationGoal(goal: String): Boolean {
        val lower = goal.lowercase()
        val kotlin = Regex("(?i)(?:\\bkotlin\\b|\\.kt\\b)").containsMatchIn(lower)
        val compile = Regex(
            "(?iu)(?:компілю|компил|компіля|compile|compiles|compiled|compilation|gradle\\s+build)"
        ).containsMatchIn(lower)
        return kotlin && compile
    }
}
