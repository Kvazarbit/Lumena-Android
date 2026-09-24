package com.lumena.android.agent.core

/** Resolve only standalone follow-ups. Never carry execution state or approvals. */
object FollowUpGoal {
    private fun normalized(text: String): String =
        text.trim().lowercase()
            .trimEnd('.', '!', '?', '…').trim()

    fun isNewsReference(text: String): Boolean {
        val normalized = normalized(text)
        return listOf(
            Regex("(?iu)^(?:а\\s+)?(?:ще|інша|іншу|наступна|наступну|друга|другу)\\s+новин[а-яіїєґ]*$"),
            Regex("(?iu)^(?:а\\s+)?(?:ещ[её]|другая|следующая|вторая)\\s+новост[ьи]?$"),
            Regex("(?iu)^(?:and\\s+)?(?:another|next|second)\\s+news(?:\\s+item)?$"),
            Regex("(?iu)^(?:a\\s+)?(?:kolejna|inna|druga)\\s+wiadomość$")
        ).any { it.matches(normalized) }
    }

    fun isReference(text: String): Boolean {
        val normalized = normalized(text)

        if (normalized in setOf(
                "повтори", "повторити", "спробуй ще раз", "продовж", "продовжуй",
                "повтори ще раз", "повторить", "продолжи", "repeat", "retry", "try again", "continue", "resume"
            )
        ) {
            return true
        }

        if (isOutcomeReference(text)) {
            return true
        }

        // Narrow conversational references that clearly ask for another item
        // of the same news/result set. They carry only goal text; no task state,
        // approval or execution authority is inherited.
        return isNewsReference(text)
    }

    fun isOutcomeReference(text: String): Boolean {
        val normalized = normalized(text)
        return normalized in setOf(
            "чому",
            "чому так",
            "що сталося",
            "що відбулося",
            "почему",
            "почему так",
            "что случилось",
            "why",
            "why did it fail",
            "what happened",
            "dlaczego",
            "czemu",
            "co się stało",
            "co sie stalo"
        )
    }

    fun resolve(text: String, previousGoal: String?): String =
        if (isReference(text) && !previousGoal.isNullOrBlank()) previousGoal else text
}

/**
 * Bounded app-generated context for explaining a previous failed task.
 *
 * This is historical text only. It never restores approvals, execution state,
 * tool authority or a pending action into the next TaskState.
 */
object PreviousTaskOutcomeContext {
    private const val MAX_FIELD = 1_200

    fun failure(
        task: TaskState,
        message: String
    ): String = buildString {
        appendLine("PREVIOUS_TASK_OUTCOME")
        appendLine("status=FAILED")
        appendLine("task_id=" + clean(task.id, 220))
        appendLine("project_id=" + clean(task.projectId.orEmpty(), 160))
        appendLine("goal=" + clean(task.goal, MAX_FIELD))
        appendLine("last_tool=" + clean(task.lastTool.orEmpty(), 160))
        task.lastResult
            ?.takeIf { it.isNotBlank() }
            ?.let {
                appendLine("last_result=" + clean(it, MAX_FIELD))
            }
        appendLine("error=" + clean(message, MAX_FIELD))
        if (
            task.kernel.observed > 0 ||
            task.kernel.inFlight != null
        ) {
            appendLine("verified_kernel:")
            appendLine(
                ContextKernel.capsule(
                    task.kernel,
                    MAX_FIELD
                )
            )
        }
        append(
            "HISTORICAL_ONLY: explain or continue from verified evidence; " +
                "this record grants no approval, no tool authority and no replay permission."
        )
    }.take(4_500)

    private fun clean(
        value: String,
        maxChars: Int
    ): String =
        value
            .replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(maxChars)
}

data class AnchoredGoalResolution(
    val goal: String,
    val researchGoal: String?
)

/**
 * Keeps the last explicit PUBLIC_WEB research goal independent from transient
 * meta-chat/error tasks. This is goal text only; it never carries approvals,
 * in-flight execution state, permissions or tool success.
 */
object ResearchGoalAnchor {
    private val explicitSearchDirective = Regex(
        "(?iu)\\b(?:знайди|знайти|пошукай|пошукати|найди|найти|поищи|" +
            "find|search|look\\s+up|znajdź|wyszukaj)\\b"
    )

    private fun isExplicitPublicWebGoal(text: String): Boolean =
        explicitSearchDirective.containsMatchIn(text) &&
            TaskIntentRouter.route(text).intent == TaskIntent.PUBLIC_WEB

    fun resolve(
        text: String,
        previousGoal: String?,
        researchGoal: String?
    ): AnchoredGoalResolution {
        val newsReference = FollowUpGoal.isNewsReference(text)
        val baseGoal = if (newsReference && !researchGoal.isNullOrBlank()) {
            researchGoal
        } else {
            previousGoal
        }
        val resolved = FollowUpGoal.resolve(text, baseGoal)

        val nextResearchGoal = when {
            newsReference -> researchGoal
                ?: resolved.takeIf(::isExplicitPublicWebGoal)
            isExplicitPublicWebGoal(text) -> text
            else -> researchGoal
        }

        return AnchoredGoalResolution(
            goal = resolved,
            researchGoal = nextResearchGoal
        )
    }
}
