package com.lumena.android.settings

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lumena.android.ollama.LocalEmbeddingIdentity

data class SemanticMemoryStats(val rows: Int, val modelVersions: Int, val largestDimension: Int)

/**
 * App-private, bounded secondary embedding cache. The original Canvas remains
 * the authoritative proof store; a vector is never training/ToolGate authority.
 * SQLite file is allowlisted in StateArchive and included in StateVault.
 */
object SemanticMemoryStore {
    const val DATABASE = "lumena_semantic_memory_v0.db"
    private val lock = StateVaultLock.monitor
    private fun helper(context: Context) = Helper(context.applicationContext)

    fun stats(context: Context): SemanticMemoryStats = synchronized(lock) {
        helper(context).readableDatabase.use { db ->
            db.rawQuery(
                "SELECT COUNT(*), COUNT(DISTINCT model_key), COALESCE(MAX(dim),0) FROM semantic_vectors",
                null
            ).use { c ->
                if (c.moveToFirst()) SemanticMemoryStats(c.getInt(0), c.getInt(1), c.getInt(2))
                else SemanticMemoryStats(0, 0, 0)
            }
        }
    }

    /** A validated batch only. Never delete older negative or contested entries. */
    fun upsert(
        context: Context,
        model: LocalEmbeddingIdentity,
        documents: List<SemanticDocument>,
        vectors: List<FloatArray>
    ): Int = synchronized(lock) {
        require(documents.size in 1..SemanticMemoryPolicy.MAX_BATCH && documents.size == vectors.size)
        require(documents.map { it.id }.distinct().size == documents.size)
        require(model.digest.matches(Regex("[a-f0-9]{64}")) && model.sizeBytes >= 1_000_000)
        val normalized = vectors.map { SemanticMemoryPolicy.normalized(it.map(Float::toDouble)) }
        require(normalized.map { it.size }.distinct().size == 1)

        helper(context).writableDatabase.use { db ->
            db.beginTransaction()
            try {
                val total = db.rawQuery("SELECT COUNT(*) FROM semantic_vectors", null)
                    .use { c -> require(c.moveToFirst()); c.getInt(0) }
                val additional = documents.count { doc ->
                    db.rawQuery(
                        "SELECT 1 FROM semantic_vectors WHERE model_key=? AND id=? LIMIT 1",
                        arrayOf(model.cacheKey, doc.id)
                    ).use { !it.moveToFirst() }
                }
                val bytes = db.rawQuery("SELECT COALESCE(SUM(LENGTH(vector)),0) FROM semantic_vectors", null)
                    .use { c -> check(c.moveToFirst()); c.getLong(0) }
                val replacedBytes = documents.sumOf { doc ->
                    db.rawQuery("SELECT LENGTH(vector) FROM semantic_vectors WHERE model_key=? AND id=?",
                        arrayOf(model.cacheKey, doc.id)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                }
                require(bytes - replacedBytes + normalized.sumOf { it.size.toLong() * 4 } <=
                    SemanticMemoryPolicy.MAX_VECTOR_BYTES) { "Semantic cache byte budget reached; evidence retained" }
                require(total + additional <= SemanticMemoryPolicy.MAX_ROWS) {
                    "Semantic SHADOW cache capacity reached; no evidence deleted"
                }
                documents.zip(normalized).forEach { (doc, vec) ->
                    require(doc.id.isNotBlank() && doc.id.length <= 128)
                    require(doc.text.isNotBlank() && doc.text.length <= SemanticMemoryPolicy.MAX_TEXT)
                    require(doc.revision.matches(Regex("[a-f0-9]{64}")))
                    val row = ContentValues().apply {
                        put("model_key", model.cacheKey)
                        put("id", doc.id)
                        put("revision", doc.revision)
                        put("scope_hash", doc.scopeHash)
                        put("stage", doc.stage.name)
                        put("text", doc.text)
                        put("evidence_count", doc.evidenceCount)
                        put("dim", vec.size)
                        put("vector", SemanticMemoryPolicy.pack(vec))
                    }
                    require(db.insertWithOnConflict(
                        "semantic_vectors", null, row, SQLiteDatabase.CONFLICT_REPLACE
                    ) != -1L)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        StateVault.requestSave(context)
        documents.size
    }

    /** Reclaim only obsolete secondary vectors, never archived evidence. */
    fun prepareIndex(
        context: Context, model: LocalEmbeddingIdentity, live: List<SemanticDocument>
    ): Map<String, String> = synchronized(lock) {
        val liveIds = live.map { it.id }.toHashSet()
        helper(context).writableDatabase.use { db ->
            db.rawQuery("SELECT id, revision FROM semantic_vectors WHERE model_key=?",
                arrayOf(model.cacheKey)).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
            }.also { revisions ->
                db.beginTransaction()
                try {
                    (revisions.keys - liveIds).forEach { id ->
                        db.delete("semantic_vectors", "model_key=? AND id=?", arrayOf(model.cacheKey, id))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }.filterKeys { it in liveIds }
        }
    }

    /**
     * Only live exact source revisions can match; a deleted/changed principle
     * is never revived by cached bytes. Null scope retrieves global items only.
     */
    fun search(
        context: Context,
        model: LocalEmbeddingIdentity,
        query: FloatArray,
        live: List<SemanticDocument>,
        scopeHash: String? = null,
        limit: Int = 6
    ): List<SemanticHit> = synchronized(lock) {
        val vectors = mutableListOf<SemanticVector>()
        helper(context).readableDatabase.use { db ->
            db.query(
                "semantic_vectors",
                arrayOf("id", "revision", "dim", "vector"),
                "model_key=?", arrayOf(model.cacheKey),
                null, null, "id ASC", SemanticMemoryPolicy.MAX_ROWS.toString()
            ).use { c ->
                while (c.moveToNext()) {
                    val data = runCatching {
                        SemanticMemoryPolicy.unpack(c.getBlob(3), c.getInt(2))
                    }.getOrNull() ?: continue
                    vectors += SemanticVector(c.getString(0), c.getString(1), data)
                }
            }
        }
        SemanticMemoryPolicy.rank(query, vectors, live, scopeHash, limit)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DATABASE, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE semantic_vectors(
                    model_key TEXT NOT NULL,
                    id TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    scope_hash TEXT,
                    stage TEXT NOT NULL,
                    text TEXT NOT NULL,
                    evidence_count INTEGER NOT NULL,
                    dim INTEGER NOT NULL,
                    vector BLOB NOT NULL,
                    PRIMARY KEY(model_key, id)
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX idx_semantic_stage ON semantic_vectors(model_key, stage)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Semantic Memory schema migration must be non-destructive")
        }
    }
}
