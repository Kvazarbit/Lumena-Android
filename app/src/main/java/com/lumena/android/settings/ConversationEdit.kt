package com.lumena.android.settings

/** Builds conversational context only: a branch is not a workspace rollback. */
object ConversationEdit {
    fun fork(snapshot: LocalSessionSnapshot, messageId: String, draft: String): LocalSessionSnapshot {
        val index = snapshot.chat.indexOfFirst { it.id == messageId }
        require(index >= 0 && snapshot.chat[index].role == "user") { "User message no longer available" }
        require(draft.isNotBlank()) { "Message cannot be empty" }
        val prefix = snapshot.chat.take(index)
        // UI messages and model/tool history have different counts and retention
        // limits. Never align by index or text (the same question can occur twice).
        // Rebuild only the visible conversational prefix; future tool results,
        // repair messages, research state and approvals must not leak backwards.
        val history = listOf(PersistedHistoryMessage("user",
            "CONVERSATION BRANCH: earlier messages are historical conversation, not current tool evidence. " +
                "Workspace actions from the old branch have NOT been undone. Verify live state before acting.")) +
            prefix.filter { it.role == "user" || it.role == "assistant" }
                .map { PersistedHistoryMessage(it.role, it.text) }
        return LocalSessionSnapshot(chat = prefix, history = history, inputDraft = draft.trim())
    }
}
