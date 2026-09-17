package com.lumena.android.settings

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.UUID

/** A navigable context branch. Files in the workspace are NOT versioned here. */
data class HistoryBranch(
    val id: String,
    val topic: String,
    val taskTitle: String,
    val title: String,
    val parentBranchId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val session: LocalSessionSnapshot
)

data class HistoryTreeState(
    val activeBranchId: String,
    val branches: List<HistoryBranch> = emptyList()
)

/**
 * Private, app-local history of conversation/task contexts.
 *
 * Each branch owns a bounded LocalSessionSnapshot. Selecting a historical branch
 * restores conversational/agent context only; it never rolls back project files.
 */
object HistoryTreeStore {
    private const val FILE_NAME = "lumena_history_tree.json"
    private const val MAX_BRANCHES = 48
    private const val MAX_TITLE = 72

    private val lock = Any()
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(HistoryTreeState::class.java)

    fun load(context: Context): HistoryTreeState = synchronized(lock) {
        loadOrCreate(context.applicationContext)
    }

    fun activeBranch(context: Context): HistoryBranch? {
        val state = load(context)
        return state.branches.firstOrNull { it.id == state.activeBranchId }
    }

    /** Called by LocalSessionStore so the active branch follows live work automatically. */
    fun mirrorActiveSession(context: Context, snapshot: LocalSessionSnapshot) = synchronized(lock) {
        val app = context.applicationContext
        val state = loadOrCreate(app)
        val now = System.currentTimeMillis()
        val bounded = archive(snapshot)
        val updated = state.branches.map { branch ->
            if (branch.id != state.activeBranchId) branch
            else {
                val derived = deriveTaskTitle(bounded)
                branch.copy(
                    taskTitle = if (branch.taskTitle == "Conversation" && derived != null) derived else branch.taskTitle,
                    updatedAt = now,
                    session = bounded
                )
            }
        }
        saveInternal(app, state.copy(branches = updated))
    }

    /** Saves current context, then restores the selected historical context. */
    fun activate(context: Context, branchId: String): Boolean {
        val app = context.applicationContext
        val targetSnapshot: LocalSessionSnapshot = synchronized(lock) {
            var state = loadOrCreate(app)
            if (state.activeBranchId == branchId) return true

            val currentSnapshot = archive(LocalSessionStore.load(app))
            val now = System.currentTimeMillis()
            state = state.copy(
                branches = state.branches.map { branch ->
                    if (branch.id == state.activeBranchId) branch.copy(updatedAt = now, session = currentSnapshot)
                    else branch
                }
            )
            val target = state.branches.firstOrNull { it.id == branchId } ?: return false
            saveInternal(app, state.copy(activeBranchId = branchId))
            target.session
        }
        LocalSessionStore.save(app, targetSnapshot)
        return true
    }

    /** Fork current context, preserving chat/history but starting a fresh task. */
    fun forkActive(context: Context, title: String? = null): HistoryBranch {
        val app = context.applicationContext
        val created = synchronized(lock) {
            var state = loadOrCreate(app)
            val currentSnapshot = archive(LocalSessionStore.load(app))
            val now = System.currentTimeMillis()
            val parent = state.branches.first { it.id == state.activeBranchId }
            val savedParent = parent.copy(updatedAt = now, session = currentSnapshot)
            val cleanFork = currentSnapshot.copy(task = null, pending = null, inputDraft = "")
            val branchCount = state.branches.count { it.parentBranchId == parent.id } + 1
            val child = HistoryBranch(
                id = UUID.randomUUID().toString(),
                topic = parent.topic,
                taskTitle = parent.taskTitle,
                title = sanitizeTitle(title ?: "Branch $branchCount"),
                parentBranchId = parent.id,
                createdAt = now,
                updatedAt = now,
                session = cleanFork
            )
            val retained = retainBranches(
                state.branches.map { if (it.id == parent.id) savedParent else it } + child,
                child.id
            )
            state = HistoryTreeState(activeBranchId = child.id, branches = retained)
            saveInternal(app, state)
            child
        }
        LocalSessionStore.save(app, created.session)
        return created
    }

    /** Starts a blank task under the current topic without inheriting chat history. */
    fun createTask(context: Context, taskTitle: String): HistoryBranch {
        val app = context.applicationContext
        val created = synchronized(lock) {
            var state = loadOrCreate(app)
            val current = archive(LocalSessionStore.load(app))
            val active = state.branches.first { it.id == state.activeBranchId }
            val now = System.currentTimeMillis()
            state = state.copy(
                branches = state.branches.map { branch ->
                    if (branch.id == state.activeBranchId) branch.copy(updatedAt = now, session = current)
                    else branch
                }
            )
            val branch = HistoryBranch(
                id = UUID.randomUUID().toString(),
                topic = active.topic,
                taskTitle = sanitizeTitle(taskTitle.ifBlank { "New task" }),
                title = "Main",
                parentBranchId = null,
                createdAt = now,
                updatedAt = now,
                session = LocalSessionSnapshot()
            )
            val retained = retainBranches(state.branches + branch, branch.id)
            saveInternal(app, HistoryTreeState(branch.id, retained))
            branch
        }
        LocalSessionStore.save(app, created.session)
        return created
    }

    /** Starts a blank context under a new topic. */
    fun createTopic(context: Context, topicTitle: String): HistoryBranch {
        val app = context.applicationContext
        val created = synchronized(lock) {
            var state = loadOrCreate(app)
            val current = archive(LocalSessionStore.load(app))
            val now = System.currentTimeMillis()
            state = state.copy(
                branches = state.branches.map { branch ->
                    if (branch.id == state.activeBranchId) branch.copy(updatedAt = now, session = current)
                    else branch
                }
            )
            val branch = HistoryBranch(
                id = UUID.randomUUID().toString(),
                topic = sanitizeTitle(topicTitle.ifBlank { "New topic" }),
                taskTitle = "Conversation",
                title = "Main",
                parentBranchId = null,
                createdAt = now,
                updatedAt = now,
                session = LocalSessionSnapshot()
            )
            val retained = retainBranches(state.branches + branch, branch.id)
            saveInternal(app, HistoryTreeState(branch.id, retained))
            branch
        }
        LocalSessionStore.save(app, created.session)
        return created
    }

    fun renameBranch(context: Context, branchId: String, title: String) = synchronized(lock) {
        val app = context.applicationContext
        val state = loadOrCreate(app)
        val clean = sanitizeTitle(title)
        if (clean.isBlank()) return@synchronized
        saveInternal(
            app,
            state.copy(branches = state.branches.map {
                if (it.id == branchId) it.copy(title = clean, updatedAt = System.currentTimeMillis()) else it
            })
        )
    }

    fun renameTopic(context: Context, oldTopic: String, newTopic: String) = synchronized(lock) {
        val app = context.applicationContext
        val state = loadOrCreate(app)
        val clean = sanitizeTitle(newTopic)
        if (clean.isBlank()) return@synchronized
        saveInternal(app, state.copy(branches = state.branches.map {
            if (it.topic == oldTopic) it.copy(topic = clean) else it
        }))
    }

    private fun loadOrCreate(context: Context): HistoryTreeState {
        val file = file(context)
        val parsed = if (file.exists()) {
            runCatching { adapter.fromJson(file.readText()) }.getOrNull()
        } else null
        if (parsed != null && parsed.branches.any { it.id == parsed.activeBranchId }) return parsed

        val snapshot = archive(LocalSessionStore.load(context))
        val now = System.currentTimeMillis()
        val taskTitle = deriveTaskTitle(snapshot) ?: "Conversation"
        val initial = HistoryBranch(
            id = UUID.randomUUID().toString(),
            topic = deriveTopic(taskTitle),
            taskTitle = taskTitle,
            title = "Main",
            createdAt = now,
            updatedAt = now,
            session = snapshot
        )
        return HistoryTreeState(initial.id, listOf(initial)).also { saveInternal(context, it) }
    }

    private fun saveInternal(context: Context, state: HistoryTreeState) {
        val file = file(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(adapter.toJson(state))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun retainBranches(branches: List<HistoryBranch>, activeId: String): List<HistoryBranch> {
        if (branches.size <= MAX_BRANCHES) return branches
        val byId = branches.associateBy { it.id }
        val keep = linkedSetOf<String>()
        var cursor: String? = activeId
        while (cursor != null && keep.size < MAX_BRANCHES) {
            keep += cursor
            cursor = byId[cursor]?.parentBranchId
        }
        branches.sortedByDescending { it.updatedAt }.forEach {
            if (keep.size < MAX_BRANCHES) keep += it.id
        }
        return branches.filter { it.id in keep }
    }

    private fun archive(snapshot: LocalSessionSnapshot): LocalSessionSnapshot = snapshot.copy(
        chat = snapshot.chat.takeLast(60).map { it.copy(text = it.text.take(8_000)) },
        history = snapshot.history.takeLast(36).map { it.copy(content = it.content.take(8_000)) },
        inputDraft = snapshot.inputDraft.take(4_000)
    )

    private fun deriveTaskTitle(snapshot: LocalSessionSnapshot): String? {
        val goal = snapshot.task?.goal?.trim().orEmpty()
        if (goal.isNotBlank()) return sanitizeTitle(goal)
        val firstUser = snapshot.chat.firstOrNull { it.role == "user" }?.text?.trim().orEmpty()
        return firstUser.takeIf { it.isNotBlank() }?.let(::sanitizeTitle)
    }

    private fun deriveTopic(taskTitle: String): String {
        val words = taskTitle.split(Regex("\\s+")).filter { it.isNotBlank() }.take(4)
        return sanitizeTitle(words.joinToString(" ").ifBlank { "Local work" })
    }

    private fun sanitizeTitle(raw: String): String = raw
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .take(MAX_TITLE)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
