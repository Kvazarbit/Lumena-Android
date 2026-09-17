package com.lumena.android.settings

import android.content.Context
import com.lumena.android.agent.core.TaskState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class PersistedChatMessage(
    val role: String,
    val text: String
)

data class PersistedHistoryMessage(
    val role: String,
    val content: String
)

data class PersistedPendingTool(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val reason: String = "",
    val taskPlan: List<String> = emptyList(),
    val history: List<PersistedHistoryMessage> = emptyList()
)

data class LocalSessionSnapshot(
    val chat: List<PersistedChatMessage> = emptyList(),
    val history: List<PersistedHistoryMessage> = emptyList(),
    val task: TaskState? = null,
    val pending: PersistedPendingTool? = null,
    val inputDraft: String = ""
)

/**
 * Persists the Local tab independently from Compose lifecycle.
 *
 * This deliberately stores only bounded conversational/task state. Connection secrets
 * remain in [LumenaPreferences]. The current system prompt is not persisted so a new
 * app version can always restore history under the latest safety/tool instructions.
 */
object LocalSessionStore {
    private const val FILE = "lumena_local_session"
    private const val KEY_SNAPSHOT = "snapshot_json"
    private const val MAX_CHAT_MESSAGES = 80
    private const val MAX_HISTORY_MESSAGES = 48
    private const val MAX_MESSAGE_CHARS = 16_000
    private const val MAX_DRAFT_CHARS = 8_000

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(LocalSessionSnapshot::class.java)

    fun load(context: Context): LocalSessionSnapshot {
        val raw = prefs(context).getString(KEY_SNAPSHOT, null) ?: return LocalSessionSnapshot()
        return runCatching { adapter.fromJson(raw) }
            .getOrNull()
            ?: LocalSessionSnapshot()
    }

    fun save(context: Context, snapshot: LocalSessionSnapshot) {
        val bounded = snapshot.copy(
            chat = snapshot.chat.takeLast(MAX_CHAT_MESSAGES).map {
                it.copy(text = it.text.take(MAX_MESSAGE_CHARS))
            },
            history = snapshot.history
                .filterNot { it.role == "system" }
                .takeLast(MAX_HISTORY_MESSAGES)
                .map { it.copy(content = it.content.take(MAX_MESSAGE_CHARS)) },
            pending = snapshot.pending?.copy(
                taskPlan = snapshot.pending.taskPlan.take(6).map { it.take(160) },
                history = snapshot.pending.history
                    .filterNot { it.role == "system" }
                    .takeLast(MAX_HISTORY_MESSAGES)
                    .map { it.copy(content = it.content.take(MAX_MESSAGE_CHARS)) }
            ),
            inputDraft = snapshot.inputDraft.take(MAX_DRAFT_CHARS)
        )
        prefs(context).edit().putString(KEY_SNAPSHOT, adapter.toJson(bounded)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SNAPSHOT).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
