package com.lumena.android.settings

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.lumena.android.agent.core.TaskStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Versioned external snapshots; internal stores remain the working copy. */
object StateVault {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioLock = Any()
    private val scheduleLock = Any()
    private var pendingSave: Job? = null
    @Volatile var companionBusy = false
    var restoring by mutableStateOf(false)
    @Volatile var startupError: String? = null
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val prefsAdapter = moshi.adapter(VaultPreferences::class.java)
    private val sessionAdapter = moshi.adapter(LocalSessionSnapshot::class.java)
    private val historyAdapter = moshi.adapter(HistoryTreeState::class.java)
    private val anyAdapter = moshi.adapter(Any::class.java)
    data class Value(val type: String, val value: String)
    data class VaultPreferences(val groups: Map<String, Map<String, Value>>)
    private val settingKeys = setOf("selected_model", "inference_backend", "compute_mode",
        "llama_context", "llama_batch", "llama_threads", "llama_response", "llama_extra_ram_mb")
    private val prefKeys = mapOf("lumena_settings" to settingKeys,
        "lumena_local_session" to setOf("snapshot_json"), "lumena_companion_handoff" to setOf("draft"))
    private fun prefs(c: Context) = c.getSharedPreferences("lumena_state_vault", Context.MODE_PRIVATE)
    fun folder(c: Context): String? = prefs(c).getString("folder", null)
    fun enabled(c: Context) = prefs(c).getBoolean("auto", true)
    fun status(c: Context): String = startupError ?: prefs(c).getString("status", "Папку ще не вибрано").orEmpty()
    fun setEnabled(c: Context, value: Boolean) { prefs(c).edit().putBoolean("auto", value).apply() }
    fun setFolder(c: Context, uri: Uri) {
        check(prefs(c).edit().putString("folder", uri.toString()).remove("content_hash").commit())
    }
    fun version(c: Context) = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
    fun requestSave(c: Context) {
        val app = c.applicationContext
        if (folder(app) == null || !enabled(app) || restoring || startupError != null) return
        synchronized(scheduleLock) {
            if (pendingSave?.isActive == true) return
            pendingSave = scope.launch {
                delay(5000)
                runCatching { save(app) }.onFailure { error(app, it) }
            }
        }
    }
    fun start(c: Context) {
        scope.launch {
            while (isActive) { delay(30_000); requestSave(c.applicationContext) }
        }
    }
    private fun error(c: Context, e: Throwable) {
        prefs(c).edit().putString("status", "Копію НЕ збережено: ${e.message}").apply()
    }
    private fun safeSession(s: LocalSessionSnapshot) = s.copy(pending = null,
        task = s.task?.let { t ->
            if (t.status in setOf(TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED)) t
            else t.copy(status = TaskStatus.CANCELLED, errors = (t.errors + "Відновлено з копії; автоматичне продовження вимкнено").takeLast(8))
        })
    private fun preferenceBytes(c: Context): ByteArray {
        val groups = prefKeys.mapValues { (group, keys) ->
            c.getSharedPreferences(group, Context.MODE_PRIVATE).all.filterKeys { it in keys }.mapValues { (_, v) ->
                when (v) {
                    is String -> Value("string", v)
                    is Int -> Value("int", v.toString())
                    is Long -> Value("long", v.toString())
                    is Boolean -> Value("boolean", v.toString())
                    is Float -> Value("float", v.toString())
                    else -> kotlin.error("Непідтримуваний тип налаштування")
                }
            }
        }
        return prefsAdapter.toJson(VaultPreferences(groups)).toByteArray()
    }
    private fun collect(c: Context): Map<String, ByteArray> = synchronized(StateVaultLock.monitor) {
        val out = linkedMapOf<String, ByteArray>()
        StateArchive.files.forEach { name ->
            val atomic = AtomicFile(File(c.filesDir, name))
            if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists())
                out["files/$name"] = atomic.openRead().use(StateArchive::readBounded)
        }
        StateArchive.databases.forEach { name ->
            val f = c.getDatabasePath(name)
            if (f.exists()) {
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { rows ->
                        check(rows.moveToFirst() && rows.getInt(0) == 0) { "База зайнята: $name" }
                    }
                    out["databases/$name"] = f.inputStream().use(StateArchive::readBounded)
                }
            }
        }
        out["preferences.json"] = preferenceBytes(c)
        out
    }
    private fun archive(c: Context) = StateArchive.encode(collect(c), version(c), System.currentTimeMillis())
    private fun root(uri: Uri) = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    fun save(c: Context): String = synchronized(ioLock) {
        check(!restoring && startupError == null) { "Відновлення ще не завершено" }
        val uri = Uri.parse(requireNotNull(folder(c)) { "Спочатку вибери папку" })
        val entries = collect(c)
        val contentHash = StateArchive.hash(entries.toSortedMap().map { (name, data) -> name + StateArchive.hash(data) }.joinToString("\n").toByteArray())
        if (prefs(c).getString("content_hash", null) == contentHash && list(c, uri).any { it.name == prefs(c).getString("saved_name", null) }) return@synchronized status(c)
        val bytes = StateArchive.encode(entries, version(c), System.currentTimeMillis())
        validateContents(c, StateArchive.decode(bytes, version(c)))
        val digest = StateArchive.hash(bytes)
        // No in-place overwrite: a failed provider write cannot destroy the last good copy.
        val name = "lumena-state-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.lumena"
        val doc = requireNotNull(DocumentsContract.createDocument(c.contentResolver, root(uri), "application/zip", name))
        try {
            requireNotNull(c.contentResolver.openOutputStream(doc, "w")).use { it.write(bytes) }
            val readback = requireNotNull(c.contentResolver.openInputStream(doc)).use(StateArchive::readBounded)
            check(StateArchive.hash(readback) == digest) { "Повторне читання копії не збігається" }
            StateArchive.decode(readback, version(c))
            val message = "Збережено й перевірено: $name (${bytes.size} байт)"
            prefs(c).edit().putString("status", message).putLong("saved_at", System.currentTimeMillis()).putString("content_hash", contentHash).putString("saved_name", name).apply()
            prune(c, uri)
            message
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, doc) }
            error(c, e); throw e
        }
    }
    data class Copy(val name: String, val uri: Uri)
    fun list(c: Context, tree: Uri): List<Copy> {
        val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return requireNotNull(c.contentResolver.query(child, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val name = rows.getString(1)
                    if (name.matches(Regex("lumena-state-[0-9]+-[a-f0-9]{8}\\.lumena")))
                        add(Copy(name, DocumentsContract.buildDocumentUriUsingTree(tree, rows.getString(0))))
                }
            }.sortedByDescending { it.name }
        }
    }
    private fun prune(c: Context, tree: Uri) {
        // Keep ten complete snapshots; never delete unrelated documents.
        runCatching { list(c, tree).drop(10).forEach { DocumentsContract.deleteDocument(c.contentResolver, it.uri) } }
    }
    fun stage(c: Context, copy: Copy) = synchronized(ioLock) {
        val bytes = requireNotNull(c.contentResolver.openInputStream(copy.uri)).use(StateArchive::readBounded)
        val verified = StateArchive.decode(bytes, version(c))
        validateContents(c, verified)
        writeAtomic(File(c.filesDir, "vault-pending.zip"), bytes)
        restoring = true
    }
    private fun validatePreferences(bytes: ByteArray): VaultPreferences {
        val data = requireNotNull(prefsAdapter.fromJson(bytes.toString(Charsets.UTF_8)))
        data.groups.forEach { (group, values) ->
            val allowed = requireNotNull(prefKeys[group])
            values.forEach { (key, v) ->
                require(key in allowed)
                val expected = if (group == "lumena_settings" && key.startsWith("llama_")) "int" else "string"
                require(v.type == expected) { "Неправильний тип $group/$key" }
                when (v.type) {
                    "int" -> v.value.toInt()
                    "long" -> v.value.toLong()
                    "float" -> require(v.value.toFloat().isFinite())
                    "boolean" -> require(v.value in setOf("true", "false"))
                    "string" -> Unit
                    else -> kotlin.error("Невідомий тип налаштування")
                }
                if (group == "lumena_local_session" && key == "snapshot_json")
                    requireNotNull(sessionAdapter.fromJson(v.value))
            }
        }
        return data
    }
    private fun validateContents(c: Context, state: VerifiedStateArchive) {
        validatePreferences(requireNotNull(state.entries["preferences.json"]))
        state.entries.forEach { (name, data) ->
            if (name.startsWith("files/")) {
                requireNotNull(anyAdapter.fromJson(data.toString(Charsets.UTF_8)))
                if (name.endsWith("lumena_history_tree.json")) requireNotNull(historyAdapter.fromJson(data.toString(Charsets.UTF_8)))
            }
            if (name.startsWith("databases/")) {
                val temp = File.createTempFile("vault-check-", ".db", c.cacheDir)
                try {
                    temp.writeBytes(data)
                    SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("PRAGMA integrity_check", null).use { row ->
                            check(row.moveToFirst() && row.getString(0) == "ok") { "Пошкоджена база" }
                        }
                    }
                } finally { temp.delete() }
            }
        }
    }
    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun apply(c: Context, state: VerifiedStateArchive, sanitize: Boolean) {
        StateArchive.files.forEach { AtomicFile(File(c.filesDir, it)).delete() }
        StateArchive.databases.forEach { c.deleteDatabase(it) }
        state.entries.forEach { (name, raw) ->
            var data = raw
            if (name.startsWith("files/")) {
                if (sanitize && name.endsWith("lumena_history_tree.json")) {
                    val history = requireNotNull(historyAdapter.fromJson(raw.toString(Charsets.UTF_8)))
                    data = historyAdapter.toJson(history.copy(branches = history.branches.map { it.copy(session = safeSession(it.session)) })).toByteArray()
                }
                writeAtomic(File(c.filesDir, name.removePrefix("files/")), data)
            } else if (name.startsWith("databases/")) writeAtomic(c.getDatabasePath(name.removePrefix("databases/")), data)
        }
        val values = validatePreferences(requireNotNull(state.entries["preferences.json"]))
        prefKeys.forEach { (group, keys) ->
            val editor = c.getSharedPreferences(group, Context.MODE_PRIVATE).edit()
            keys.forEach(editor::remove)
            values.groups[group].orEmpty().forEach { (key, v) ->
                val value = if (sanitize && group == "lumena_local_session" && key == "snapshot_json")
                    v.copy(value = sessionAdapter.toJson(safeSession(requireNotNull(sessionAdapter.fromJson(v.value))))) else v
                when (value.type) {
                    "string" -> editor.putString(key, value.value)
                    "int" -> editor.putInt(key, value.value.toInt())
                    "long" -> editor.putLong(key, value.value.toLong())
                    "boolean" -> editor.putBoolean(key, value.value.toBoolean())
                    "float" -> editor.putFloat(key, value.value.toFloat())
                }
            }
            check(editor.commit())
        }
        if (sanitize) {
            c.getSharedPreferences("lumena_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("companion_safe_auto", false).putBoolean("companion_auto_return", false).commit()
            AtomicFile(File(c.filesDir, "context_checkpoint.json")).delete()
        }
    }
    /** Called in Application.onCreate, before any Activity/service/store initialization. */
    fun restoreOnStartup(c: Context) = synchronized(StateVaultLock.monitor) {
        val pending = File(c.filesDir, "vault-pending.zip")
        val rollback = File(c.filesDir, "vault-rollback.zip")
        try {
            fun readAtomic(file: File): ByteArray? {
                if (!file.exists() && !File(file.path + ".bak").exists()) return null
                return AtomicFile(file).openRead().use(StateArchive::readBounded)
            }
            val rollbackBytes = readAtomic(rollback)
            val pendingBytes = if (rollbackBytes == null) readAtomic(pending) else null
            if (rollbackBytes == null && pendingBytes != null)
                validateContents(c, StateArchive.decode(pendingBytes, version(c)))
            val message = StateRestoreTransaction.run(pendingBytes, rollbackBytes,
                current = { archive(c) }, saveRollback = { writeAtomic(rollback, it) },
                apply = { bytes, sanitize -> apply(c, StateArchive.decode(bytes, version(c)), sanitize) },
                clearPending = { AtomicFile(pending).delete() },
                clearRollback = { AtomicFile(rollback).delete() })
            if (message != null) prefs(c).edit().putString("status", message).remove("content_hash").commit()

        } catch (e: Exception) {
            startupError = "Відновлення зупинено: ${e.message}. Дані для відкату збережено."
        }
    }
}
