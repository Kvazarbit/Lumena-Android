package com.lumena.android.companion

/** Quoted Local-chat material, never a tool result or execution permission. */
data class CompanionHandoff(
    val messageId: String,
    val role: String,
    val text: String,
    val sessionId: String,
    val taskId: String,
    val topic: String
) {
    fun draftText(): String = buildString {
        appendLine("Допоможи продовжити роботу над цією темою в Lumena.")
        appendLine("Тема: $topic")
        appendLine("session_id=$sessionId")
        appendLine("task_id=$taskId")
        appendLine("source_message_id=$messageId")
        appendLine("Джерело: " + when (role) {
            "user" -> "запит користувача"
            "error" -> "повідомлення про помилку"
            else -> "відповідь локального чату (не перевірений результат інструмента)"
        })
        appendLine("--- Початок вибраного повідомлення ---")
        appendLine(text)
        append("--- Кінець вибраного повідомлення ---")
    }
}
