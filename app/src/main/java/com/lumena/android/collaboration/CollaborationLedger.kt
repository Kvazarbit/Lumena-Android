package com.lumena.android.collaboration

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** A durable transport journal, not a new goal verifier or learning store. No destructive upgrade. */
class CollaborationLedger(context: Context) {
    private val appContext = context.applicationContext
    private fun helper() = Helper(appContext)
    private inline fun <T> withHelper(block: (Helper) -> T): T {
        val h = helper()
        try { return block(h) } finally { h.close() }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DATABASE, null, 1) {
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

    private fun read(db: SQLiteDatabase): List<CollaborationEntry> {
        val out = mutableListOf<CollaborationEntry>()
        val identities = mutableSetOf<String>()
        val deliveries = mutableSetOf<String>()
        val intents = mutableMapOf<String, CollaborationEntry>()
        val results = mutableMapOf<String, CollaborationEntry>()
        val acknowledged = mutableSetOf<String>()
        var previous = "GENESIS"
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
            if (c.moveToFirst()) c.getString(0) else "GENESIS"
        }
        db.insertOrThrow("events", null, ContentValues().apply {
            put("id", e.id); put("body", body); put("previous", previous)
            put("digest", CollaborationProtocol.hash(previous + "\n" + body))
        })
    }

    fun entries(): List<CollaborationEntry> = withHelper { h -> read(h.readableDatabase) }

    /** Called after ToolGate/owner approval and Python hash binding, immediately before transport. */
    private fun begin(scope: CollaborationScope, request: ToolRequest, delivery: String): CollaborationEntry = withHelper { h ->
        val db = h.writableDatabase
        db.beginTransaction()
        try {
            val all = read(db)
            // Delivery identity does not contain a payload digest: altered replay must conflict.
            val key = CollaborationProtocol.hash(listOf(scope.channel, scope.sessionId, scope.taskId, delivery)
                .joinToString("") { "${it.length}:$it" })
            all.firstOrNull { it.kind == "INTENT" && it.deliveryKey == key }?.let {
                require(it.scope == scope && it.requestDigest == CollaborationProtocol.requestDigest(request)) { "Conflicting collaboration delivery" }
                error("Delivery already recorded: ${it.id}; consult historical receipt, no re-execution")
            }
            val mutating = ToolRegistry.get(request.tool)?.risk != ToolRisk.READ_ONLY
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
            val all = read(db)
            val intent = CollaborationProtocol.unresolved(all).firstOrNull { it.id == intentId }
                ?: error("No unresolved writer")
            append(db, intent.copy(id = UUID.randomUUID().toString(), kind = "OWNER_ACK_UNKNOWN", at = System.currentTimeMillis()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    } }

    private fun finish(intent: CollaborationEntry, result: ToolResult) = withHelper { h ->
        val db = h.writableDatabase
        db.beginTransaction()
        try {
            val all = read(db)
            require(all.any { it == intent } && all.none { it.kind == "RESULT" && it.intentId == intent.id })
            append(db, intent.copy(id = UUID.randomUUID().toString(), kind = "RESULT", at = System.currentTimeMillis(),
                stdoutUtf8Sha256 = CollaborationProtocol.hash(result.stdout), stdoutTruncated = result.stdout.length > 4096,
                result = result.copy(stdout = result.stdout.take(4096), stderr = result.stderr.take(512), error = result.error?.take(512),
                    tool = result.tool?.take(100), errorCode = result.errorCode?.take(120), failureClass = result.failureClass?.take(120),
                    dependency = result.dependency?.take(160))))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun execute(scope: CollaborationScope, request: ToolRequest, delegate: ToolExecutor, delivery: String): ToolResult {
        var captured: CollaborationEntry? = null
        try {
            val intent = try { withContext(Dispatchers.IO) { synchronized(leaseMonitor) {
                begin(scope, request, delivery).also { captured = it; active.add(it.id) }
            } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return blocked("Journal unavailable, conflicting/replayed delivery, or unresolved writer; no tool sent", false) }
            // Cancellation/process death leaves durable INTENT. Never retry an unknown mutation here.
            val result = try { delegate.execute(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { blocked("Transport failed after intent; effect UNKNOWN", true) }
            return try {
                withContext(Dispatchers.IO) { finish(intent, result) }
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
