package com.lumena.android.collaboration

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest

internal data class CollaborationSegment(val name: String, val sha256: String, val previous: String,
    val tail: String, val events: Int, val bytes: Long)
internal data class CollaborationArchiveManifest(val version: Int = 1,
    val segments: List<CollaborationSegment> = emptyList(), val pending: CollaborationSegment? = null)
internal data class CollaborationArchiveIndex(val tail: String = "GENESIS", val deliveries: Set<String> = emptySet(),
    val recent: List<CollaborationEntry> = emptyList(), val segments: Int = 0)

/** Private, closed SQLite segments. A durable prepare record precedes the rename; no import API. */
internal class CollaborationArchive(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "collaboration-segments-v1")
    private val manifest = AtomicFile(File(directory, "manifest.json"))
    private val database = context.applicationContext.getDatabasePath(CollaborationLedger.DATABASE)
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(CollaborationArchiveManifest::class.java).failOnUnknown()
    private val sha = Regex("[a-f0-9]{64}")
    private fun read(): CollaborationArchiveManifest {
        if (!manifest.baseFile.exists() && !File(manifest.baseFile.path + ".bak").exists()) {
            require(directory.listFiles().orEmpty().none { it.name.endsWith(".db") }) { "Archive manifest missing; segments retained" }
            return CollaborationArchiveManifest()
        }
        val raw = manifest.openRead().use { it.readBytes() }
        require(raw.size <= 64 * 1024)
        val state = requireNotNull(adapter.fromJson(raw.toString(Charsets.UTF_8)))
        require(state.version == 1 && state.segments.size <= MAX_SEGMENTS)
        var previous = "GENESIS"
        for (s in state.segments + listOfNotNull(state.pending)) {
            require(s.name.matches(Regex("segment-[0-9]{4}-[a-f0-9]{12}\\.db")) && s.sha256.matches(sha) &&
                s.tail.matches(sha) && s.previous == previous && s.events in 1..CollaborationProtocol.MAX_EVENTS && s.bytes in 1..MAX_BYTES)
            previous = s.tail
        }
        require(state.segments.map { it.name }.distinct().size == state.segments.size)
        return state
    }
    private fun save(state: CollaborationArchiveManifest) {
        check(directory.isDirectory || directory.mkdirs())
        val out = manifest.startWrite()
        try { out.write(adapter.toJson(state).toByteArray(Charsets.UTF_8)); manifest.finishWrite(out) }
        catch (failure: Exception) { manifest.failWrite(out); throw failure }
        cache.remove(directory.absolutePath)
    }
    fun recover() {
        val state = read()
        val p = state.pending ?: return
        val target = File(directory, p.name)
        if (!target.exists()) {
            require(database.isFile && hash(database) == p.sha256) { "Prepared archive source changed; data retained" }
            check(database.renameTo(target)) { "Archive rename failed; source retained" }
        } else {
            require(!database.exists()) { "Ambiguous prepared archive; both files retained" }
        }
        require(hash(target) == p.sha256) { "Prepared archive corrupted; data retained" }
        save(state.copy(segments = state.segments + p, pending = null))
    }
    fun index(reader: (SQLiteDatabase, String) -> List<CollaborationEntry>): CollaborationArchiveIndex {
        val state = read()
        require(state.pending == null)
        val stamp = adapter.toJson(state) + state.segments.joinToString { s ->
            val f = File(directory, s.name)
            "${f.length()}:${f.lastModified()}"
        }
        cache[directory.absolutePath]?.takeIf { it.first == stamp }?.let { return it.second }
        val deliveries = mutableSetOf<String>()
        var recent = emptyList<CollaborationEntry>()
        var tail = "GENESIS"
        for (s in state.segments) {
            val f = File(directory, s.name)
            require(f.isFile && f.length() == s.bytes && hash(f) == s.sha256) { "Archive integrity error; no reset" }
            val db = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
                DatabaseErrorHandler { throw SQLiteException("Archived SQLite corruption; file retained") })
            val entries = try {
                reader(db, tail).also {
                    val actual = db.rawQuery("SELECT digest FROM events ORDER BY seq DESC LIMIT 1", null).use { c ->
                        require(c.moveToFirst()); c.getString(0)
                    }
                    require(actual == s.tail)
                }
            } finally { db.close() }
            require(entries.size == s.events)
            entries.filter { it.kind == "INTENT" }.forEach { require(deliveries.add(it.deliveryKey)) }
            require(deliveries.size <= MAX_DELIVERIES) { "Archive identity budget reached" }
            val combined = recent + entries
            val keep = combined.filter { it.kind == "INTENT" }.takeLast(256).map { it.id }.toSet()
            recent = combined.filter { it.intentId in keep }
            tail = s.tail
        }
        return CollaborationArchiveIndex(tail, deliveries, recent, state.segments.size)
            .also { cache[directory.absolutePath] = stamp to it }
    }
    fun visitSegments(reader: (SQLiteDatabase, String) -> List<CollaborationEntry>, consume: (CollaborationEntry) -> Unit) {
        index(reader) // Validate manifest, immutable file hashes and chains before exposing records.
        read().segments.forEach { segment ->
            val file = File(directory, segment.name)
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
                DatabaseErrorHandler { throw SQLiteException("Archived SQLite corruption; file retained") })
            try { reader(db, segment.previous).forEach(consume) } finally { db.close() }
        }
    }
    fun rotate(tail: String, events: Int, afterPrepare: () -> Unit = {}) {
        val state = read()
        require(state.pending == null && events > 0 && state.segments.size < MAX_SEGMENTS)
        require(state.segments.sumOf { it.bytes } + database.length() <= MAX_BYTES) { "Private archive budget reached; data retained. Export a copy; capture remains stopped." }
        val digest = hash(database)
        val segment = CollaborationSegment("segment-%04d-%s.db".format(state.segments.size + 1, digest.take(12)), digest,
            state.segments.lastOrNull()?.tail ?: "GENESIS", tail, events, database.length())
        require(!File(directory, segment.name).exists())
        save(state.copy(pending = segment))
        afterPrepare()
        recover()
    }
    fun export(output: java.io.OutputStream, projects: String, history: File?,
               imported: Map<String, File> = emptyMap(), lab: Map<String, String> = emptyMap()) {
        recover()
        val state = read()
        require(state.segments.size + imported.size + lab.size + 6 <= 400) { "Archive file limit reached; original data retained" }
        require(state.segments.sumOf { it.bytes } + database.length() + projects.toByteArray().size +
            (history?.length() ?: 0) + imported.values.sumOf { it.length() } + lab.values.sumOf { it.toByteArray().size.toLong() } <= 510L * 1024 * 1024) {
            "Archive size limit reached; original data retained"
        }
        java.util.zip.ZipOutputStream(output).use { zip ->
            val hashes = linkedMapOf<String, String>()
            fun text(name: String, body: String) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                hashes[name] = CollaborationProtocol.hash(body)
            }
            fun add(file: File, name: String) {
                if (!file.exists()) return
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); zip.write(buffer, 0, n) }
                }
                zip.closeEntry()
                hashes[name] = digest.digest().joinToString("") { "%02x".format(it) }
            }
            add(manifest.baseFile, "archive/manifest.json")
            state.segments.forEach { add(File(directory, it.name), "archive/" + it.name) }
            add(database, "active/" + CollaborationLedger.DATABASE)
            text("workspace/lumena_projects_v1.json", projects)
            history?.let { add(it, "workspace/" + it.name) }
            imported.forEach { (name, file) -> add(file, name) }
            lab.forEach { (name, body) -> text(name, body) }
            text("READ-ME.txt", "Private historical Lumena context. ARCHIVED_NOT_VERIFIED after import. " +
                "Not approvals, current goal proof, executable code or model weights. Inspect actual project files after restoring.\n")
            val type = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
            val integrity = Moshi.Builder().build().adapter<Map<String, String>>(type).toJson(hashes)
            text("integrity.json", integrity)
        }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val MAX_SEGMENTS = 32
        const val MAX_BYTES = 256L * 1024 * 1024
        const val MAX_DELIVERIES = 100_000
        // Only immutable app-private archive views are cached. Active events are revalidated every call.
        private val cache = mutableMapOf<String, Pair<String, CollaborationArchiveIndex>>()
    }
}
