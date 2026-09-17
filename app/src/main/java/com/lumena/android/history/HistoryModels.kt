package com.lumena.android.history

import com.lumena.android.agent.core.AgentControlState
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.ollama.OllamaMessage
import com.lumena.android.ollama.PendingWorkflowTool
import com.lumena.android.ollama.ToolMode
import java.util.UUID

fun newHistoryId(): String = UUID.randomUUID().toString()

data class HistoryTopic(val id: String, val title: String, val createdAt: Long = System.currentTimeMillis())
data class ChatEntry(
    val role: String,
    val text: String,
    val id: String = newHistoryId(),
    val createdAt: Long = System.currentTimeMillis()
)

/** Checkpoints are recorded only at a completed exchange, never in the middle of a tool call. */
data class TaskMark(
    val id: String,
    val goal: String,
    val status: TaskStatus,
    val anchorMessageId: String,
    val startedAt: Long = System.currentTimeMillis(),
    val chatEnd: Int? = null,
    val historyEnd: Int? = null,
    val inherited: Boolean = false
)

data class ConversationPayload(
    val chat: List<ChatEntry> = emptyList(),
    val history: List<OllamaMessage> = emptyList(),
    val control: AgentControlState? = null,
    val pending: PendingWorkflowTool? = null,
    val draft: String = "",
    val tasks: List<TaskMark> = emptyList(),
    val recoveryNotice: String? = null,
    val inheritedContext: Boolean = false,
    val executionUncertain: Boolean = false
)

data class ConversationMeta(
    val id: String,
    val topicId: String,
    val title: String = "Нова розмова",
    val parentId: String? = null,
    val forkTaskId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val model: String = "",
    val toolMode: ToolMode = ToolMode.AUTO,
    val tasks: List<TaskMark> = emptyList()
)

data class Conversation(val meta: ConversationMeta, val payload: ConversationPayload = ConversationPayload())
data class HistoryCatalog(
    val topics: List<HistoryTopic> = emptyList(),
    val conversations: List<ConversationMeta> = emptyList(),
    val selectedId: String? = null
)
data class TreeConversation(val meta: ConversationMeta, val depth: Int)

object HistoryLogic {
    val activeStatuses = setOf(TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING)

    fun title(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(72).ifBlank { "Нова розмова" }

    /** Only a new UUID may receive a branch. Live state, approvals and execution authority are not inherited. */
    fun fork(source: Conversation, taskId: String, newId: String = newHistoryId()): Conversation {
        require(newId != source.meta.id)
        val mark = source.payload.tasks.firstOrNull { it.id == taskId } ?: error("Checkpoint not found")
        val chatEnd = mark.chatEnd ?: error("This task has no completed checkpoint")
        val historyEnd = mark.historyEnd ?: error("This task has no completed checkpoint")
        require(mark.status == TaskStatus.DONE)
        require(chatEnd in 0..source.payload.chat.size && historyEnd in 0..source.payload.history.size)
        val prefix = source.payload.history.take(historyEnd).filterNot { it.role == "system" }
        require(prefix.lastOrNull()?.tool_calls.isNullOrEmpty()) { "Cannot branch an unresolved tool call" }
        val now = System.currentTimeMillis()
        val copiedTasks = source.payload.tasks.takeWhile { it.id != mark.id }.plus(mark).map { it.copy(inherited = true) }
        return Conversation(
            ConversationMeta(newId, source.meta.topicId, "Гілка · ${title(mark.goal)}", source.meta.id, mark.id,
                now, now, source.meta.model, source.meta.toolMode, copiedTasks),
            ConversationPayload(
                chat = source.payload.chat.take(chatEnd) + ChatEntry("status",
                    "Відгалуження від історичної точки. Файли workspace не скопійовано і не відновлено. Перед новими змінами їх потрібно перечитати."),
                history = prefix,
                tasks = copiedTasks,
                inheritedContext = true
            )
        )
    }

    /** Descending sibling activity, stable ancestry, with a cycle guard for corrupt/imported metadata. */
    fun tree(catalog: HistoryCatalog, topicId: String, query: String = "", collapsed: Set<String> = emptySet()): List<TreeConversation> {
        val nodes = catalog.conversations.filter { it.topicId == topicId }
        val byId = nodes.associateBy { it.id }
        val visible = if (query.isBlank()) byId.keys else {
            val found = nodes.filter { it.title.contains(query, true) || it.tasks.any { t -> t.goal.contains(query, true) } }
                .map { it.id }.toMutableSet()
            for (node in found.toList()) {
                var parent = byId[node]?.parentId
                val seen = mutableSetOf<String>()
                while (parent != null && seen.add(parent)) { found.add(parent); parent = byId[parent]?.parentId }
            }
            found
        }
        val out = mutableListOf<TreeConversation>()
        val seen = mutableSetOf<String>()
        fun visit(node: ConversationMeta, depth: Int) {
            if (node.id !in visible || !seen.add(node.id)) return
            out.add(TreeConversation(node, depth))
            if (query.isBlank() && node.id in collapsed) return
            nodes.filter { it.parentId == node.id }.sortedByDescending { it.updatedAt }.forEach { visit(it, depth + 1) }
        }
        nodes.filter { it.parentId == null || it.parentId !in byId }.sortedByDescending { it.updatedAt }.forEach { visit(it, 0) }
        if (collapsed.isEmpty() || query.isNotBlank()) nodes.filter { it.id !in seen }.forEach { visit(it, 0) }
        return out
    }

    fun breadcrumbs(catalog: HistoryCatalog, id: String): List<ConversationMeta> {
        val byId = catalog.conversations.associateBy { it.id }
        val chain = mutableListOf<ConversationMeta>()
        val seen = mutableSetOf<String>()
        var node = byId[id]
        while (node != null && seen.add(node.id)) { chain.add(node); node = node.parentId?.let(byId::get) }
        return chain.asReversed()
    }

    fun interrupted(payload: ConversationPayload): ConversationPayload {
        val control = payload.control ?: return payload
        if (control.task.status !in activeStatuses) return payload
        val uncertain = control.task.status == TaskStatus.EXECUTING || payload.executionUncertain
        val notice = if (uncertain)
            "Застосунок закрився під час інструмента. Результат невідомий: команда могла виконатись. Автоповтор заблоковано; спочатку перевірте workspace."
        else "Задача перервалася після закриття процесу. Контекст збережено; автоматичного запуску немає."
        return payload.copy(
            control = control.copy(task = control.task.copy(status = TaskStatus.FAILED)),
            tasks = payload.tasks.map { if (it.id == control.task.id) it.copy(status = TaskStatus.FAILED) else it },
            pending = null, recoveryNotice = notice, executionUncertain = uncertain,
            chat = payload.chat + ChatEntry("status", notice)
        )
    }
}
