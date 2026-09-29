package com.lumena.android.settings

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object StateVaultLock { val monitor = Any() }
data class StateArchiveEntry(val name: String, val bytes: Int, val sha256: String)
data class StateArchiveManifest(val schema: Int = 1, val appVersion: Long, val createdAt: Long,
    val entries: List<StateArchiveEntry>)
data class VerifiedStateArchive(val manifest: StateArchiveManifest, val entries: Map<String, ByteArray>)

/** Exact allowlist: no arbitrary paths, secrets, executables or model files. */
object StateArchive {
    const val MAX_BYTES = 64 * 1024 * 1024
    val files = setOf("lumena_history_tree.json", "lumena_experience_memory.json",
        "lumena_constitution_genome.json", "lumena_coordinator_experience.json",
        "lumena_evidence_graph.json", "lumena_adaptive_web_research.json",
        "lumena_tinyjev_calibration.json", "lumena_laya_shadow_v1.json", "lumena_portable_kernel.json",
        "lumena_nervous_system_v1.json")
    val databases = setOf("lumena_context_genome.db", "lumena_landscape.db")
    val allowed = files.map { "files/$it" }.toSet() + databases.map { "databases/$it" } + "preferences.json"
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(StateArchiveManifest::class.java)
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size().toLong() + n <= MAX_BYTES) { "Копія перевищує 64 МБ" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    fun encode(entries: Map<String, ByteArray>, version: Long, now: Long): ByteArray {
        require(entries.keys.all { it in allowed } && "preferences.json" in entries)
        require(entries.values.sumOf { it.size.toLong() } <= MAX_BYTES)
        val manifest = StateArchiveManifest(appVersion = version, createdAt = now,
            entries = entries.toSortedMap().map { (name, data) -> StateArchiveEntry(name, data.size, hash(data)) })
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            put("manifest.json", adapter.toJson(manifest).toByteArray())
            entries.toSortedMap().forEach { (name, data) -> put(name, data) }
        }
        return out.toByteArray().also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray, currentVersion: Long): VerifiedStateArchive {
        require(bytes.size <= MAX_BYTES)
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                require(!e.isDirectory && (e.name in allowed || e.name == "manifest.json")) { "Невідомий файл: ${e.name}" }
                require(e.name !in entries) { "Повторний файл" }
                val data = readBounded(zip); total += data.size
                require(total <= MAX_BYTES) { "Розпакована копія перевищує 64 МБ" }
                entries[e.name] = data
            }
        }
        val manifest = requireNotNull(adapter.fromJson(requireNotNull(entries.remove("manifest.json")).toString(Charsets.UTF_8)))
        require(manifest.schema == 1 && manifest.appVersion <= currentVersion) { "Потрібна новіша версія Lumena" }
        require(manifest.entries.size == entries.size && manifest.entries.map { it.name }.toSet() == entries.keys)
        require("preferences.json" in entries)
        manifest.entries.forEach { e ->
            val data = requireNotNull(entries[e.name])
            require(e.bytes == data.size && e.sha256 == hash(data)) { "Пошкоджено ${e.name}" }
        }
        return VerifiedStateArchive(manifest, entries)
    }
}
