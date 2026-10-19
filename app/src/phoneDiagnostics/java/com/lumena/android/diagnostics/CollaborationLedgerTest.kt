package com.lumena.android.diagnostics

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.agent.local.*
import com.lumena.android.collaboration.*
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
        context.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE).edit().clear().commit()
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
}
