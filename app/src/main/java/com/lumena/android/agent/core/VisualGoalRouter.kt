package com.lumena.android.agent.core

data class VisualGoalRoute(
    val query: String
)

/**
 * Deterministic router for explicit find/show-image goals.
 *
 * It does not use the model to decide whether visual evidence is mandatory.
 * The model may refine a failed search later, but the application performs the
 * first safe read-only image.search itself.
 */
object VisualGoalRouter {
    private val imageTerms = listOf(
        "фото", "зображ", "картин", "image", "photo", "picture",
        "zdję", "obraz"
    )

    private val actionTerms = listOf(
        "знайд", "покаж", "пошук", "пошукай",
        "find", "show", "search",
        "znajd", "pokaż", "wyszuk"
    )

    fun route(goal: String): VisualGoalRoute? {
        val normalized = goal
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        if (normalized.isBlank()) return null

        val lower = normalized.lowercase()
        val isVisual = imageTerms.any(lower::contains)
        val hasAction = actionTerms.any(lower::contains)
        if (!isVisual || !hasAction) return null

        val cleaned = normalized
            .replace(
                Regex(
                    "(?iu)\\b(знайди|знайти|знайдеш|покажи|показати|пошукай|пошукати|" +
                        "find|show|search|znajdź|znajdz|pokaż|pokaz|wyszukaj)\\b"
                ),
                " "
            )
            .replace(
                Regex(
                    "(?iu)\\b(мені|мне|me|mi|будь\\s+ласка|please|proszę|prosze)\\b"
                ),
                " "
            )
            .replace(
                Regex(
                    "(?iu)\\b(в\\s+інтернеті|у\\s+інтернеті|онлайн|in\\s+the\\s+internet|" +
                        "online|w\\s+internecie)\\b"
                ),
                " "
            )
            .replace(Regex("(?iu)\\s+і\\s*$"), " ")
            .replace(Regex("(?iu)\\s+and\\s*$"), " ")
            .replace(Regex("(?iu)\\s+i\\s*$"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim(' ', ',', '.', ':', ';', '-', '—')

        val query = cleaned
            .ifBlank { normalized }
            .take(180)

        return VisualGoalRoute(query)
    }
}
