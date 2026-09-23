package com.lumena.android.agent.core

/** Resolve only standalone follow-ups. Never carry execution state or approvals. */
object FollowUpGoal {
    fun isReference(text: String): Boolean {
        val normalized = text.trim().lowercase()
            .trimEnd('.', '!', '?', '…').trim()

        if (normalized in setOf(
                "повтори", "повторити", "спробуй ще раз", "продовж", "продовжуй",
                "повтори ще раз", "повторить", "продолжи", "repeat", "retry", "try again", "continue", "resume"
            )
        ) {
            return true
        }

        // Narrow conversational references that clearly ask for another item
        // of the same news/result set. They carry only the previous goal text;
        // no task state, approval or execution authority is inherited.
        return listOf(
            Regex("(?iu)^(?:а\\s+)?(?:ще|інша|іншу|наступна|наступну|друга|другу)\\s+новин[а-яіїєґ]*$"),
            Regex("(?iu)^(?:а\\s+)?(?:ещ[её]|другая|следующая|вторая)\\s+новост[ьи]?$"),
            Regex("(?iu)^(?:and\\s+)?(?:another|next|second)\\s+news(?:\\s+item)?$"),
            Regex("(?iu)^(?:a\\s+)?(?:kolejna|inna|druga)\\s+wiadomość$")
        ).any { it.matches(normalized) }
    }

    fun resolve(text: String, previousGoal: String?): String =
        if (isReference(text) && !previousGoal.isNullOrBlank()) previousGoal else text
}
