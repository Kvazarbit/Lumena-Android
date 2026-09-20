package com.lumena.android.settings

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream

data class GenomeEvent(
    val id: String,
    val createdAt: Long,
    val tool: String,
    val target: String,
    val argsHash: String,
    val ok: Boolean,
    val exitCode: Int?,
    val resultHash: String,
    val evidenceExcerpt: String
)

data class GenomeLink(
    val fromId: String,
    val toId: String,
    val relation: String,
    val createdAt: Long
)

data class GenomeCapsule(
    val id: String,
    val level: Int,
    val scopeKey: String,
    val topicKey: String,
    val summary: String,
    val importance: Double,
    val updatedAt: Long,
    val childIds: List<String>,
    val evidenceIds: List<String>
)

data class ContextGenomeStats(
    val events: Int,
    val anchors: Int,
    val links: Int,
    val capsules: Int
)

/**
 * SQLite evidence ledger for Lumena Context Genome.
 *
 * events are append-only verified TOOL_RESULT observations.
 * anchors are a deterministic projection maintained by ExperienceMemoryIndex.
 * links preserve causal relations such as SUPPORTS and RESOLVED_BY.
 * capsules are hierarchical summaries derived from anchors, never model prose.
 */
object ContextGenomeStore {
    private const val DB_NAME = "lumena_context_genome.db"
    private const val DB_VERSION = 1
    private const val MAX_RAW_RESULT_BYTES = 128 * 1024
    private val lock = Any()

    fun loadProjection(context: Context): ExperienceMemoryState? = synchronized(lock) {
        val db = helper(context).readableDatabase
        val anchors = mutableListOf<ExperienceAnchor>()
        db.query(
            "genome_anchors",
            arrayOf(
                "id", "signature", "tool", "target", "valence", "summary",
                "occurrences", "first_seen_at", "last_seen_at", "resolved_at"
            ),
            null, null, null, null,
            "last_seen_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                anchors += ExperienceAnchor(
                    id = cursor.getString(0),
                    signature = cursor.getString(1),
                    tool = cursor.getString(2),
                    target = cursor.getString(3),
                    valence = runCatching {
                        ExperienceValence.valueOf(cursor.getString(4))
                    }.getOrDefault(ExperienceValence.NEGATIVE),
                    summary = cursor.getString(5),
                    occurrences = cursor.getInt(6),
                    firstSeenAt = cursor.getLong(7),
                    lastSeenAt = cursor.getLong(8),
                    resolvedAt = if (cursor.isNull(9)) null else cursor.getLong(9)
                )
            }
        }
        anchors.takeIf { it.isNotEmpty() }?.let(::ExperienceMemoryState)
    }

    fun importLegacyProjection(
        context: Context,
        state: ExperienceMemoryState,
        now: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        if (state.anchors.isEmpty()) return@synchronized
        val db = helper(context).writableDatabase
        db.beginTransaction()
        try {
            if (count(db, "genome_anchors") == 0) {
                syncAnchors(db, state)
                state.anchors.groupBy { it.signature }.forEach { (signature, _) ->
                    updateSignatureCapsule(db, state, signature, now, emptyList())
                }
                updateAllTopicCapsules(db, now)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun record(
        context: Context,
        request: ToolRequest,
        result: ToolResult,
        projection: ExperienceMemoryState,
        now: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val db = helper(context).writableDatabase
        val tool = request.tool.trim().lowercase()
        val target = targetOf(request)
        val signature = sha256("$tool|$target")
        val argsHash = sha256(
            request.args.toSortedMap().entries.joinToString("&") {
                "${it.key}=${it.value}"
            }
        )
        val rawResult = buildString {
            append("ok=").append(result.ok).append('\n')
            result.exitCode?.let { append("exit_code=").append(it).append('\n') }
            result.error?.let { append("error=").append(it).append('\n') }
            if (result.stdout.isNotBlank()) append("stdout:\n").append(result.stdout).append('\n')
            if (result.stderr.isNotBlank()) append("stderr:\n").append(result.stderr).append('\n')
        }
        val rawBytes = rawResult.toByteArray()
        val boundedBytes = if (rawBytes.size <= MAX_RAW_RESULT_BYTES) {
            rawBytes
        } else {
            rawBytes.copyOf(MAX_RAW_RESULT_BYTES)
        }
        val resultHash = sha256Bytes(rawBytes)
        val eventId = UUID.randomUUID().toString()
        val evidenceExcerpt = summarizeResult(result)
        val compressed = gzip(boundedBytes)

        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", eventId)
                put("created_at", now)
                put("tool", tool)
                put("target", target)
                put("signature", signature)
                put("args_hash", argsHash)
                put("ok", if (result.ok) 1 else 0)
                result.exitCode?.let { put("exit_code", it) }
                put("result_hash", resultHash)
                put("evidence_excerpt", evidenceExcerpt)
                put("result_gzip", compressed)
                put("result_raw_bytes", rawBytes.size)
                put("result_stored_bytes", boundedBytes.size)
            }
            db.insertOrThrow("genome_events", null, values)

            syncAnchors(db, projection)

            val matching = projection.anchors.filter { it.signature == signature }
            val supported = if (result.ok) {
                matching.firstOrNull { it.valence == ExperienceValence.POSITIVE }
            } else {
                matching
                    .filter { it.valence == ExperienceValence.NEGATIVE }
                    .maxByOrNull { it.lastSeenAt }
            }
            supported?.let { anchor ->
                insertLink(db, eventId, anchor.id, "SUPPORTS", now)
            }

            if (result.ok) {
                val positive = matching.firstOrNull { it.valence == ExperienceValence.POSITIVE }
                if (positive != null) {
                    matching
                        .filter {
                            it.valence == ExperienceValence.NEGATIVE &&
                                it.resolvedAt == now
                        }
                        .forEach { negative ->
                            insertLink(db, negative.id, positive.id, "RESOLVED_BY", now)
                        }
                }
            }

            updateSignatureCapsule(
                db = db,
                state = projection,
                signature = signature,
                now = now,
                evidenceIds = listOf(eventId)
            )
            updateAllTopicCapsules(db, now)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun capsules(
        context: Context,
        level: Int? = null,
        limit: Int = 32
    ): List<GenomeCapsule> = synchronized(lock) {
        val db = helper(context).readableDatabase
        val selection = if (level == null) null else "level=?"
        val args = level?.let { arrayOf(it.toString()) }
        val out = mutableListOf<GenomeCapsule>()
        db.query(
            "genome_capsules",
            arrayOf(
                "id", "level", "scope_key", "topic_key", "summary",
                "importance", "updated_at", "child_ids_json", "evidence_ids_json"
            ),
            selection,
            args,
            null,
            null,
            "importance DESC, updated_at DESC",
            limit.coerceIn(1, 128).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += GenomeCapsule(
                    id = cursor.getString(0),
                    level = cursor.getInt(1),
                    scopeKey = cursor.getString(2),
                    topicKey = cursor.getString(3),
                    summary = cursor.getString(4),
                    importance = cursor.getDouble(5),
                    updatedAt = cursor.getLong(6),
                    childIds = jsonList(cursor.getString(7)),
                    evidenceIds = jsonList(cursor.getString(8))
                )
            }
        }
        out
    }

    fun express(
        context: Context,
        query: String,
        maxChars: Int = 3_200,
        maxUnits: Int = 8
    ): GenomeExpressionPacket = synchronized(lock) {
        val db = helper(context).readableDatabase
        val projection = loadProjection(context) ?: ExperienceMemoryState()

        val evidenceByAnchor = mutableMapOf<String, MutableList<String>>()
        db.query(
            "genome_links",
            arrayOf("from_id", "to_id"),
            "relation=?",
            arrayOf("SUPPORTS"),
            null, null,
            "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getString(0)
                val anchorId = cursor.getString(1)
                evidenceByAnchor
                    .getOrPut(anchorId) { mutableListOf() }
                    .add(eventId)
            }
        }

        val anchorUnits = projection.anchors.map { anchor ->
            val state = when {
                anchor.valence == ExperienceValence.POSITIVE -> "POSITIVE verified"
                anchor.resolvedAt == null -> "NEGATIVE unresolved"
                else -> "NEGATIVE resolved"
            }
            val target = anchor.target.ifBlank { "(general)" }
            val importance =
                (if (anchor.valence == ExperienceValence.NEGATIVE && anchor.resolvedAt == null) 18.0 else 0.0) +
                    (if (anchor.valence == ExperienceValence.POSITIVE) 8.0 else 3.0) +
                    anchor.occurrences.coerceAtMost(10) * 1.5

            GenomeMemoryUnit(
                id = anchor.id,
                layer = GenomeLayer.ANCHOR,
                topicKey = topicOf(anchor.tool, anchor.target),
                text = "$state · ${anchor.tool} · target=$target · ${anchor.summary} · seen=${anchor.occurrences}x",
                importance = importance,
                updatedAt = anchor.lastSeenAt,
                evidenceIds = evidenceByAnchor[anchor.id].orEmpty().distinct().take(16)
            )
        }

        val capsuleUnits = capsules(context, limit = 128).map { capsule ->
            GenomeMemoryUnit(
                id = capsule.id,
                layer = if (capsule.level >= 2) {
                    GenomeLayer.TOPIC_CAPSULE
                } else {
                    GenomeLayer.SIGNATURE_CAPSULE
                },
                topicKey = capsule.topicKey,
                text = capsule.summary,
                importance = capsule.importance,
                updatedAt = capsule.updatedAt,
                evidenceIds = capsule.evidenceIds
            )
        }

        ContextGenomePolicy.express(
            units = anchorUnits + capsuleUnits,
            query = query,
            maxChars = maxChars.coerceIn(256, 12_000),
            maxUnits = maxUnits.coerceIn(1, 16)
        )
    }

    fun recentEvents(
        context: Context,
        limit: Int = 50
    ): List<GenomeEvent> = synchronized(lock) {
        val db = helper(context).readableDatabase
        val out = mutableListOf<GenomeEvent>()
        db.query(
            "genome_events",
            arrayOf(
                "id", "created_at", "tool", "target", "args_hash",
                "ok", "exit_code", "result_hash", "evidence_excerpt"
            ),
            null, null, null, null,
            "created_at DESC",
            limit.coerceIn(1, 200).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += GenomeEvent(
                    id = cursor.getString(0),
                    createdAt = cursor.getLong(1),
                    tool = cursor.getString(2),
                    target = cursor.getString(3),
                    argsHash = cursor.getString(4),
                    ok = cursor.getInt(5) != 0,
                    exitCode = if (cursor.isNull(6)) null else cursor.getInt(6),
                    resultHash = cursor.getString(7),
                    evidenceExcerpt = cursor.getString(8)
                )
            }
        }
        out
    }

    fun links(
        context: Context,
        limit: Int = 100
    ): List<GenomeLink> = synchronized(lock) {
        val db = helper(context).readableDatabase
        val out = mutableListOf<GenomeLink>()
        db.query(
            "genome_links",
            arrayOf("from_id", "to_id", "relation", "created_at"),
            null, null, null, null,
            "created_at DESC",
            limit.coerceIn(1, 500).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += GenomeLink(
                    fromId = cursor.getString(0),
                    toId = cursor.getString(1),
                    relation = cursor.getString(2),
                    createdAt = cursor.getLong(3)
                )
            }
        }
        out
    }

    fun stats(context: Context): ContextGenomeStats = synchronized(lock) {
        val db = helper(context).readableDatabase
        ContextGenomeStats(
            events = count(db, "genome_events"),
            anchors = count(db, "genome_anchors"),
            links = count(db, "genome_links"),
            capsules = count(db, "genome_capsules")
        )
    }

    fun clear(context: Context) = synchronized(lock) {
        context.applicationContext.deleteDatabase(DB_NAME)
    }

    private fun syncAnchors(
        db: SQLiteDatabase,
        state: ExperienceMemoryState
    ) {
        val keep = state.anchors.map { it.id }.toSet()
        db.query(
            "genome_anchors",
            arrayOf("id"),
            null, null, null, null, null
        ).use { cursor ->
            val stale = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (id !in keep) stale += id
            }
            stale.forEach { id ->
                db.delete("genome_anchors", "id=?", arrayOf(id))
            }
        }

        state.anchors.forEach { anchor ->
            val values = ContentValues().apply {
                put("id", anchor.id)
                put("signature", anchor.signature)
                put("tool", anchor.tool)
                put("target", anchor.target)
                put("valence", anchor.valence.name)
                put("summary", anchor.summary)
                put("occurrences", anchor.occurrences)
                put("first_seen_at", anchor.firstSeenAt)
                put("last_seen_at", anchor.lastSeenAt)
                if (anchor.resolvedAt == null) putNull("resolved_at") else put("resolved_at", anchor.resolvedAt)
            }
            db.insertWithOnConflict(
                "genome_anchors",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    private fun updateSignatureCapsule(
        db: SQLiteDatabase,
        state: ExperienceMemoryState,
        signature: String,
        now: Long,
        evidenceIds: List<String>
    ) {
        val anchors = state.anchors.filter { it.signature == signature }
        if (anchors.isEmpty()) return

        val tool = anchors.first().tool
        val target = anchors.first().target
        val positiveSeen = anchors
            .filter { it.valence == ExperienceValence.POSITIVE }
            .sumOf { it.occurrences }
        val negativeSeen = anchors
            .filter { it.valence == ExperienceValence.NEGATIVE }
            .sumOf { it.occurrences }
        val unresolved = anchors.count {
            it.valence == ExperienceValence.NEGATIVE && it.resolvedAt == null
        }
        val resolved = anchors.count {
            it.valence == ExperienceValence.NEGATIVE && it.resolvedAt != null
        }
        val latest = anchors.maxByOrNull { it.lastSeenAt }!!
        val summary = buildString {
            append(tool)
            if (target.isNotBlank()) append(" · ").append(target)
            append(" · positive=").append(positiveSeen)
            append(" negative=").append(negativeSeen)
            append(" unresolved=").append(unresolved)
            append(" resolved=").append(resolved)
            append(" · latest=").append(latest.summary.take(360))
        }
        val importance =
            unresolved * 10.0 +
                positiveSeen.coerceAtMost(10) * 1.5 +
                negativeSeen.coerceAtMost(10) * 1.0 +
                anchors.size * 0.5

        val id = sha256("capsule:signature:$signature").take(24)
        val topic = topicOf(tool, target)
        upsertCapsule(
            db = db,
            capsule = GenomeCapsule(
                id = id,
                level = 1,
                scopeKey = signature,
                topicKey = topic,
                summary = summary,
                importance = importance,
                updatedAt = now,
                childIds = anchors.map { it.id },
                evidenceIds = mergeEvidenceIds(db, id, evidenceIds)
            )
        )
    }

    private fun updateAllTopicCapsules(
        db: SQLiteDatabase,
        now: Long
    ) {
        val levelOne = mutableListOf<GenomeCapsule>()
        db.query(
            "genome_capsules",
            arrayOf(
                "id", "level", "scope_key", "topic_key", "summary",
                "importance", "updated_at", "child_ids_json", "evidence_ids_json"
            ),
            "level=1",
            null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                levelOne += GenomeCapsule(
                    id = cursor.getString(0),
                    level = cursor.getInt(1),
                    scopeKey = cursor.getString(2),
                    topicKey = cursor.getString(3),
                    summary = cursor.getString(4),
                    importance = cursor.getDouble(5),
                    updatedAt = cursor.getLong(6),
                    childIds = jsonList(cursor.getString(7)),
                    evidenceIds = jsonList(cursor.getString(8))
                )
            }
        }

        levelOne.groupBy { it.topicKey }.forEach { (topic, children) ->
            val top = children
                .sortedWith(
                    compareByDescending<GenomeCapsule> { it.importance }
                        .thenByDescending { it.updatedAt }
                )
                .take(6)
            val summary = buildString {
                append("topic=").append(topic)
                append(" · memories=").append(children.size)
                top.forEach { child ->
                    append("\n- ").append(child.summary.take(320))
                }
            }
            upsertCapsule(
                db,
                GenomeCapsule(
                    id = sha256("capsule:topic:$topic").take(24),
                    level = 2,
                    scopeKey = topic,
                    topicKey = topic,
                    summary = summary,
                    importance = children.sumOf { it.importance }.coerceAtMost(100.0),
                    updatedAt = now,
                    childIds = top.map { it.id },
                    evidenceIds = top.flatMap { it.evidenceIds }.distinct().take(32)
                )
            )
        }
    }

    private fun upsertCapsule(
        db: SQLiteDatabase,
        capsule: GenomeCapsule
    ) {
        val values = ContentValues().apply {
            put("id", capsule.id)
            put("level", capsule.level)
            put("scope_key", capsule.scopeKey)
            put("topic_key", capsule.topicKey)
            put("summary", capsule.summary)
            put("importance", capsule.importance)
            put("updated_at", capsule.updatedAt)
            put("child_ids_json", JSONArray(capsule.childIds).toString())
            put("evidence_ids_json", JSONArray(capsule.evidenceIds).toString())
        }
        db.insertWithOnConflict(
            "genome_capsules",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun mergeEvidenceIds(
        db: SQLiteDatabase,
        capsuleId: String,
        newIds: List<String>
    ): List<String> {
        val old = db.query(
            "genome_capsules",
            arrayOf("evidence_ids_json"),
            "id=?",
            arrayOf(capsuleId),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) jsonList(cursor.getString(0)) else emptyList()
        }
        return (old + newIds).distinct().takeLast(32)
    }

    private fun insertLink(
        db: SQLiteDatabase,
        fromId: String,
        toId: String,
        relation: String,
        now: Long
    ) {
        val values = ContentValues().apply {
            put("from_id", fromId)
            put("to_id", toId)
            put("relation", relation)
            put("created_at", now)
        }
        db.insertWithOnConflict(
            "genome_links",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun helper(context: Context): GenomeDbHelper =
        GenomeDbHelper(context.applicationContext)

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun targetOf(request: ToolRequest): String {
        val preferred = listOf("path", "script", "cwd", "model", "url", "name")
        val pair = preferred.firstNotNullOfOrNull { key ->
            request.args[key]?.trim()?.takeIf { it.isNotBlank() }?.let { key to it }
        }
        return pair?.let { (key, value) ->
            "$key=${sanitize(value, 220)}"
        }.orEmpty()
    }

    private fun topicOf(tool: String, target: String): String {
        if (target.isBlank()) return tool.substringBefore('.')
        val key = target.substringBefore('=')
        val value = target.substringAfter('=', "")
        if (value.startsWith("@")) return value.substringBefore('/')
        if (key == "model") return "model:$value"
        if (key == "url") {
            val hostish = value
                .substringAfter("://", value)
                .substringBefore('/')
                .take(120)
            return "web:$hostish"
        }
        val root = value
            .trimStart('/')
            .substringBefore('/')
            .ifBlank { value.take(120) }
        return "$key:$root"
    }

    private fun summarizeResult(result: ToolResult): String {
        if (result.ok) {
            val first = result.stdout.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
            return first?.let { "success: ${sanitize(it, 700)}" }
                ?: "success${result.exitCode?.let { " exit=$it" }.orEmpty()}"
        }
        val detail = sequenceOf(result.error, result.stderr, result.stdout)
            .filterNotNull()
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?: "tool failed"
        return "failure: ${sanitize(detail, 900)}"
    }

    private fun sanitize(value: String, maxChars: Int): String = value
        .replace('\u0000', ' ')
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .take(maxChars)

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun sha256(value: String): String =
        sha256Bytes(value.toByteArray())

    private fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun jsonList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    private class GenomeDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE genome_events (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    tool TEXT NOT NULL,
                    target TEXT NOT NULL,
                    signature TEXT NOT NULL,
                    args_hash TEXT NOT NULL,
                    ok INTEGER NOT NULL,
                    exit_code INTEGER,
                    result_hash TEXT NOT NULL,
                    evidence_excerpt TEXT NOT NULL,
                    result_gzip BLOB NOT NULL,
                    result_raw_bytes INTEGER NOT NULL,
                    result_stored_bytes INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE genome_anchors (
                    id TEXT PRIMARY KEY,
                    signature TEXT NOT NULL,
                    tool TEXT NOT NULL,
                    target TEXT NOT NULL,
                    valence TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    occurrences INTEGER NOT NULL,
                    first_seen_at INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL,
                    resolved_at INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE genome_links (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    from_id TEXT NOT NULL,
                    to_id TEXT NOT NULL,
                    relation TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    UNIQUE(from_id, to_id, relation)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE genome_capsules (
                    id TEXT PRIMARY KEY,
                    level INTEGER NOT NULL,
                    scope_key TEXT NOT NULL,
                    topic_key TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    importance REAL NOT NULL,
                    updated_at INTEGER NOT NULL,
                    child_ids_json TEXT NOT NULL,
                    evidence_ids_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_genome_events_signature ON genome_events(signature, created_at DESC)")
            db.execSQL("CREATE INDEX idx_genome_anchors_signature ON genome_anchors(signature, last_seen_at DESC)")
            db.execSQL("CREATE INDEX idx_genome_capsules_topic ON genome_capsules(topic_key, level, importance DESC)")
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            // v1 is the initial Context Genome schema.
        }
    }
}
