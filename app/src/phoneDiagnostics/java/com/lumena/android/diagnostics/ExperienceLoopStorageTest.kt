package com.lumena.android.diagnostics

import android.app.Application
import android.content.Context
import com.lumena.android.agent.core.*
import com.lumena.android.agent.local.*
import com.lumena.android.collaboration.*
import com.lumena.android.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExperienceLoopStorageTest {
    private val c: Context get() = RuntimeEnvironment.getApplication()
    private val p = WorkspaceProject("p", "Demo", "demo", "Maintain", updatedAt = 1,
        allowRemoteContext = true, verificationPaths = listOf("tests"))
    private fun scope() = CollaborationRuntime.scope(p, "task", "session", "fixture", "LOCAL", "", "").copy(obligationId = "obligation")
    private fun clearLive() {
        c.deleteDatabase(CollaborationLedger.DATABASE)
        for (name in listOf("collaboration-segments-v1", "project-workspace-history-v1")) {
            val dir = c.noBackupFilesDir.resolve(name)
            require(dir.canonicalFile.parentFile == c.noBackupFilesDir.canonicalFile); dir.deleteRecursively()
        }
        c.filesDir.resolve(ProjectWorkspaceStore.FILE_NAME).delete()
        c.filesDir.resolve(ProjectWorkspaceStore.FILE_NAME + ".bak").delete()
    }
    @Before fun prepare() {
        clearLive()
        for (name in listOf("imported-context-v1", "repair-lab-history")) {
            val dir = c.noBackupFilesDir.resolve(name)
            require(dir.canonicalFile.parentFile == c.noBackupFilesDir.canonicalFile); dir.deleteRecursively()
        }
        c.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE).edit().clear().commit()
        ProjectWorkspaceStore.update(c) { ProjectWorkspaceState(projects = listOf(p)) }
    }
    private val read = ToolRequest("file.read", mapOf("path" to "demo/calc.py"))
    private fun executor(result: ToolResult) = object : ToolExecutor {
        override suspend fun execute(toolRequest: ToolRequest) = result
    }
    private suspend fun export(unknown: Boolean = false): ByteArray {
        val ledger = CollaborationLedger(c)
        ledger.execute(scope(), if (unknown) ToolRequest("file.write", mapOf("path" to "demo/calc.py", "content" to "x")) else read,
            executor(ToolResult(!unknown, exitCode = if (unknown) null else 0, stdout = "fixture", outcomeUnknown = unknown)), "delivery")
        return ByteArrayOutputStream().also(ledger::exportArchive).toByteArray()
    }
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val e = zip.nextEntry ?: break; files[e.name] = zip.readBytes(); zip.closeEntry() } }
        return files
    }
    private fun zip(files: Map<String, ByteArray>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> files.forEach { (name, body) -> zip.putNextEntry(ZipEntry(name)); zip.write(body); zip.closeEntry() } }
    }.toByteArray()
    @Test fun restoreAfterFreshInstallationPreservesHistoryWithoutLiveReceiptsOrPermissions() = runBlocking {
        val bytes = export(); clearLive()
        val info = ImportedContextArchive.importArchive(c, bytes.inputStream())
        assertFalse("IMPORTED_AS_LIVE", CollaborationLedger.exists(c))
        assertTrue(ProjectWorkspaceStore.load(c).projects.isEmpty())
        assertEquals(2, info.receipts)
        assertEquals("ARCHIVED_NOT_VERIFIED", info.provenance)
        val restored = ImportedContextArchive.restoreProjects(c, info.id).projects.single()
        assertEquals(p.goal, restored.goal)
        assertEquals(listOf("tests"), restored.verificationPaths)
        assertFalse(restored.allowRemoteContext)
        assertFalse(CollaborationRuntime.remoteAllowed(c, restored))
        assertEquals(info.id, restored.restoredArchive)
        assertTrue(ProjectWorkspacePolicy.capsule(restored).text.contains("ARCHIVED_NOT_VERIFIED"))
        assertFalse(CollaborationLedger.exists(c))
    }
    @Test fun repeatedImportAndColdInspectionAreIdempotent() = runBlocking {
        val bytes = export(); clearLive()
        val first = ImportedContextArchive.importArchive(c, bytes.inputStream())
        val second = ImportedContextArchive.importArchive(c, bytes.inputStream())
        assertEquals(first, second)
        assertEquals(1, ImportedContextArchive.infos(c).size)
        assertEquals(first.id, ImportedContextArchive.restoreProjects(c, first.id).projects.single().restoredArchive)
        assertEquals(1, ImportedContextArchive.restoreProjects(c, first.id).projects.size)
    }
    @Test fun changedArchiveBytesAndTraversalAreRejectedBeforeAnyPublication() = runBlocking {
        val files = unzip(export()).toMutableMap(); clearLive()
        val workspace = "workspace/" + ProjectWorkspaceStore.FILE_NAME
        files[workspace] = String(files.getValue(workspace)).replace("Maintain", "Malicious").toByteArray()
        assertThrows(IllegalArgumentException::class.java) { ImportedContextArchive.importArchive(c, zip(files).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { ImportedContextArchive.importArchive(c, zip(mapOf("../escape" to byteArrayOf(1))).inputStream()) }
        assertTrue(ImportedContextArchive.infos(c).isEmpty())
        assertTrue(ProjectWorkspaceStore.load(c).projects.isEmpty())
        assertFalse(CollaborationLedger.exists(c))
    }
    @Test fun legacyBackupWithoutIntegrityStillRequiresAnIntactReceiptChain() = runBlocking {
        val files = unzip(export()).toMutableMap(); files.remove("integrity.json"); clearLive()
        val info = ImportedContextArchive.importArchive(c, zip(files).inputStream())
        assertEquals(2, info.receipts)
        assertEquals("ARCHIVED_NOT_VERIFIED", info.provenance)
    }
    @Test fun oversizedSqliteReceiptIsRejectedBeforeDecodingEvenInLegacyZip() = runBlocking {
        val files = unzip(export()).toMutableMap(); files.remove("integrity.json"); clearLive()
        val fixture = c.cacheDir.resolve("oversized-import-fixture.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(fixture, null).use { db ->
            db.execSQL("CREATE TABLE events (seq INTEGER PRIMARY KEY,id TEXT,body TEXT,previous TEXT,digest TEXT)")
            db.execSQL("INSERT INTO events VALUES(1,'id',?,'GENESIS','digest')", arrayOf("x".repeat(40_000)))
        }
        files["active/${CollaborationLedger.DATABASE}"] = fixture.readBytes()
        val failure = assertThrows(IllegalArgumentException::class.java) { ImportedContextArchive.importArchive(c, zip(files).inputStream()) }
        assertTrue(failure.message.orEmpty().contains("receipt bounds"))
        assertTrue(ImportedContextArchive.infos(c).isEmpty())
        assertFalse(CollaborationLedger.exists(c))
    }
    @Test fun importedUnknownBlocksWritersWithCaptureOffAndOwnerAckNeverChangesHistory() = runBlocking {
        val bytes = export(unknown = true); clearLive()
        val info = ImportedContextArchive.importArchive(c, bytes.inputStream())
        CollaborationRuntime.enable(c, false)
        var calls = 0
        val delegate = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult { calls++; return ToolResult(true, stdout = "read") }
        }
        val wrapped = CollaborationRuntime.executor(c, scope(), delegate)
        val write = ToolRequest("file.write", mapOf("path" to "demo/x.py", "content" to "x"))
        assertFalse("IMPORTED_UNKNOWN_EXECUTED", wrapped.execute(write).ok)
        assertEquals(0, calls)
        assertTrue(wrapped.execute(read).ok)
        ImportedContextArchive.acknowledgeAfterInspection(c, info.id, info.unresolved.single().intentId)
        assertTrue(wrapped.execute(write).ok)
        assertEquals(2, calls)
        assertEquals(1, ImportedContextArchive.infos(c).single().unresolved.size)
        assertFalse(CollaborationLedger.exists(c))
    }
    @Test fun conflictingProjectCannotBeOverwrittenOrPartiallyRestored() = runBlocking {
        val bytes = export(); clearLive()
        val info = ImportedContextArchive.importArchive(c, bytes.inputStream())
        val current = ProjectWorkspaceStore.update(c) { ProjectWorkspaceState(projects = listOf(p.copy(goal = "New owner goal"))) }
        assertThrows(IllegalArgumentException::class.java) { ImportedContextArchive.restoreProjects(c, info.id) }
        assertEquals(current, ProjectWorkspaceStore.load(c))
    }
    @Test fun reexportFlattensHistoryAndIncludesIntegrity() = runBlocking {
        val bytes = export(); clearLive()
        val info = ImportedContextArchive.importArchive(c, bytes.inputStream())
        ImportedContextArchive.restoreProjects(c, info.id)
        val next = ByteArrayOutputStream().also { CollaborationLedger(c).exportArchive(it) }.toByteArray()
        val files = unzip(next)
        assertTrue(files.containsKey("integrity.json"))
        assertTrue(files.keys.any { it.startsWith("history/${info.id}/active/") })
        assertFalse(files.keys.any { it.count { ch -> ch == '/' } > 5 })
        clearLive()
        val again = ImportedContextArchive.importArchive(c, next.inputStream())
        assertEquals(2, ImportedContextArchive.infos(c).size)
        assertEquals(0, again.receipts)
    }
    @Test fun importedDamageBlocksNewWritersInsteadOfDiscardingTheArchive() = runBlocking {
        val bytes = export(); clearLive()
        val info = ImportedContextArchive.importArchive(c, bytes.inputStream())
        val file = c.noBackupFilesDir.resolve("imported-context-v1/${info.id}/content/workspace/${ProjectWorkspaceStore.FILE_NAME}")
        file.appendText("damage")
        var calls = 0
        val delegate = object : ToolExecutor { override suspend fun execute(toolRequest: ToolRequest): ToolResult { calls++; return ToolResult(true) } }
        assertFalse(CollaborationRuntime.executor(c, scope(), delegate).execute(ToolRequest("file.write", mapOf("path" to "demo/x", "content" to "x"))).ok)
        assertEquals(0, calls)
        assertTrue(file.exists())
    }
    @Test fun repairCaseSpansArchivedSegmentAndKeepsStructuredProofAfterColdOpen() = runBlocking {
        val proof = VerificationEvidence("a".repeat(64), "b".repeat(64), "c".repeat(64), 1, true, true, "d".repeat(64))
        val ledger = CollaborationLedger(c)
        val tests = ToolRequest("python.tests", mapOf("cwd" to "demo"))
        ledger.execute(scope(), tests, executor(ToolResult(false, exitCode = 1, stdout = "fail", verification = proof)), "fail") { SourceObservation("a".repeat(64)) }
        ledger.archiveClosedSegment()
        ledger.execute(scope(), ToolRequest("file.patch", mapOf("path" to "demo/calc.py", "old" to "x - 1", "new" to "x + 1")),
            executor(ToolResult(true, exitCode = 0)), "patch") { SourceObservation("e".repeat(64)) }
        ledger.execute(scope(), tests, executor(ToolResult(true, exitCode = 0, stdout = "pass", verification = proof.copy(failureSignature = null))), "pass") { SourceObservation("e".repeat(64)) }
        val snapshot = CollaborationLedger(c).repairSnapshot()
        assertEquals(1, snapshot.cases.size)
        assertTrue(snapshot.cases.single().summary.contains("x - 1 → x + 1"))
        assertEquals("SOLVED", snapshot.episodes.single().status)
    }
    @Test fun ownerVerificationBoundaryOverridesModelArgumentsEvenWithoutCapture() = runBlocking {
        var boundary = ""
        val delegate = object : ToolExecutor { override suspend fun execute(toolRequest: ToolRequest): ToolResult {
            boundary = toolRequest.args["verification_paths"].orEmpty(); return ToolResult(true)
        } }
        val result = CollaborationRuntime.executor(c, scope(), delegate).execute(ToolRequest("python.tests", mapOf("cwd" to "demo", "verification_paths" to "[]")))
        assertTrue(result.ok)
        assertEquals("[\"tests\"]", boundary)
    }
    @Test fun ggufDigestCacheCannotBreakProjectArchiveExport() = runBlocking {
        c.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE).edit()
            .putString("repair_lab_gguf", "legacy-key=not-json")
            .putString("gguf_digest_cache_v1", "new-key=not-json")
            .putString("repair_lab_unrelated_pref", "not-json").commit()
        val exported = export()
        assertTrue(unzip(exported).containsKey("integrity.json"))
        assertFalse(unzip(exported).keys.any { it.contains("gguf") })
    }
    @Test fun labCountersSurviveColdLoadAndExportButNeverReactivateOnImport() = runBlocking {
        val id = java.util.UUID.randomUUID().toString()
        val cfg = RepairLabConfig(id, RepairCasePolicy.scope(p), p.id, "local", "a".repeat(64), "build", "phone", emptyList())
        val state = RepairLabState(cfg, listOf(RepairLabRow("episode", "task", true, emptyList())))
        val adapter = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build().adapter(RepairLabState::class.java)
        c.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE).edit().putString("repair_lab_p", adapter.toJson(state)).commit()
        RepairLabStore.doneProposal(c, TaskState("task", p.id, "Check"), false)
        assertEquals(1, RepairLabStore.load(c, p.id)!!.rows.single().rejectedDone)
        val bytes = export()
        assertTrue(unzip(bytes).containsKey("lab/current-$id.json"))
        clearLive(); c.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE).edit().clear().commit()
        ImportedContextArchive.importArchive(c, bytes.inputStream())
        assertNull(RepairLabStore.load(c, p.id))
        assertFalse(CollaborationRuntime.enabled(c))
    }
    @Test fun localDigestFrozenLibraryAndLiveAssignmentWorkTogetherAndChangedModelStopsTrial() = runBlocking {
        repairCaseSpansArchivedSegmentAndKeepsStructuredProofAfterColdOpen()
        CollaborationRuntime.enable(c, true)
        val digest = java.util.concurrent.atomic.AtomicReference("a".repeat(64))
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) try { server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                val body = """{"models":[{"name":"fixture:latest","digest":"${digest.get()}"}]}""".toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                }
            } } catch (failure: java.net.SocketException) { if (!server.isClosed) throw failure }
        }
        try {
            val route = CollaborationRuntime.route("ollama", "http://127.0.0.1:${server.localPort}", "fixture")
            CollaborationRuntime.attestLocal(c, route, true)
            RepairLabStore.start(c, p, route)
            assertEquals(1, RepairLabStore.load(c, p.id)!!.config.library.size)
            val proof = VerificationEvidence("a".repeat(64), "b".repeat(64), "c".repeat(64), 1, true, true, "d".repeat(64))
            CollaborationLedger(c).execute(scope().copy(taskId = "retry", obligationId = "next"), ToolRequest("python.tests", mapOf("cwd" to "demo")),
                executor(ToolResult(false, exitCode = 1, stdout = "fail", verification = proof)), "new-failure") { SourceObservation("a".repeat(64)) }
            val task = TaskState("retry", p.id, "Fix", projectRoot = "demo")
            val advice = RepairLabStore.advice(c, task, route)
            val assigned = RepairLabStore.load(c, p.id)!!.rows.single()
            assertEquals(assigned.exposed, advice.isNotEmpty())
            RepairLabStore.modelRequest(c, task, advice.joinToString("\n"))
            assertEquals(if (assigned.exposed) 1 else 0, RepairLabStore.load(c, p.id)!!.rows.single().requestsWithCases)
            val ledger = CollaborationLedger(c)
            val next = scope().copy(taskId = "retry", obligationId = "next")
            ledger.execute(next, ToolRequest("file.patch", mapOf("path" to "demo/calc.py", "old" to "x - 1", "new" to "x + 1")),
                executor(ToolResult(true, exitCode = 0)), "next-patch") { SourceObservation("e".repeat(64)) }
            ledger.execute(next, ToolRequest("python.tests", mapOf("cwd" to "demo")),
                executor(ToolResult(true, exitCode = 0, verification = proof.copy(failureSignature = null))), "next-pass") { SourceObservation("e".repeat(64)) }
            assertEquals(2, ledger.repairSnapshot().cases.size)
            ledger.execute(next, ToolRequest("python.tests", mapOf("cwd" to "demo")),
                executor(ToolResult(false, exitCode = 1, verification = proof)), "third-failure") { SourceObservation("a".repeat(64)) }
            RepairLabStore.advice(c, task, route)
            assertEquals("FROZEN_LIBRARY_CHANGED", 1, RepairLabStore.load(c, p.id)!!.config.library.size)
            digest.set("e".repeat(64))
            assertTrue(RepairLabStore.advice(c, task, route).isEmpty())
            val stopped = RepairLabStore.load(c, p.id)!!
            assertFalse(stopped.config.active)
            assertEquals(2, stopped.rows.size)
            assertFalse(stopped.rows.first().invalidConditions)
            assertTrue(stopped.rows.last().invalidConditions)
            assertEquals(assigned.exposed, stopped.rows.first().exposed)
        } finally { server.close(); worker.join(1000) }
    }
}
