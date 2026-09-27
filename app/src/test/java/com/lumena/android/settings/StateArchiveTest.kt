package com.lumena.android.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StateArchiveTest {
    private val payload = mapOf("preferences.json" to "{\"groups\":{}}".toByteArray(),
        "files/lumena_constitution_genome.json" to "{\"text\":\"Досвід: успіх і помилка\"}".toByteArray(),
        "files/lumena_history_tree.json" to "{\"chat\":\"Акваріум\"}".toByteArray(),
        "databases/lumena_context_genome.db" to ByteArray(512) { it.toByte() })
    @Test fun roundTripPreservesEveryByteIncludingUnicodeAndDatabase() {
        val actual = StateArchive.decode(StateArchive.encode(payload, 33, 123), 33)
        assertEquals(payload.keys, actual.entries.keys)
        payload.forEach { (name, bytes) -> assertArrayEquals(bytes, actual.entries[name]) }
        assertEquals(123L, actual.manifest.createdAt)
    }
    @Test fun rejectsFutureVersionAndTruncatedArchive() {
        val bytes = StateArchive.encode(payload, 34, 123)
        assertFails { StateArchive.decode(bytes, 33) }
        assertFails { StateArchive.decode(bytes.copyOf(20), 34) }
    }
    @Test fun rejectsTraversalAndUnknownSecretEntries() {
        for (name in listOf("../secret", "/data/secret", "files/../../secret", "shared_prefs/lumena_settings.xml")) {
            assertFails { StateArchive.encode(payload + (name to byteArrayOf(1)), 33, 1) }
            assertFails { StateArchive.decode(zip(mapOf(name to byteArrayOf(1))), 33) }
        }
    }
    @Test fun rejectsChangedEntryEvenWhenZipCrcIsValid() {
        val good = StateArchive.encode(payload, 33, 1)
        val entries = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(good.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        entries["files/lumena_constitution_genome.json"] = "corrupted".toByteArray()
        assertFails { StateArchive.decode(zip(entries), 33) }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray()
    }
    private fun assertFails(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: Exception) { /* expected */ }
    }
}
