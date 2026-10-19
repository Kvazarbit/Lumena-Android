package com.lumena.android.collaboration

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import com.lumena.android.agent.core.ProjectWorkspacePolicy
import com.lumena.android.agent.core.ProjectWorkspaceState
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.settings.ProjectWorkspaceCodec
import com.lumena.android.settings.ProjectWorkspaceStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class ImportedContextInfo(val id: String, val projects: ProjectWorkspaceState,
    val receipts: Int, val unresolved: List<CollaborationEntry>, val provenance: String = "ARCHIVED_NOT_VERIFIED")
private data class ImportSeal(val id: String, val files: Map<String, String>)

/** Imported facts never enter the active ledger, delivery registry, approvals or learned local provenance. */
object ImportedContextArchive {
    private const val LIMIT = 512L * 1024 * 1024
    private val monitor = Any()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val sealAdapter = moshi.adapter(ImportSeal::class.java).failOnUnknown()
    private val mapAdapter = moshi.adapter<Map<String, String>>(Types.newParameterizedType(Map::class.java, String::class.java, String::class.java))
    private val manifestAdapter = moshi.adapter(CollaborationArchiveManifest::class.java).failOnUnknown()
    private val cached = mutableMapOf<String, Pair<String, List<ImportedContextInfo>>>()
    private fun root(c: Context) = c.applicationContext.noBackupFilesDir.resolve("imported-context-v1")
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(8192); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun allowed(name: String): Boolean = name in setOf("READ-ME.txt", "integrity.json", "archive/manifest.json",
        "active/${CollaborationLedger.DATABASE}", "workspace/${ProjectWorkspaceStore.FILE_NAME}", "workspace/lumena_project_history_v1.db") ||
        name.matches(Regex("archive/segment-[0-9]{4}-[a-f0-9]{12}\\.db")) ||
        name.matches(Regex("lab/(current|archive)-[a-f0-9-]{36}\\.json"))
    private fun split(name: String): Pair<String, String> {
        if (!name.startsWith("history/")) { require(allowed(name)); return "" to name }
        val pieces = name.split('/', limit = 3)
        require(pieces.size == 3 && pieces[1].matches(Regex("[a-f0-9]{64}")) && allowed(pieces[2]))
        return pieces[1] to pieces[2]
    }
    private fun database(file: File): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { error("Imported SQLite corruption") })
        try {
            db.rawQuery("PRAGMA quick_check(1)", null).use { require(it.moveToFirst() && it.getString(0) == "ok") }
            return db
        } catch (failure: Exception) { db.close(); throw failure }
    }
    private fun inspect(c: Context, dir: File, id: String): ImportedContextInfo {
        val workspace = ProjectWorkspaceCodec.decode(dir.resolve("workspace/${ProjectWorkspaceStore.FILE_NAME}").readText())
        val manifestFile = dir.resolve("archive/manifest.json")
        val manifest = if (manifestFile.exists()) requireNotNull(manifestAdapter.fromJson(manifestFile.readText())) else CollaborationArchiveManifest()
        require(manifest.version == 1 && manifest.pending == null && manifest.segments.size <= 32)
        require(manifest.segments.map { it.name }.distinct().size == manifest.segments.size)
        require(dir.resolve("archive").listFiles().orEmpty().filter { it.extension == "db" }.map { it.name }.toSet() == manifest.segments.map { it.name }.toSet())
        require(manifest.segments.sumOf { it.bytes } <= CollaborationArchive.MAX_BYTES)
        val ledger = CollaborationLedger(c)
        var tail = "GENESIS"; var count = 0
        val unknown = mutableListOf<CollaborationEntry>()
        val deliveries = hashSetOf<String>()
        fun read(file: File, base: String): Pair<List<CollaborationEntry>, String> {
            require(file.isFile && file.length() <= 64L * 1024 * 1024)
            val db = database(file)
            try {
                val entries = ledger.validateImportedDatabase(db, base)
                entries.filter { it.kind == "INTENT" }.forEach { require(deliveries.add(it.deliveryKey)) }
                require(deliveries.size <= CollaborationArchive.MAX_DELIVERIES)
                unknown += CollaborationProtocol.unresolved(entries)
                require(unknown.size <= 256) { "Too many unresolved imported effects; no partial import" }
                count += entries.size
                val last = db.rawQuery("SELECT digest FROM events ORDER BY seq DESC LIMIT 1", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else base
                }
                return entries to last
            } finally { db.close() }
        }
        manifest.segments.forEach { segment ->
            require(segment.name.matches(Regex("segment-[0-9]{4}-[a-f0-9]{12}\\.db")) && segment.previous == tail)
            val file = dir.resolve("archive/${segment.name}")
            require(file.length() == segment.bytes && hash(file) == segment.sha256)
            val (entries, next) = read(file, tail)
            require(entries.size == segment.events && next == segment.tail)
            tail = next
        }
        val active = dir.resolve("active/${CollaborationLedger.DATABASE}")
        if (active.exists()) read(active, tail)
        val history = dir.resolve("workspace/lumena_project_history_v1.db")
        if (history.exists()) database(history).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM records", null).use { require(it.moveToFirst()) }
        }
        return ImportedContextInfo(id, workspace, count, unknown)
    }
    fun importArchive(c: Context, input: InputStream): ImportedContextInfo = synchronized(monitor) {
        val base = root(c); check(base.isDirectory || base.mkdirs())
        val stage = base.resolve(".stage-${UUID.randomUUID()}"); check(stage.mkdir())
        try {
            val source = stage.resolve("source.zip")
            source.outputStream().use { output ->
                val buffer = ByteArray(8192); var size = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= LIMIT); output.write(buffer, 0, n) }
            }
            val id = hash(source)
            val target = base.resolve(id)
            if (target.exists()) return@synchronized infos(c).first { it.id == id }
            require(base.listFiles().orEmpty().count { it.name.matches(Regex("[a-f0-9]{64}")) } < 8)
            val occupied = base.walkTopDown().filter { it.isFile && it != source }.sumOf { it.length() }
            val content = stage.resolve("content"); check(content.mkdir())
            val names = hashSetOf<String>(); var total = 0L
            ZipInputStream(source.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name; split(name)
                    require(!entry.isDirectory && names.add(name) && names.size <= 400)
                    val file = content.resolve(name)
                    require(file.canonicalPath.startsWith(content.canonicalPath + File.separator))
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192); var size = 0L
                        val cap = when { name.endsWith(".db") -> 64L * 1024 * 1024
                            name.endsWith("lumena_projects_v1.json") -> ProjectWorkspaceCodec.MAX_BYTES.toLong()
                            name.startsWith("lab/") || "/lab/" in name -> 256_000L
                            else -> 2L * 1024 * 1024 }
                        while (true) { val n = zip.read(buffer); if (n < 0) break; size += n; total += n
                            require(size <= cap && total + occupied <= LIMIT); output.write(buffer, 0, n) }
                    }
                    zip.closeEntry()
                }
            }
            val integrity = content.resolve("integrity.json")
            if (integrity.exists()) {
                val expected = requireNotNull(mapAdapter.fromJson(integrity.readText()))
                require(expected.keys == names - "integrity.json")
                expected.forEach { (name, digest) -> require(hash(content.resolve(name)) == digest) }
            }
            val info = inspect(c, content, id)
            content.resolve("history").listFiles().orEmpty().forEach { dir -> inspect(c, dir, dir.name) }
            val hashes = names.associateWith { hash(content.resolve(it)) }
            val seal = stage.resolve("seal.json"); seal.writeText(sealAdapter.toJson(ImportSeal(id, hashes)))
            check(source.delete())
            // One directory publication after ALL validation. No live files were touched.
            check(stage.renameTo(target))
            cached.remove(base.absolutePath)
            info
        } finally {
            // Only the newly allocated private staging directory is eligible for cleanup.
            if (stage.exists() && stage.canonicalFile.parentFile == base.canonicalFile) stage.deleteRecursively()
        }
    }
    fun infos(c: Context): List<ImportedContextInfo> = synchronized(monitor) {
        val base = root(c)
        val folders = base.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }
        val stamp = folders.sortedBy { it.name }.joinToString { dir -> dir.walkTopDown().filter { it.isFile }
            .joinToString { it.relativeTo(base).path + ":" + it.length() + ":" + it.lastModified() } }
        cached[base.absolutePath]?.takeIf { it.first == stamp }?.let { return@synchronized it.second }
        val result = mutableListOf<ImportedContextInfo>()
        val bundles = mutableMapOf<String, Map<String, String>>()
        fun register(content: File, id: String) {
            val signature = content.walkTopDown().filter { it.isFile && allowed(it.relativeTo(content).invariantSeparatorsPath) && it.name != "integrity.json" }
                .associate { it.relativeTo(content).invariantSeparatorsPath to hash(it) }
            val prior = bundles.putIfAbsent(id, signature)
            require(prior == null || prior == signature) { "Conflicting imported archive identity" }
            if (prior == null) result += inspect(c, content, id)
        }
        for (dir in folders) {
            val seal = requireNotNull(sealAdapter.fromJson(dir.resolve("seal.json").readText()))
            require(seal.id == dir.name && seal.files.size <= 400)
            seal.files.forEach { (name, digest) -> split(name); require(hash(dir.resolve("content/$name")) == digest) }
            val content = dir.resolve("content")
            register(content, dir.name)
            content.resolve("history").listFiles().orEmpty().forEach { register(it, it.name) }
        }
        result.distinctBy { it.id }.also { cached[base.absolutePath] = stamp to it }
    }
    fun restoreProjects(c: Context, id: String): ProjectWorkspaceState {
        val info = infos(c).first { it.id == id }
        return ProjectWorkspaceStore.update(c) { current ->
            var next = current
            info.projects.projects.forEach { p ->
                val existing = current.projects.firstOrNull { it.id == p.id || it.root == p.root }
                if (existing != null) {
                    require(existing.id == p.id && existing.root == p.root && existing.goal == p.goal && existing.constraints == p.constraints &&
                        existing.verification == p.verification && existing.verificationPaths == p.verificationPaths) {
                        "Конфлікт із чинним проєктом; наявні дані не перезаписуються"
                    }
                } else {
                    val historical = p.copy(allowRemoteContext = false, restoredArchive = p.restoredArchive ?: id,
                        tasks = p.tasks.map { it.copy(summary = ("ARCHIVED_NOT_VERIFIED: " + it.summary).take(1200),
                            evidence = emptyList(), unresolvedEffects = emptyList(), handoffs = emptyList()) })
                    next = ProjectWorkspacePolicy.put(next, historical)
                }
            }
            next
        }
    }
    fun blocked(c: Context, request: ToolRequest): Boolean = synchronized(monitor) {
        if (!CollaborationProtocol.writer(request.tool)) return@synchronized false
        val prefs = c.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE)
        infos(c).any { info -> info.unresolved.any { !prefs.getBoolean("import_ack_${info.id}_${it.intentId}", false) } }
    }
    fun acknowledgeAfterInspection(c: Context, archiveId: String, intentId: String) = synchronized(monitor) {
        require(infos(c).any { it.id == archiveId && it.unresolved.any { e -> e.intentId == intentId } })
        check(c.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE).edit()
            .putBoolean("import_ack_${archiveId}_$intentId", true).commit())
    }
    /** Flatten historical bundles on every export; repeated reinstall does not add nesting. */
    fun exportFiles(c: Context): Map<String, File> = synchronized(monitor) {
        infos(c)
        val result = linkedMapOf<String, File>()
        root(c).listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) }.forEach { dir ->
            val content = dir.resolve("content")
            fun add(bundle: File, id: String) {
                bundle.walkTopDown().filter { it.isFile && allowed(it.relativeTo(bundle).invariantSeparatorsPath) && it.name != "integrity.json" }
                    .forEach { file ->
                        val name = "history/$id/" + file.relativeTo(bundle).invariantSeparatorsPath
                        result[name]?.let { require(hash(it) == hash(file)) } ?: run { result[name] = file }
                    }
            }
            add(content, dir.name)
            content.resolve("history").listFiles().orEmpty().forEach { add(it, it.name) }
        }
        result
    }
}
