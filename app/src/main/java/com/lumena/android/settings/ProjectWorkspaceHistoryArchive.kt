package com.lumena.android.settings

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.lumena.android.agent.core.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** Historical workspace records evicted from the working capsule. Never a competing current state. */
object ProjectWorkspaceHistoryArchive {
    const val DATABASE = "lumena_project_history_v1.db"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private class Helper(c: Context) : SQLiteOpenHelper(c, c.noBackupFilesDir.resolve(DATABASE).absolutePath, null, 1,
        DatabaseErrorHandler { throw SQLiteException("Project history corruption; data retained") }) {
        override fun onConfigure(db: SQLiteDatabase) { db.disableWriteAheadLogging(); db.execSQL("PRAGMA synchronous=FULL"); db.setMaximumSize(64L * 1024 * 1024) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL)")
            db.execSQL("CREATE TRIGGER history_no_update BEFORE UPDATE ON records BEGIN SELECT RAISE(ABORT,'immutable history'); END")
            db.execSQL("CREATE TRIGGER history_no_delete BEFORE DELETE ON records BEGIN SELECT RAISE(ABORT,'immutable history'); END")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported project history migration; data retained") }
    }
    fun preserveEvictions(c: Context, old: ProjectWorkspaceState, next: ProjectWorkspaceState) {
        val rows = mutableListOf<Triple<String, String, String>>()
        fun <T : Any> add(project: String, kind: String, value: T, type: Class<T>) {
            rows += Triple(project, kind, moshi.adapter(type).toJson(value))
        }
        for (p in old.projects) {
            val n = next.projects.firstOrNull { it.id == p.id }
            if (n == null) { add(p.id, "project", p, WorkspaceProject::class.java); continue }
            for (t in p.tasks) {
                val nt = n.tasks.firstOrNull { it.id == t.id }
                if (nt == null) add(p.id, "task", t, ProjectWorkRecord::class.java)
                else {
                    // Store only evicted facts; copying a whole 48-receipt task each turn wastes phone storage.
                    t.handoffs.filter { it !in nt.handoffs }.forEach {
                        add(p.id, "handoff:" + t.id, it, ProjectHandoffRecord::class.java)
                    }
                    t.evidence.filter { e -> nt.evidence.none { it.id == e.id } }.forEach {
                        add(p.id, "evidence:" + t.id, it, ActionEvidence::class.java)
                    }
                }
            }
            p.decisions.filter { d -> n.decisions.none { it.id == d.id } }
                .forEach { add(p.id, "decision", it, ProjectDecisionRecord::class.java) }
        }
        if (rows.isEmpty()) return
        val helper = Helper(c.applicationContext)
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                rows.forEach { (project, kind, body) ->
                    require(body.toByteArray(Charsets.UTF_8).size <= ProjectWorkspaceCodec.MAX_BYTES)
                    val id = ProjectWorkspacePolicy.digest("$project\n$kind\n$body")
                    // Idempotent prepare: a crash before the active AtomicFile commit leaves history intact.
                    db.insertWithOnConflict("records", null, ContentValues().apply {
                        put("id", id); put("project_id", project); put("kind", kind); put("body", body)
                    }, SQLiteDatabase.CONFLICT_IGNORE).also { result ->
                        if (result == -1L) db.rawQuery("SELECT body FROM records WHERE id=?", arrayOf(id)).use {
                            require(it.moveToFirst() && it.getString(0) == body) { "Project history could not be saved" }
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } finally { helper.close() }
    }
    fun validatedFile(c: Context): java.io.File? {
        val file = c.noBackupFilesDir.resolve(DATABASE)
        if (!file.exists()) return null
        val helper = Helper(c.applicationContext)
        try {
            helper.readableDatabase.rawQuery("SELECT id,project_id,kind,body FROM records", null).use { rows ->
                while (rows.moveToNext()) require(rows.getString(0) == ProjectWorkspacePolicy.digest(
                    rows.getString(1) + "\n" + rows.getString(2) + "\n" + rows.getString(3))) { "Historical workspace integrity error" }
            }
        } finally { helper.close() }
        return file
    }
}
