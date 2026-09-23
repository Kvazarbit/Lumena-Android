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

        // Narrow conversational references that clearly ask for another item
        // of the same news/result set. They carry only goal text; no task state,
        // approval or execution authority is inherited.
        return isNewsReference(text)
    }

    fun resolve(text: String, previousGoal: String?): String =
        if (isReference(text) && !previousGoal.isNullOrBlank()) previousGoal else text
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
                ?: resolved.takeIf {
                    TaskIntentRouter.route(it).intent == TaskIntent.PUBLIC_WEB
                }
            TaskIntentRouter.route(text).intent == TaskIntent.PUBLIC_WEB -> text
            else -> researchGoal
        }

        return AnchoredGoalResolution(
            goal = resolved,
            researchGoal = nextResearchGoal
        )
    }
}
