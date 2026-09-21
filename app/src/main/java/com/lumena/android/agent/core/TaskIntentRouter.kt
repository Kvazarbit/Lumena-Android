package com.lumena.android.agent.core

enum class TaskIntent {
    VISUAL_SEARCH,
    OLLAMA_OPERATION,
    CODE_WORK,
    FILE_INSPECTION,
    PUBLIC_WEB,
    GENERAL
}

data class IntentPreflight(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val reason: String,
    val mandatory: Boolean = false
)

data class TaskIntentProfile(
    val intent: TaskIntent,
    val confidence: Int,
    val recommendedTools: List<String>,
    val guidance: String,
    val preflight: IntentPreflight? = null
)

/**
 * Pure deterministic intent policy.
 *
 * It does not attempt to understand arbitrary language like an LLM. It only
 * catches high-confidence operational intents where a safe local preflight
 * reduces hallucination and wasted model turns.
 */
object TaskIntentRouter {
    fun route(goal: String): TaskIntentProfile {
        val normalized = goal
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        val lower = normalized.lowercase()

        VisualGoalRouter.route(normalized)?.let { visual ->
            return TaskIntentProfile(
                intent = TaskIntent.VISUAL_SEARCH,
                confidence = 100,
                recommendedTools = listOf("image.search"),
                guidance = "The application must obtain displayable image evidence before completion.",
                preflight = IntentPreflight(
                    tool = "image.search",
                    args = mapOf(
                        "query" to visual.query,
                        "limit" to "4"
                    ),
                    reason = "Obtain required display-ready visual evidence before the model answers.",
                    mandatory = true
                )
            )
        }

        if (isOllamaOperation(lower)) {
            return TaskIntentProfile(
                intent = TaskIntent.OLLAMA_OPERATION,
                confidence = 95,
                recommendedTools = listOf(
                    "ollama.status",
                    "ollama.generate",
                    "ollama.start",
                    "ollama.pull",
                    "process.status",
                    "system.info"
                ),
                guidance = "Inspect real Ollama CLI/API state before changing or querying the local runtime.",
                preflight = IntentPreflight(
                    tool = "ollama.status",
                    reason = "Inspect the real local Ollama state before planning the operation.",
                    mandatory = true
                )
            )
        }

        if (isCodeWork(lower)) {
            return TaskIntentProfile(
                intent = TaskIntent.CODE_WORK,
                confidence = 82,
                recommendedTools = listOf(
                    "context.snapshot",
                    "workspace.list",
                    "file.search",
                    "file.read",
                    "file.write",
                    "file.patch",
                    "python.syntax_check",
                    "python.tests",
                    "python.run",
                    "git.status",
                    "git.diff"
                ),
                guidance = "Orient to verified workspace/project state before editing; verify code changes before completion.",
                preflight = IntentPreflight(
                    tool = "context.snapshot",
                    args = emptyMap(),
                    reason = "Load a compact verified environment/project snapshot before code work.",
                    mandatory = false
                )
            )
        }

        if (isFileInspection(lower) && !isPublicWeb(lower)) {
            return TaskIntentProfile(
                intent = TaskIntent.FILE_INSPECTION,
                confidence = 86,
                recommendedTools = listOf(
                    "workspace.list",
                    "file.list",
                    "file.search",
                    "file.read",
                    "git.status",
                    "git.diff",
                    "git.log"
                ),
                guidance = "Discover real paths first; never invent cwd or file paths.",
                preflight = IntentPreflight(
                    tool = "workspace.list",
                    reason = "Discover real workspace/read-only roots before file inspection.",
                    mandatory = true
                )
            )
        }

        if (isPublicWeb(lower)) {
            return TaskIntentProfile(
                intent = TaskIntent.PUBLIC_WEB,
                confidence = 72,
                recommendedTools = listOf(
                    "web.search",
                    "web.read",
                    "http.json",
                    "http.get",
                    "image.search"
                ),
                guidance = "Search with web.search; read relevant source URLs with web.read or documented http.json APIs. Cite fetched URLs, compare sources for current claims. Snippets/homepages do not prove popularity or profit. If evidence is missing, report partial; distinguish observed failures from hypotheses.",
                preflight = if ("https://" in lower || "http://" in lower) null else IntentPreflight(
                    tool = "web.search",
                    args = mapOf("query" to goal.trim().replace(Regex("\\s+"), " ").take(400)),
                    reason = "Find real source URLs before making current public claims.",
                    mandatory = true
                )
            )
        }

        return TaskIntentProfile(
            intent = TaskIntent.GENERAL,
            confidence = 0,
            recommendedTools = emptyList(),
            guidance = "No deterministic operational recipe matched; let the model reason under the normal tool policy."
        )
    }

    private fun isOllamaOperation(lower: String): Boolean {
        val subject = listOf(
            "ollama", "локальн", "local model", "gguf model", "model loaded",
            "модель завантаж", "модель запущ", "модель працю", "модел запущ",
            "модел загруж", "модел работает"
        ).any { containsTerm(lower, it) }
        val action = listOf(
            "status", "стан", "статус", "запуст", "запущ", "start", "pull", "download",
            "generate", "генер", "завантаж", "loaded", "running", "працю", "работ",
            "uruchom", "działa", "dziala"
        ).any { containsTerm(lower, it) }
        return subject && action
    }

    private fun isCodeWork(lower: String): Boolean {
        val codeTerms = listOf(
            "python", ".py", "kotlin", ".kt", "java", ".java", "gradle",
            "скрипт", "script", "код", "code", "compile", "компіля",
            "test", "тест", "bug", "баг", "debug", "fix(", "repo", "repository",
            "github", "git "
        )
        val actionTerms = listOf(
            "створ", "create", "write", "напис", "реаліз", "implement",
            "виправ", "fix", "редаг", "edit", "patch", "перевір", "test",
            "запуст", "run", "debug", "build", "збір", "commit"
        )
        return codeTerms.any { containsTerm(lower, it) } && actionTerms.any { containsTerm(lower, it) }
    }

    private fun isFileInspection(lower: String): Boolean {
        val fileTerms = listOf(
            "файл", "file", "папк", "folder", "директор", "directory",
            "readme", "лог", "log", "репозитор", "repository", "repo"
        )
        val actionTerms = listOf(
            "знайд", "find", "покаж", "show", "прочит", "read", "відкрий",
            "open", "перевір", "inspect", "list", "список", "де ", "where"
        )
        return fileTerms.any { containsTerm(lower, it) } && actionTerms.any { containsTerm(lower, it) }
    }

    private fun isPublicWeb(lower: String): Boolean {
        return listOf(
            "інтернет", "internet", "web", "онлайн", "online", "api",
            "https://", "http://", "сайт", "website", "url", "latest",
            "останні новини", "актуальн"
        ).any { containsTerm(lower, it) }
    }

    /**
     * ASCII command words are matched as lexical tokens, not arbitrary substrings.
     * This prevents e.g. "latest" from satisfying the code/action term "test".
     * Cyrillic stems and explicit phrases intentionally keep substring matching.
     */
    private fun containsTerm(lower: String, term: String): Boolean {
        val lexicalAscii = term.isNotEmpty() && term.all { ch ->
            ch in 'a'..'z' || ch in '0'..'9' || ch == '_'
        }
        return if (lexicalAscii) {
            Regex("(?<![a-z0-9_])" + Regex.escape(term) + "(?![a-z0-9_])")
                .containsMatchIn(lower)
        } else {
            lower.contains(term)
        }
    }
}
