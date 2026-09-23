package com.lumena.android.settings

import android.content.Context
import com.lumena.android.agent.core.AgentControlState
import com.lumena.android.agent.core.TaskState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class PersistedChatImage(
    val title: String = "",
    val thumbnailUrl: String,
    val sourcePage: String = "",
    val source: String = ""
)

data class PersistedChatMessage(
    val role: String,
    val text: String,
    val images: List<PersistedChatImage> = emptyList()
)

data class PersistedHistoryMessage(
    val role: String,
    val content: String
)

data class PersistedPendingTool(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val requestId: String? = null,
    val reason: String = "",
    val control: AgentControlState? = null,
    val history: List<PersistedHistoryMessage> = emptyList(),
    val images: List<PersistedChatImage> = emptyList()
)

data class LocalSessionSnapshot(
    val chat: List<PersistedChatMessage> = emptyList(),
    val history: List<PersistedHistoryMessage> = emptyList(),
    val task: TaskState? = null,
    val pending: PersistedPendingTool? = null,
    val inputDraft: String = "",
    val researchGoal: String? = null
)

/**
 * Persists bounded Local-tab state independently from Compose lifecycle.
 * Connection secrets remain in LumenaPreferences. The static system prompt is
 * deliberately not persisted so restored sessions use the newest agent rules.
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
            chat = snapshot.chat.takeLast(MAX_CHAT_MESSAGES).map { message ->
                message.copy(
                    text = message.text.take(MAX_MESSAGE_CHARS),
                    images = message.images.take(8).map { image ->
                        image.copy(
                            title = image.title.take(300),
                            thumbnailUrl = image.thumbnailUrl.take(2_000),
                            sourcePage = image.sourcePage.take(2_000),
                            source = image.source.take(120)
                        )
                    }
                )
            },
            history = snapshot.history
                .filterNot { it.role == "system" }
                .takeLast(MAX_HISTORY_MESSAGES)
                .map { it.copy(content = it.content.take(MAX_MESSAGE_CHARS)) },
            pending = snapshot.pending?.copy(
                requestId = snapshot.pending.requestId?.take(220),
                control = snapshot.pending.control?.copy(
                    plan = snapshot.pending.control.plan.take(6).map { it.take(180) },
                    task = snapshot.pending.control.task.copy(
                        goal = snapshot.pending.control.task.goal.take(8_000),
                        lastResult = snapshot.pending.control.task.lastResult?.take(4_000),
                        errors = snapshot.pending.control.task.errors.takeLast(8).map { it.take(2_000) }
                    )
                ),
                history = snapshot.pending.history
                    .filterNot { it.role == "system" }
                    .takeLast(MAX_HISTORY_MESSAGES)
                    .map { it.copy(content = it.content.take(MAX_MESSAGE_CHARS)) },
                images = snapshot.pending.images.take(8).map { image ->
                    image.copy(
                        title = image.title.take(300),
                        thumbnailUrl = image.thumbnailUrl.take(2_000),
                        sourcePage = image.sourcePage.take(2_000),
                        source = image.source.take(120)
                    )
                }
            ),
            inputDraft = snapshot.inputDraft.take(MAX_DRAFT_CHARS),
            researchGoal = snapshot.researchGoal
                ?.take(MAX_DRAFT_CHARS)
        )
        prefs(context).edit().putString(KEY_SNAPSHOT, adapter.toJson(bounded)).apply()
        // History mirrors only bounded, app-private context. Workspace files are never copied/rolled back.
        HistoryTreeStore.mirrorActiveSession(context, bounded)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SNAPSHOT).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
