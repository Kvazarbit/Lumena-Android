package com.lumena.android.settings

import android.content.Context
import com.lumena.android.agent.core.AgentControlState
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.runtime.ActiveTool
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class PersistedChatMessage(val role: String, val text: String)
data class PersistedHistoryMessage(val role: String, val content: String)
data class PersistedPendingTool(
    val tool: String, val args: Map<String, String> = emptyMap(), val reason: String = "",
    val control: AgentControlState? = null, val history: List<PersistedHistoryMessage> = emptyList()
)
data class LocalSessionSnapshot(
    val chat: List<PersistedChatMessage> = emptyList(), val history: List<PersistedHistoryMessage> = emptyList(),
    val task: TaskState? = null, val pending: PersistedPendingTool? = null, val inputDraft: String = "",
    val control: AgentControlState? = null, val inFlight: ActiveTool? = null
)
/** Single writer: LocalAgentViewModel. No credentials or private model reasoning here. */
object LocalSessionStore {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(LocalSessionSnapshot::class.java)
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("lumena_local_session", Context.MODE_PRIVATE)
    fun load(context: Context): LocalSessionSnapshot = runCatching {
        prefs(context).getString("snapshot_json", null)?.let { adapter.fromJson(it) }
    }.getOrNull() ?: LocalSessionSnapshot()
    fun save(context: Context, snapshot: LocalSessionSnapshot) {
        val bounded = snapshot.copy(
            chat = snapshot.chat.takeLast(100).map { it.copy(text = it.text.take(16_000)) },
            history = snapshot.history.filterNot { it.role == "system" }.takeLast(48),
            pending = snapshot.pending?.copy(history = snapshot.pending.history.filterNot { it.role == "system" }.takeLast(48)),
            inputDraft = snapshot.inputDraft.take(8000)
        )
        prefs(context).edit().putString("snapshot_json", adapter.toJson(bounded)).apply()
    }
    fun clear(context: Context) { prefs(context).edit().remove("snapshot_json").apply() }
}
