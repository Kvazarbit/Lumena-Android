package com.lumena.android.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lumena.android.agent.core.AgentControlState
import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.ollama.OllamaMessage
import com.lumena.android.ollama.PendingWorkflowTool
import com.lumena.android.ollama.ToolMode
import com.lumena.android.settings.LocalSessionStore
import com.lumena.android.settings.LumenaPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One serial writer. Every result is addressed by conversation ID AND task ID, never by selected tab. */
class HistoryRepository(context: Context) {
    private val app = context.applicationContext
    private val db = Db(app)
    private val mutex = Mutex()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val payloadAdapter = moshi.adapter(ConversationPayload::class.java)
    private val tasksAdapter = moshi.adapter<List<TaskMark>>(Types.newParameterizedType(List::class.java, TaskMark::class.java))

    private class Db(context: Context) : SQLiteOpenHelper(context,
        context.noBackupFilesDir.resolve("lumena-history-v1.db").absolutePath, null, 1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE topics(id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY, topic_id TEXT NOT NULL, title TEXT NOT NULL, parent_id TEXT, fork_task_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, model TEXT NOT NULL, tool_mode TEXT NOT NULL, task_index TEXT NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX conversations_topic_parent ON conversations(topic_id,parent_id,updated_at)")
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("No destructive history migration is permitted")
        }
    }

    private suspend fun <T> transaction(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val database = db.writableDatabase
            database.beginTransaction()
            try { block(database).also { database.setTransactionSuccessful() } }
            finally { database.endTransaction() }
        }
    }

    suspend fun initialize() = transaction { database ->
        if (meta(database, "migrated_v08") == null) {
            val topic = HistoryTopic(newHistoryId(), "Загальне")
            insertTopic(database, topic)
            val old = LocalSessionStore.load(app)
            val settings = LumenaPreferences.load(app)
            val chat = old.chat.map { ChatEntry(it.role, it.text) }
            val control = old.pending?.control ?: old.task?.let { AgentControlState(it) }
            val pending = old.pending?.let { p ->
                val gate = ToolGate.plan(PlannerDecision(ToolRequest(p.tool, p.args), p.reason))
                if (gate.allowed && control != null) PendingWorkflowTool(gate,
                    p.history.map { OllamaMessage(it.role, it.content) }, control) else null
            }
            val tasks = old.task?.let { task -> listOf(TaskMark(task.id, task.goal, task.status,
                chat.firstOrNull { it.role == "user" }?.id ?: chat.firstOrNull()?.id.orEmpty())) }.orEmpty()
            val payload = HistoryLogic.interrupted(ConversationPayload(chat,
                old.history.filterNot { it.role == "system" }.map { OllamaMessage(it.role, it.content) },
                control, pending, old.inputDraft, tasks))
            val conversation = Conversation(ConversationMeta(newHistoryId(), topic.id,
                chat.firstOrNull { it.role == "user" }?.text?.let(HistoryLogic::title) ?: "Нова розмова",
                model = settings.selectedModel, tasks = payload.tasks), payload)
            insertConversation(database, conversation)
            putMeta(database, "selected", conversation.meta.id)
            putMeta(database, "migrated_v08", "1")
            // Old preferences remain untouched as a migration backup.
        }
        // No automatic replay after process death. Pending unapproved calls remain pending.
        val ids = database.rawQuery("SELECT id FROM conversations", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        for (id in ids) {
            val c = read(database, id)
            val recovered = HistoryLogic.interrupted(c.payload)
            if (recovered != c.payload) writePayload(database, c, recovered)
        }
    }

    suspend fun catalog(): HistoryCatalog = transaction { database ->
        val topics = database.rawQuery("SELECT id,title,created_at FROM topics ORDER BY created_at", null).use { c ->
            buildList { while (c.moveToNext()) add(HistoryTopic(c.getString(0), c.getString(1), c.getLong(2))) }
        }
        val conversations = database.rawQuery("SELECT * FROM conversations ORDER BY updated_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(readMeta(c)) }
        }
        HistoryCatalog(topics, conversations, meta(database, "selected"))
    }

    suspend fun load(id: String): Conversation = transaction { read(it, id) }
    suspend fun select(id: String): Conversation = transaction { database ->
        read(database, id).also { putMeta(database, "selected", id) }
    }

    suspend fun createTopic(title: String): HistoryTopic = transaction { database ->
        HistoryTopic(newHistoryId(), HistoryLogic.title(title)).also { insertTopic(database, it) }
    }

    suspend fun newConversation(topicId: String, model: String): Conversation = transaction { database ->
        requireTopic(database, topicId)
        Conversation(ConversationMeta(newHistoryId(), topicId, model = model)).also {
            insertConversation(database, it); putMeta(database, "selected", it.meta.id)
        }
    }

    suspend fun fork(id: String, taskId: String): Conversation = transaction { database ->
        HistoryLogic.fork(read(database, id), taskId).also {
            insertConversation(database, it); putMeta(database, "selected", it.meta.id)
        }
    }

    suspend fun rename(id: String, title: String) = transaction { database ->
        read(database, id)
        database.update("conversations", ContentValues().apply { put("title", HistoryLogic.title(title)) }, "id=?", arrayOf(id))
    }

    suspend fun renameTopic(id: String, title: String) = transaction { database ->
        requireTopic(database, id)
        database.update("topics", ContentValues().apply { put("title", HistoryLogic.title(title)) }, "id=?", arrayOf(id))
    }

    /** Move the subtree together so parent links cannot point into an unrelated topic. */
    suspend fun move(id: String, topicId: String) = transaction { database ->
        requireTopic(database, topicId)
        read(database, id)
        val queue = java.util.ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!seen.add(node)) continue
            database.rawQuery("SELECT id FROM conversations WHERE parent_id=?", arrayOf(node)).use { c ->
                while (c.moveToNext()) queue.add(c.getString(0))
            }
            database.update("conversations", ContentValues().apply {
                put("topic_id", topicId)
                if (node == id) putNull("parent_id")
            }, "id=?", arrayOf(node))
        }
    }

    suspend fun setModel(id: String, model: String, mode: ToolMode) = transaction { database ->
        read(database, id)
        database.update("conversations", ContentValues().apply {
            put("model", model.trim()); put("tool_mode", mode.name)
        }, "id=?", arrayOf(id))
    }

    suspend fun updatePayload(id: String, expectedTaskId: String? = null,
        change: (ConversationPayload) -> ConversationPayload): Conversation? = transaction { database ->
        val c = read(database, id)
        if (expectedTaskId != null && c.payload.control?.task?.id != expectedTaskId) return@transaction null
        val next = change(c.payload)
        writePayload(database, c, next)
        read(database, id)
    }

    private fun writePayload(database: SQLiteDatabase, c: Conversation, payload: ConversationPayload) {
        val clean = payload.copy(history = payload.history.filterNot { it.role == "system" })
        val json = payloadAdapter.toJson(clean)
        require(json.toByteArray(Charsets.UTF_8).size <= 12 * 1024 * 1024) {
            "This conversation reached 12 MiB. History was not truncated; start a new conversation."
        }
        database.update("conversations", ContentValues().apply {
            put("payload", json); put("task_index", tasksAdapter.toJson(clean.tasks)); put("updated_at", System.currentTimeMillis())
            if (c.meta.title == "Нова розмова") clean.chat.firstOrNull { it.role == "user" }?.let { put("title", HistoryLogic.title(it.text)) }
        }, "id=?", arrayOf(c.meta.id))
    }

    private fun insertConversation(database: SQLiteDatabase, c: Conversation) {
        val count = database.rawQuery("SELECT COUNT(*) FROM conversations", null).use { it.moveToFirst(); it.getInt(0) }
        require(count < 200) { "The prototype supports 200 conversations; no existing history will be removed automatically." }
        database.insertOrThrow("conversations", null, ContentValues().apply {
            put("id", c.meta.id); put("topic_id", c.meta.topicId); put("title", c.meta.title)
            put("parent_id", c.meta.parentId); put("fork_task_id", c.meta.forkTaskId)
            put("created_at", c.meta.createdAt); put("updated_at", c.meta.updatedAt)
            put("model", c.meta.model); put("tool_mode", c.meta.toolMode.name)
            put("task_index", tasksAdapter.toJson(c.payload.tasks)); put("payload", payloadAdapter.toJson(c.payload))
        })
    }

    private fun read(database: SQLiteDatabase, id: String): Conversation =
        database.rawQuery("SELECT * FROM conversations WHERE id=?", arrayOf(id)).use { c ->
            require(c.moveToFirst()) { "Conversation not found" }
            val payload = payloadAdapter.fromJson(c.getString(c.getColumnIndexOrThrow("payload")))
                ?: error("Cannot decode history; stored data was not overwritten")
            Conversation(readMeta(c), payload)
        }

    private fun readMeta(c: Cursor): ConversationMeta {
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun number(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        return ConversationMeta(text("id"), text("topic_id"), text("title"), text("parent_id"), text("fork_task_id"),
            number("created_at"), number("updated_at"), text("model"), ToolMode.valueOf(text("tool_mode")),
            tasksAdapter.fromJson(text("task_index")).orEmpty())
    }
    private fun insertTopic(database: SQLiteDatabase, t: HistoryTopic) {
        database.insertOrThrow("topics", null, ContentValues().apply { put("id", t.id); put("title", t.title); put("created_at", t.createdAt) })
    }
    private fun requireTopic(database: SQLiteDatabase, id: String) {
        database.rawQuery("SELECT id FROM topics WHERE id=?", arrayOf(id)).use { require(it.moveToFirst()) { "Topic not found" } }
    }
    private fun meta(database: SQLiteDatabase, key: String): String? =
        database.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun putMeta(database: SQLiteDatabase, key: String, value: String) {
        database.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
