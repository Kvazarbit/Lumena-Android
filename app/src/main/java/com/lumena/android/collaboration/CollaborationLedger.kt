package com.lumena.android.collaboration

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** A durable transport journal, not a new goal verifier or learning store. No destructive upgrade. */
class CollaborationLedger(context: Context) {
    private val appContext = context.applicationContext
    private val archive = CollaborationArchive(appContext)
    private fun helper() = Helper(appContext)
    private inline fun <T> withHelper(block: (Helper) -> T): T = synchronized(leaseMonitor) {
        archive.recover()
        val h = helper()
        try { block(h) } finally { h.close() }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DATABASE, null, 1,
        DatabaseErrorHandler { throw SQLiteException("Collaboration SQLite corruption; original file retained") }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.disableWriteAheadLogging()
            db.execSQL("PRAGMA synchronous=FULL")
            require(db.setMaximumSize(64L * 1024 * 1024) <= 64L * 1024 * 1024) { "Collaboration database exceeds disk budget" }
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, body TEXT NOT NULL, previous TEXT NOT NULL, digest TEXT NOT NULL)")
            db.execSQL("CREATE TRIGGER immutable_update BEFORE UPDATE ON events BEGIN SELECT RAISE(ABORT, 'append-only'); END")
            db.execSQL("CREATE TRIGGER immutable_delete BEFORE DELETE ON events BEGIN SELECT RAISE(ABORT, 'append-only'); END")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported collaboration migration; historical data retained")
        }
    }

    private fun read(db: SQLiteDatabase, base: String = "GENESIS"): List<CollaborationEntry> {
        val out = mutableListOf<CollaborationEntry>()
        val identities = mutableSetOf<String>()
        val deliveries = mutableSetOf<String>()
        val intents = mutableMapOf<String, CollaborationEntry>()
        val results = mutableMapOf<String, CollaborationEntry>()
        val acknowledged = mutableSetOf<String>()
        var previous = base
        var bytes = 0
        var seq = 0L
        db.rawQuery("SELECT seq,id,body,previous,digest FROM events ORDER BY seq", null).use { c ->
            while (c.moveToNext()) {
                val body = c.getString(2)
                bytes += body.toByteArray(Charsets.UTF_8).size
                require(out.size < CollaborationProtocol.MAX_EVENTS && bytes <= CollaborationProtocol.MAX_BYTES) { "Collaboration archive exceeds budget" }
                require(c.getLong(0) == ++seq && c.getString(3) == previous &&
                    c.getString(4) == CollaborationProtocol.hash(previous + "\n" + body)) { "Collaboration integrity error; no data reset" }
                val entry = CollaborationProtocol.decode(body)
                require(entry.id == c.getString(1))
                if (entry.kind != "INTENT") {
                    val intent = intents[entry.intentId]
                    require(intent != null && intent.scope == entry.scope && intent.tool == entry.tool &&
                        intent.requestDigest == entry.requestDigest && intent.deliveryKey == entry.deliveryKey &&
                        intent.targets == entry.targets && intent.mutating == entry.mutating &&
                        intent.boundInputSha256 == entry.boundInputSha256)
                    if (entry.kind == "OWNER_ACK_UNKNOWN") {
                        require(intent.mutating && acknowledged.add(intent.id) &&
                            (results[intent.id] == null || results[intent.id]?.result?.outcomeUnknown == true))
                    } else require(results.put(entry.intentId, entry) == null)
                }
                require(identities.add(entry.id))
                if (entry.kind == "INTENT") {
                    require(deliveries.add(entry.deliveryKey))
                    intents[entry.id] = entry
                }
                out += entry
                previous = c.getString(4)
            }
        }
        return out
    }

    private fun append(db: SQLiteDatabase, e: CollaborationEntry) {
        val body = CollaborationProtocol.encode(e)
        db.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM events", null).use { c ->
            c.moveToFirst()
            require(c.getInt(0) < CollaborationProtocol.MAX_EVENTS &&
                c.getLong(1) + body.toByteArray(Charsets.UTF_8).size <= CollaborationProtocol.MAX_BYTES) { "Collaboration archive full; history retained" }
        }
        val previous = db.rawQuery("SELECT digest FROM events ORDER BY seq DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else archiveIndex().tail
        }
        db.insertOrThrow("events", null, ContentValues().apply {
            put("id", e.id); put("body", body); put("previous", previous)
            put("digest", CollaborationProtocol.hash(previous + "\n" + body))
        })
    }

    private fun archiveIndex() = archive.index { db, base -> read(db, base) }
    fun entries(): List<CollaborationEntry> = withHelper { h ->
        val old = archiveIndex()
        old.recent + read(h.readableDatabase, old.tail)
    }
    fun archivedSegments(): Int = withHelper { archiveIndex().segments }
    fun exportArchive(output: java.io.OutputStream) = synchronized(leaseMonitor) {
        require(active.isEmpty()) { "Stop active operations before exporting" }
        withHelper { h -> read(h.readableDatabase, archiveIndex().tail) }
        archive.export(output)
    }

    /** Move a fully closed segment. Pending effects stay in the active journal until owner inspection. */
    fun archiveClosedSegment() = archiveClosedSegmentAfterPrepare {}
    internal fun archiveClosedSegmentAfterPrepare(afterPrepare: () -> Unit) = synchronized(leaseMonitor) {
        require(active.isEmpty()) { "An execution is still active" }
        val observed = withHelper { h ->
            val db = h.readableDatabase
            val entries = read(db, archiveIndex().tail)
            require(entries.isNotEmpty() && CollaborationProtocol.unresolved(entries).isEmpty()) { "Resolve UNKNOWN effects before archiving" }
            val tail = db.rawQuery("SELECT digest FROM events ORDER BY seq DESC LIMIT 1", null).use { c -> check(c.moveToFirst()); c.getString(0) }
            entries.size to tail
        }
        // All helpers are closed before rename. Recovery completes the prepared move before creating a DB.
        archive.rotate(observed.second, observed.first, afterPrepare)
    }

    private fun prepareCapacity() {
        if (active.isNotEmpty()) return
        val due = withHelper { h ->
            h.readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM events", null).use { c ->
                c.moveToFirst(); c.getInt(0) >= 8_000 || c.getLong(1) >= 20L * 1024 * 1024
            }
        }
        if (due) archiveClosedSegment()
    }

    /** Called after ToolGate/owner approval and Python hash binding, immediately before transport. */
    private fun begin(scope: CollaborationScope, request: ToolRequest, delivery: String): CollaborationEntry = withHelper { h ->
        val db = h.writableDatabase
        db.beginTransaction()
        try {
            val old = archiveIndex()
            val all = read(db, old.tail)
            // Delivery identity does not contain a payload digest: altered replay must conflict.
            val key = CollaborationProtocol.hash(listOf(scope.channel, scope.sessionId, scope.taskId, delivery)
                .joinToString("") { "${it.length}:$it" })
            require(key !in old.deliveries) { "Archived delivery; no re-execution" }
            require(old.deliveries.size + all.count { it.kind == "INTENT" } < CollaborationArchive.MAX_DELIVERIES)
            all.firstOrNull { it.kind == "INTENT" && it.deliveryKey == key }?.let {
                require(it.scope == scope && it.requestDigest == CollaborationProtocol.requestDigest(request)) { "Conflicting collaboration delivery" }
                error("Delivery already recorded: ${it.id}; consult historical receipt, no re-execution")
            }
            val mutating = CollaborationProtocol.writer(request.tool)
            if (mutating) {
                require(CollaborationProtocol.unresolved(all, scope.bridgeBinding).isEmpty()) {
                    "Unresolved writer on this bridge; reconciliation required, no repeat"
                }
            }
            val id = UUID.randomUUID().toString()
            val e = CollaborationEntry(id = id, intentId = id, kind = "INTENT", scope = scope,
                tool = request.tool, requestDigest = CollaborationProtocol.requestDigest(request), deliveryKey = key,
                mutating = mutating, targets = request.args.filterKeys { it in setOf("cwd", "path", "script", "file") }
                    .mapValues { it.value.take(360) },
                boundInputSha256 = request.args["approved_sha256"]?.takeIf { request.tool == "python.run" && it.matches(Regex("[a-f0-9]{64}")) },
                at = System.currentTimeMillis())
            // Reserve room for a result BEFORE authorizing transport. Full journal never discards data.
            val completed = all.filter { it.kind == "RESULT" }.map { it.intentId }.toHashSet()
            val unfinished = all.count { it.kind == "INTENT" && it.id !in completed }
            require(all.size + unfinished + 2 <= CollaborationProtocol.MAX_EVENTS)
            val used = all.sumOf { CollaborationProtocol.encode(it).toByteArray(Charsets.UTF_8).size.toLong() }
            require(used + CollaborationProtocol.encode(e).toByteArray(Charsets.UTF_8).size + (unfinished + 1L) * 32_768 <= CollaborationProtocol.MAX_BYTES)
            append(db, e)
            db.setTransactionSuccessful()
            e
        } finally { db.endTransaction() }
    }

    /** Explicit local owner acknowledgement after inspection, never a successful outcome or automatic retry. */
    fun acknowledgeUnknownFromOwner(intentId: String) = synchronized(leaseMonitor) { withHelper { h ->
        require(intentId !in active) { "Execution still active; stop and inspect the tool first" }
        val db = h.writableDatabase
        db.beginTransaction()
        try {
            val all = read(db, archiveIndex().tail)
            val intent = CollaborationProtocol.unresolved(all).firstOrNull { it.id == intentId }
                ?: error("No unresolved writer")
            append(db, intent.copy(id = UUID.randomUUID().toString(), kind = "OWNER_ACK_UNKNOWN", at = System.currentTimeMillis()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    } }

    private fun finish(intent: CollaborationEntry, result: ToolResult, source: SourceObservation) = withHelper { h ->
        val db = h.writableDatabase
        db.beginTransaction()
        try {
            val all = read(db, archiveIndex().tail)
            require(all.any { it == intent } && all.none { it.kind == "RESULT" && it.intentId == intent.id })
            append(db, intent.copy(id = UUID.randomUUID().toString(), kind = "RESULT", at = System.currentTimeMillis(),
                stdoutUtf8Sha256 = CollaborationProtocol.hash(result.stdout), stdoutTruncated = result.stdout.length > 4096,
                result = result.copy(stdout = result.stdout.take(4096), stderr = result.stderr.take(512), error = result.error?.take(512),
                    tool = result.tool?.take(100), errorCode = result.errorCode?.take(120), failureClass = result.failureClass?.take(120),
                    dependency = result.dependency?.take(160)), sourceDigest = source.digest, sourceGitHead = source.gitHead))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun execute(scope: CollaborationScope, request: ToolRequest, delegate: ToolExecutor, delivery: String,
                        observe: (suspend (String) -> SourceObservation)? = null): ToolResult {
        var captured: CollaborationEntry? = null
        try {
            val intent = try { withContext(Dispatchers.IO) { synchronized(leaseMonitor) {
                prepareCapacity()
                begin(scope, request, delivery).also { captured = it; active.add(it.id) }
            } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return blocked("Journal unavailable, conflicting/replayed delivery, or unresolved writer; no tool sent", false) }
            // Cancellation/process death leaves durable INTENT. Never retry an unknown mutation here.
            val result = try { delegate.execute(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { blocked("Transport failed after intent; effect UNKNOWN", true) }
            // Project fingerprint right after a writer. Failure leaves it UNKNOWN; the receipt itself is never lost.
            val source = if (observe == null || !intent.mutating || intent.scope.projectId == "UNASSIGNED" || result.outcomeUnknown) {
                SourceObservation.UNKNOWN
            } else try { observe(intent.scope.root) }
            catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { finish(intent, result, SourceObservation.UNKNOWN) } }
                throw cancelled
            } catch (_: Exception) { SourceObservation.UNKNOWN }
            return try {
                withContext(Dispatchers.IO) { finish(intent, result, source) }
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { blocked("Result not durably saved; effect UNKNOWN; intent=${intent.id}", true) }
        } finally { synchronized(leaseMonitor) { captured?.let { active.remove(it.id) } } }
    }

    companion object {
        const val DATABASE = "lumena_collaboration_v1.db"
        private val leaseMonitor = Any()
        private val active = mutableSetOf<String>()
        fun blocked(message: String, unknown: Boolean) = ToolResult(false, error = message,
            outcomeUnknown = unknown, errorCode = "COLLABORATION_HOLD", retryable = false)
    }
}
