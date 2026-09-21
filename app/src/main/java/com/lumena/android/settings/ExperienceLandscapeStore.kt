package com.lumena.android.settings

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import com.lumena.android.agent.core.TaskIntentRouter
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class LandscapeSession(val scope: String, val label: String)
data class ConstitutionVersion(val revision: Long, val at: Long, val reason: String, val rules: List<LandscapeRule>)
data class LandscapeSnapshot(val state: LandscapeState, val view: LandscapeView, val versions: List<ConstitutionVersion>)

object ExperienceLandscapeStore {
    private val lock = Any()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val stateAdapter = moshi.adapter(LandscapeState::class.java)
    private val rulesAdapter = moshi.adapter<List<LandscapeRule>>(
        Types.newParameterizedType(List::class.java, LandscapeRule::class.java))
    @Volatile private var dbHelper: Helper? = null

    fun session(context: Context, backend: String, model: String, mode: String, endpoint: String): LandscapeSession {
        val tuning = LumenaPreferences.loadTuning(context)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val identity = listOf(backend, model, mode, endpoint, Build.FINGERPRINT, version.toString(), tuning.toString())
            .joinToString("") { "${it.length}:$it" }
        return LandscapeSession(ExperienceLandscapePolicy.hash(identity),
            "$backend · ${Build.MODEL} · $mode · model#${ExperienceLandscapePolicy.hash(model).take(6)}")
    }

    fun record(context: Context, session: LandscapeSession, task: TaskState, request: ToolRequest,
               result: ToolResult, elapsedMs: Long, genomeEventId: String) = synchronized(lock) {
        val tool = ToolRegistry.canonicalize(request.tool)
        val requestId = request.requestId ?: genomeEventId
        val target = listOf("path", "script", "cwd", "model", "url", "query")
            .firstNotNullOfOrNull { key -> request.args[key]?.let { "$key=$it" } }.orEmpty()
        val observation = LandscapeObservation(
            id = ExperienceLandscapePolicy.hash("${task.id}:$requestId"), taskId = task.id,
            requestId = requestId, scope = session.scope, scopeLabel = session.label,
            intent = TaskIntentRouter.route(task.goal).intent.name,
            operation = ExperienceLandscapePolicy.operation(tool, request.args), tool = tool,
            target = target.replace(Regex("[\\r\\n\\t]"), " ").take(160),
            ok = result.ok && (result.exitCode == null || result.exitCode == 0) &&
                (result.tool == null || ToolRegistry.canonicalize(result.tool) == tool),
            elapsedMs = elapsedMs, at = System.currentTimeMillis(), genomeEventId = genomeEventId,
            detail = (result.error ?: result.stderr.ifBlank { result.stdout }).take(300)
        )
        mutate(context, "Новий підтверджений результат") { state -> ExperienceLandscapePolicy.record(state, observation) }
    }

    fun advice(context: Context, session: LandscapeSession, task: TaskState): List<String> = synchronized(lock) {
        val state = mutate(context, "Переоцінка актуальності") { it }
        ExperienceLandscapePolicy.advice(state, session.scope, TaskIntentRouter.route(task.goal).intent.name,
            System.currentTimeMillis())
    }

    fun snapshot(context: Context): LandscapeSnapshot = synchronized(lock) {
        val state = mutate(context, "Переоцінка актуальності") { it }
        val versions = mutableListOf<ConstitutionVersion>()
        db(context).query("versions", arrayOf("revision", "at", "reason", "rules"),
            null, null, null, null, "revision DESC", "32").use { rows ->
            while (rows.moveToNext()) versions += ConstitutionVersion(rows.getLong(0), rows.getLong(1), rows.getString(2),
                rulesAdapter.fromJson(rows.getString(3)) ?: emptyList())
        }
        LandscapeSnapshot(state, ExperienceLandscapePolicy.view(state, System.currentTimeMillis()), versions)
    }

    fun toggleRule(context: Context, id: String) = synchronized(lock) {
        mutate(context, "Ручна зміна правила") { state ->
            val known = ExperienceLandscapePolicy.view(state, System.currentTimeMillis()).rules.any { it.id == id }
            require(known) { "Unknown rule" }
            state.copy(disabledRules = if (id in state.disabledRules) state.disabledRules - id else state.disabledRules + id,
                revision = state.revision + 1)
        }
    }

    fun restore(context: Context, revision: Long) = synchronized(lock) {
        val ids = db(context).query("versions", arrayOf("rules"), "revision=?", arrayOf(revision.toString()),
            null, null, null).use { rows ->
            require(rows.moveToFirst()) { "Version no longer retained" }
            rulesAdapter.fromJson(rows.getString(0)).orEmpty().map { it.id }.toSet()
        }
        mutate(context, "Відновлено v$revision; автоматичне підвищення призупинено") {
            ExperienceLandscapePolicy.restore(it, ids, System.currentTimeMillis())
        }
    }

    fun setAutoPromote(context: Context, enabled: Boolean) = synchronized(lock) {
        mutate(context, if (enabled) "Автоматичне підвищення увімкнено" else "Автоматичне підвищення призупинено") {
            it.copy(autoPromote = enabled, revision = it.revision + 1)
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        val database = db(context)
        database.beginTransaction()
        try {
            database.delete("state", null, null)
            database.delete("versions", null, null)
            database.execSQL("INSERT INTO versions VALUES (0,?,?,'[]')", arrayOf(System.currentTimeMillis(), "Початковий порожній набір"))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    private fun mutate(context: Context, reason: String, update: (LandscapeState) -> LandscapeState): LandscapeState {
        val database = db(context)
        database.beginTransaction()
        try {
            val old = database.query("state", arrayOf("payload"), "id=1", null, null, null, null).use { rows ->
                if (rows.moveToFirst()) requireNotNull(stateAdapter.fromJson(rows.getString(0))) else LandscapeState()
            }
            val now = System.currentTimeMillis()
            val next = ExperienceLandscapePolicy.reconcile(update(old), now)
            if (next != old) {
                database.execSQL("INSERT OR REPLACE INTO state(id,payload) VALUES (1,?)", arrayOf(stateAdapter.toJson(next)))
                if (next.revision != old.revision) {
                    val active = ExperienceLandscapePolicy.view(next, now).rules.filter { it.id in next.activeRules }
                    database.insertOrThrow("versions", null, ContentValues().apply {
                        put("revision", next.revision); put("at", now); put("reason", reason)
                        put("rules", rulesAdapter.toJson(active))
                    })
                    database.execSQL("DELETE FROM versions WHERE revision NOT IN (SELECT revision FROM versions ORDER BY revision DESC LIMIT 32)")
                }
            }
            database.setTransactionSuccessful()
            return next
        } finally { database.endTransaction() }
    }

    private fun db(context: Context): SQLiteDatabase {
        val helper = dbHelper ?: Helper(context.applicationContext).also { dbHelper = it }
        return helper.writableDatabase
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "lumena_landscape.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY ON CONFLICT REPLACE, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE versions (revision INTEGER PRIMARY KEY, at INTEGER NOT NULL, reason TEXT NOT NULL, rules TEXT NOT NULL)")
            db.execSQL("INSERT INTO versions VALUES (0,?,?,'[]')", arrayOf(System.currentTimeMillis(), "Початковий порожній набір"))
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
