package com.lumena.android.agent.core

/** Resolve only standalone follow-ups. Never carry execution state or approvals. */
object FollowUpGoal {
    fun isReference(text: String): Boolean = text.trim().lowercase()
        .trimEnd('.', '!', '?', '…').trim() in setOf(
            "повтори", "повторити", "спробуй ще раз", "продовж", "продовжуй",
            "повтори ще раз", "повторить", "продолжи", "repeat", "retry", "try again", "continue", "resume"
        )

    fun resolve(text: String, previousGoal: String?): String =
        if (isReference(text) && !previousGoal.isNullOrBlank()) previousGoal else text
}
