package com.lumena.android.agent.core

/** Conservative owner-request grammar. Mentions and historical prose are data. */
object OwnerActionText {
    private val verbs = "(?:використай|використовуй|використовуйте|запусти|запустіть|запускай|виконай|" +
        "прочитай|переглянь|перевір|перевірте|знайди|покажи|створи|створити|напиши|написати|" +
        "виправ|виправи|редагуй|відредагуй|додай|онови|реалізуй|реалізувати|зроби|зробіть|" +
        "покращ|прожени|прогнати|скомпілюй|используй|используйте|выполни|прочитай|проверь|найди|" +
        "создай|исправь|обнови|напиши|use|run|execute|rerun|read|inspect|review|find|show|" +
        "create|write|edit|patch|fix|update|implement|modify|improve|test|build|compile|commit|" +
        "użyj|uzyj|uruchom|wykonaj|przeczytaj|sprawdź|sprawdz|znajdź|napraw|utwórz|utworz)"
    private val request = Regex("(?iu)(?:^|[!?;:\\n]|\\.(?=\\s|$)|\\b(?:і|та|й|and|then|потім|спочатку|тепер|після\\s+цього)\\s+)" +
        "\\s*(?:[-*]|\\d+[.)])?\\s*(?:(?:будь\\s+ласка|please|proszę|тепер|спочатку|потім)\\s*[, :]?\\s*)?" +
        "(?:(?:можеш|можете|хочу|can\\s+you|could\\s+you|would\\s+you)\\s+)?$verbs\\b")

    fun hasRequest(text: String): Boolean = request.containsMatchIn(withoutToolNames(text))

    fun withoutToolNames(text: String): String {
        var out = text
        ToolRegistry.all().sortedByDescending { it.name.length }.forEach {
            out = out.replace(Regex("(?iu)(?<![a-z0-9_])${Regex.escape(it.name)}(?![a-z0-9_])"), " ".repeat(it.name.length))
        }
        return out
    }

    fun maskData(text: String, preserveFileNames: Boolean = false): String {
        var out = text.replace(Regex("(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)")) { " ".repeat(it.value.length) }
        val filename = Regex("[\\p{L}0-9_./\\\\ -]+\\.(?:py|kt|java|js|ts|html|css|json|md|txt)", RegexOption.IGNORE_CASE)
        for (pattern in listOf("\\\"[^\\\"\\n]*\\\"", "'[^'\\n]*'", "«[^»\\n]*»", "\\x60[^\\x60\\n]*\\x60")) {
            out = out.replace(Regex(pattern)) { match ->
                val body = match.value.drop(1).dropLast(1)
                if (preserveFileNames && filename.matches(body)) " $body " else " ".repeat(match.value.length)
            }
        }
        return out
    }
}
