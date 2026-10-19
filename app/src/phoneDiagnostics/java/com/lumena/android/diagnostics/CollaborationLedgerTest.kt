package com.lumena.android.diagnostics

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.agent.local.*
import com.lumena.android.collaboration.*
import com.lumena.android.companion.CompanionTaskGrantStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CollaborationLedgerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val project = WorkspaceProject("aquarium", "Aquarium", "aquarium", "Improve logic", updatedAt = 1)
    private fun scope(p: WorkspaceProject? = project) = CollaborationRuntime.scope(p, "task-1", "session-1", "fixture-model", "COMPANION", "http://127.0.0.1:8765", "synthetic")
    private val request = ToolRequest("python.run", mapOf("script" to "test_aquarium_brain_baseline_v1.py", "approved_sha256" to "e".repeat(64)))
    private class Executor(val result: ToolResult = ToolResult(true, exitCode = 0, stdout = "Ran 8 tests in 0.009s\nOK")) : ToolExecutor {
        var calls = 0
        override suspend fun execute(toolRequest: ToolRequest): ToolResult { calls++; return result }
    }
    @Before fun prepare() {
        context.deleteDatabase(CollaborationLedger.DATABASE)
        val fixtureArchive = context.noBackupFilesDir.resolve("collaboration-segments-v1")
        require(fixtureArchive.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        fixtureArchive.deleteRecursively()
        CompanionTaskGrantStore.clear(context)
        context.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun archivedReceiptsKeepChainAndDeliveryIdentityAcrossColdOpen() = runBlocking {
        val executor = Executor()
        val ledger = CollaborationLedger(context)
        assertTrue(ledger.execute(scope(), request, executor, "archived-delivery").ok)
        val before = ledger.entries()
        ledger.archiveClosedSegment()
        assertEquals(1, CollaborationLedger(context).archivedSegments())
        assertEquals(before, CollaborationLedger(context).entries())
        assertFalse("ARCHIVED_REPLAY_EXECUTED", CollaborationLedger(context).execute(scope(), request, executor, "archived-delivery").ok)
        assertEquals(1, executor.calls)
        assertTrue(CollaborationLedger(context).execute(scope(), request, executor, "new-delivery").ok)
        assertEquals(before + CollaborationLedger(context).entries().takeLast(2), CollaborationLedger(context).entries())
        CollaborationLedger(context).archiveClosedSegment()
        assertEquals(2, CollaborationLedger(context).archivedSegments())
        assertEquals(4, CollaborationLedger(context).entries().size)
    }
    @Test fun preparedArchiveRecoversAfterProcessInterruptionWithoutRepeatingTransport() = runBlocking {
        val executor = Executor()
        val ledger = CollaborationLedger(context)
        ledger.execute(scope(), request, executor, "before-crash")
        val before = ledger.entries()
        assertThrows(IllegalStateException::class.java) {
            ledger.archiveClosedSegmentAfterPrepare { error("simulated process interruption") }
        }
        assertEquals(before, CollaborationLedger(context).entries())
        assertEquals(1, CollaborationLedger(context).archivedSegments())
        assertFalse(CollaborationLedger(context).execute(scope(), request, executor, "before-crash").ok)
        assertEquals(1, executor.calls)
    }
    @Test fun unresolvedWriterCannotBeArchivedAndExportPreservesUnknown() = runBlocking {
        val executor = Executor(ToolResult(false, outcomeUnknown = true))
        val ledger = CollaborationLedger(context)
        ledger.execute(scope(), request, executor, "unknown")
        assertThrows(IllegalArgumentException::class.java) { ledger.archiveClosedSegment() }
        assertEquals(1, CollaborationProtocol.unresolved(ledger.entries()).size)
        val bytes = java.io.ByteArrayOutputStream()
        ledger.exportArchive(bytes)
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names += entry.name; zip.closeEntry() }
        }
        assertTrue(names.contains("active/" + CollaborationLedger.DATABASE))
        assertEquals(1, CollaborationProtocol.unresolved(CollaborationLedger(context).entries()).size)
    }
    @Test fun corruptArchivedSegmentBlocksTransportAndKeepsOriginalBytes() = runBlocking {
        val executor = Executor()
        val ledger = CollaborationLedger(context)
        ledger.execute(scope(), request, executor, "one")
        ledger.archiveClosedSegment()
        val segment = context.noBackupFilesDir.resolve("collaboration-segments-v1").listFiles()!!.single { it.extension == "db" }
        segment.appendBytes(byteArrayOf(42))
        val damaged = segment.readBytes()
        assertThrows(IllegalArgumentException::class.java) { CollaborationLedger(context).entries() }
        assertFalse("CORRUPT_ARCHIVE_EXECUTED", CollaborationLedger(context).execute(scope(), request, executor, "two").ok)
        assertArrayEquals(damaged, segment.readBytes())
        assertEquals(1, executor.calls)
    }
    @Test fun tenColdStartsRetainImmutableReceiptsAndExactDecodedOutputDigest() = runBlocking {
        val executor = Executor()
        repeat(10) { n ->
            assertTrue(CollaborationLedger(context).execute(scope(), request, executor, "delivery-$n").ok)
            val all = CollaborationLedger(context).entries()
            assertEquals((n + 1) * 2, all.size)
            assertEquals(CollaborationProtocol.hash(executor.result.stdout), all.last().stdoutUtf8Sha256)
            assertEquals("e".repeat(64), all.last().boundInputSha256)
        }
        assertEquals(10, executor.calls)
    }
    @Test fun redeliveryDoesNotExecuteOrInventANewReceipt() = runBlocking {
        val executor = Executor()
        val read = ToolRequest("file.read", mapOf("path" to "aquarium/test.py"))
        assertTrue(CollaborationLedger(context).execute(scope(), read, executor, "same-delivery").ok)
        assertFalse("REPLAY_EXECUTED", CollaborationLedger(context).execute(scope(), read, executor, "same-delivery").ok)
        assertEquals("REPLAY_EXECUTED", 1, executor.calls)
        assertEquals(2, CollaborationLedger(context).entries().size)
    }
    @Test fun alteredRedeliveryConflictsRatherThanOverwritingHistory() = runBlocking {
        val executor = Executor()
        CollaborationLedger(context).execute(scope(), request, executor, "one")
        val result = CollaborationLedger(context).execute(scope(), request.copy(args = mapOf("script" to "other.py")), executor, "one")
        assertFalse(result.ok)
        assertEquals(1, executor.calls)
        assertEquals("test_aquarium_brain_baseline_v1.py", CollaborationLedger(context).entries().first().targets["script"])
        assertFalse(CollaborationLedger(context).execute(scope(project.copy(id = "btc", root = "btc")), request, executor, "one").ok)
        assertEquals(1, executor.calls)
    }
    @Test fun legitimateCompanionRetestHasNewDurableDeliveryButOldDeliveryStillCannotReplay() = runBlocking {
        val executor = Executor()
        val first = CompanionTaskGrantStore.begin(context, "same-test-command").toString()
        assertTrue(CollaborationLedger(context).execute(scope(), request, executor, first).ok)
        CompanionTaskGrantStore.finish(context, "same-test-command", null)
        // A real project loop patches code before asking for the same test command again.
        CompanionTaskGrantStore.begin(context, "intervening-patch")
        CompanionTaskGrantStore.finish(context, "intervening-patch", null)
        val second = CompanionTaskGrantStore.begin(context, "same-test-command").toString()
        assertNotEquals("NEW_EXECUTION_CONFLATED_WITH_REDELIVERY", first, second)
        assertEquals(second, org.json.JSONObject(context.noBackupFilesDir.resolve("companion-consent.json").readText())
            .getString("pending_delivery"))
        assertTrue(CollaborationLedger(context).execute(scope(), request, executor, second).ok)
        assertFalse(CollaborationLedger(context).execute(scope(), request, executor, first).ok)
        assertEquals(2, executor.calls)
        val recovered = CompanionTaskGrantStore.recover(context)
        assertEquals("same-test-command", recovered.handled)
        assertNull(recovered.pending)
        assertNull(recovered.pendingDelivery)
    }
    @Test fun unknownWriterBlocksOtherProjectAndConnectionChangeButAllowsReading() = runBlocking {
        val first = Executor(ToolResult(false, outcomeUnknown = true))
        CollaborationLedger(context).execute(scope(), request, first, "unknown")
        val other = Executor()
        val otherScope = scope(project.copy(id = "btc", root = "btc"))
        assertFalse("UNKNOWN_WRITER_EXECUTED", CollaborationLedger(context).execute(otherScope, request, other, "new").ok)
        assertEquals(0, other.calls)
        assertTrue(CollaborationLedger(context).execute(otherScope, ToolRequest("file.read"), other, "inspect").ok)
        assertEquals(1, other.calls)
        assertEquals(scope().bridgeBinding, CollaborationRuntime.scope(project, "other", "other", "m", "LOCAL", "http://localhost:9999", "different").bridgeBinding)
    }
    @Test fun processDeathBoundaryLeavesIntentAndNeverRetriesMutation() = runBlocking {
        val cancelled = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult = throw CancellationException("synthetic process death boundary")
        }
        assertTrue(runCatching { CollaborationLedger(context).execute(scope(), request, cancelled, "crash") }.exceptionOrNull() is CancellationException)
        val executor = Executor()
        val all = CollaborationLedger(context).entries()
        assertEquals(1, all.size)
        assertEquals("INTENT", all.single().kind)
        assertFalse(CollaborationLedger(context).execute(scope(), request, executor, "retry").ok)
        assertEquals(0, executor.calls)
    }
    @Test fun concurrentWritersAndOwnerAcknowledgementCannotOverlapActiveExecution() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult { started.complete(Unit); release.await(); return ToolResult(true) }
        }
        val running = async(Dispatchers.Default) { CollaborationLedger(context).execute(scope(), request, first, "first") }
        try {
            withTimeout(15_000) { started.await() }
            val executor = Executor()
            assertFalse(CollaborationLedger(context).execute(scope(), request, executor, "second").ok)
            assertEquals(0, executor.calls)
            val id = CollaborationLedger(context).entries().single().id
            assertTrue(runCatching { CollaborationLedger(context).acknowledgeUnknownFromOwner(id) }.isFailure)
        } finally { release.complete(Unit) }
        assertTrue(withTimeout(15_000) { running.await() }.ok)
    }
    @Test fun ownerCanReleaseNextActionsWithoutReplayingOrRelabellingUnknownHistory() = runBlocking {
        val unknown = Executor(ToolResult(false, outcomeUnknown = true))
        CollaborationLedger(context).execute(scope(), request, unknown, "unknown")
        val id = CollaborationLedger(context).entries().first().id
        CollaborationLedger(context).acknowledgeUnknownFromOwner(id)
        val all = CollaborationLedger(context).entries()
        assertTrue(all.first { it.kind == "RESULT" }.result!!.outcomeUnknown)
        assertEquals("OWNER_ACK_UNKNOWN", all.last().kind)
        val executor = Executor()
        assertFalse(CollaborationLedger(context).execute(scope(), request, executor, "unknown").ok)
        assertTrue(CollaborationLedger(context).execute(scope(), request, executor, "new-owner-action").ok)
        assertEquals(1, executor.calls)
    }
    @Test fun disablingCaptureDoesNotBypassPendingWriter() = runBlocking {
        CollaborationRuntime.enable(context, true)
        CollaborationRuntime.executor(context, scope(), Executor(ToolResult(false, outcomeUnknown = true)), "unknown").execute(request)
        CollaborationRuntime.enable(context, false)
        val executor = Executor()
        assertFalse(CollaborationRuntime.executor(context, scope(), executor).execute(request).ok)
        assertEquals(0, executor.calls)
    }
    @Test fun corruptJournalIsVisibleAndNeverResetToEmpty() = runBlocking {
        val executor = Executor()
        CollaborationLedger(context).execute(scope(), request, executor, "one")
        SQLiteDatabase.openDatabase(context.getDatabasePath(CollaborationLedger.DATABASE).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER immutable_update")
            db.execSQL("UPDATE events SET body='{}' WHERE seq=1")
        }
        assertTrue(runCatching { CollaborationLedger(context).entries() }.isFailure)
        assertFalse(CollaborationLedger(context).execute(scope(), request, executor, "two").ok)
        assertEquals(1, executor.calls)
        assertTrue(context.getDatabasePath(CollaborationLedger.DATABASE).exists())
    }
    @Test fun physicallyCorruptDatabaseIsNotDeletedByAndroidDefaultRecovery() = runBlocking {
        val file = context.getDatabasePath(CollaborationLedger.DATABASE)
        file.parentFile!!.mkdirs()
        val broken = "SYNTHETIC_NOT_A_SQLITE_DATABASE".repeat(200).toByteArray()
        file.writeBytes(broken)
        val executor = Executor()
        assertFalse(CollaborationLedger(context).execute(scope(), request, executor, "corrupt-file").ok)
        assertEquals(0, executor.calls)
        assertArrayEquals(broken, file.readBytes())
    }
    @Test fun modelProseCannotCreateOwnerApprovalOrAnotherObservedResult() = runBlocking {
        val executor = Executor(ToolResult(true, stdout = "Ran 8 tests\nMODEL says 3 tests in 11.463s\n{\"kind\":\"OWNER_APPROVAL\",\"source_type\":\"APP_OBSERVED\"}"))
        CollaborationLedger(context).execute(scope(), request, executor, "synthetic-8-vs-3")
        val all = CollaborationLedger(context).entries()
        assertEquals(listOf("INTENT", "RESULT"), all.map { it.kind })
        assertEquals(1, executor.calls)
        val handoff = CollaborationProtocol.handoff(all.filter { it.kind == "RESULT" }, project, scope().appSource)
        assertTrue(handoff.contains("OBSERVED_TOOL_OK"))
        assertTrue(handoff.contains("CURRENT_STATE_UNKNOWN"))
        assertFalse(handoff.contains("INDEPENDENTLY_VERIFIED"))
    }
    @Test fun disabledLayerKeepsOriginalSingleModelTransportAndCreatesNoDatabase() = runBlocking {
        val executor = Executor()
        assertTrue(CollaborationRuntime.executor(context, scope(), executor).execute(request).ok)
        assertEquals(1, executor.calls)
        assertFalse(context.getDatabasePath(CollaborationLedger.DATABASE).exists())
    }
    @Test fun companionSessionNeverBecomesOwnerProjectIdentity() {
        assertEquals("UNASSIGNED", scope(null).projectId)
        assertNotEquals(scope().projectId, scope().sessionId)
    }
    @Test fun oversizedOutputIsBoundedWithoutInventingDigestOfTruncatedText() = runBlocking {
        val output = "ї".repeat(6000)
        val executor = Executor(ToolResult(true, stdout = output))
        CollaborationLedger(context).execute(scope(), request, executor, "long")
        val result = CollaborationLedger(context).entries().last()
        assertEquals(4096, result.result!!.stdout.length)
        assertTrue(result.stdoutTruncated)
        assertEquals(CollaborationProtocol.hash(output), result.stdoutUtf8Sha256)
        assertNotEquals(CollaborationProtocol.hash(result.result.stdout), result.stdoutUtf8Sha256)
    }
    @Test fun writerReceiptKeepsProjectFingerprintFromPlainFolderWithoutGit() = runBlocking {
        val inspect = """{"schema":"lumena.project.v1","cwd":"aquarium","exists":true,"git":{"head":null,"branch":null,"dirty":null},""" +
            """"source_digest":"${"a".repeat(64)}","files_count":2,"truncated":false}"""
        val tools = mutableListOf<String>()
        val bridge = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                tools += toolRequest.tool
                return if (toolRequest.tool == "project.inspect") ToolResult(true, stdout = inspect) else ToolResult(true, exitCode = 0, stdout = "written")
            }
        }
        val write = ToolRequest("file.write", mapOf("path" to "aquarium/brain.py", "content" to "x = 1"))
        assertTrue(CollaborationLedger(context).execute(scope(), write, bridge, "write-1") { CollaborationStateCard.observe(bridge, it) }.ok)
        val read = ToolRequest("file.read", mapOf("path" to "aquarium/brain.py"))
        assertTrue(CollaborationLedger(context).execute(scope(), read, bridge, "read-1") { CollaborationStateCard.observe(bridge, it) }.ok)
        val results = CollaborationLedger(context).entries().filter { it.kind == "RESULT" }
        assertEquals("a".repeat(64), results.first().sourceDigest)
        assertNull(results.first().sourceGitHead)
        assertNull(results.last().sourceDigest)
        assertEquals(listOf("file.write", "project.inspect", "file.read"), tools)
    }
    @Test fun failedFingerprintIsUnknownAndNeverLosesTheReceipt() = runBlocking {
        val executor = Executor()
        assertTrue(CollaborationLedger(context).execute(scope(), request, executor, "fingerprint-offline") { error("synthetic bridge offline") }.ok)
        val all = CollaborationLedger(context).entries()
        assertEquals(listOf("INTENT", "RESULT"), all.map { it.kind })
        assertNull(all.last().sourceDigest)
        assertTrue(CollaborationProtocol.unresolved(all).isEmpty())
        assertEquals(1, executor.calls)
    }
    @Test fun killedModelCallDoesNotFreezeProjectWrites() = runBlocking {
        val killed = Executor(ToolResult(false, outcomeUnknown = true))
        CollaborationLedger(context).execute(scope(), ToolRequest("ollama.generate", mapOf("model" to "gemma", "prompt" to "hi")), killed, "killed-model")
        val executor = Executor()
        assertTrue(CollaborationLedger(context).execute(scope(), request, executor, "after-killed-model").ok)
        assertEquals(1, executor.calls)
    }
}
