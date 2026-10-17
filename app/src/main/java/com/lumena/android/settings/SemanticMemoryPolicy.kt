package com.lumena.android.settings

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/** Embeddings provide only advisory retrieval, never permissions or goal proof. */
data class SemanticDocument(
    val id: String,
    val stage: CognitivePrincipleStage,
    val scopeHash: String?,
    val text: String,
    val revision: String,
    val evidenceCount: Int
)
data class SemanticVector(val id: String, val revision: String, val values: FloatArray)
data class SemanticHit(
    val id: String,
    val stage: CognitivePrincipleStage,
    val score: Double,
    val scopeHash: String?,
    val text: String,
    val evidenceCount: Int
)

object SemanticMemoryPolicy {
    const val VERSION = "embeddinggemma2-shadow-v1-context"
    const val MAX_BATCH = 4
    const val MAX_SESSION = 48
    const val MAX_ROWS = 4096
    const val MAX_VECTOR_BYTES = 16 * 1024 * 1024
    const val MAX_DIM = 4096
    const val MAX_TEXT = 700
    const val MAX_QUERY = 320

    fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Only structured, bounded V0 fields; never raw model prose or tool output. */
    fun documents(principles: List<CognitivePrinciple>, limit: Int = MAX_SESSION): List<SemanticDocument> {
        require(limit in 1..CognitiveGenomePolicy.MAX_ARCHIVED_DERIVED)
        fun priority(stage: CognitivePrincipleStage): Int = when (stage) {
            CognitivePrincipleStage.KERNEL_CONTRACT -> 0
            CognitivePrincipleStage.NEGATIVE_DEAD_END -> 1
            CognitivePrincipleStage.CONTESTED -> 2
            CognitivePrincipleStage.RECOVERY_CANDIDATE -> 3
            CognitivePrincipleStage.POSITIVE_CANDIDATE -> 4
        }
        return principles.sortedWith(compareBy<CognitivePrinciple> { priority(it.stage) }.thenBy { it.id })
            .distinctBy { it.id }.take(limit).map { p ->
                p.conditions.validate()
                val text = ("stage=" + p.stage.name +
                    " problem_class_hint=" + p.conditions.problemClass +
                    " resource_types=" + p.targetKinds.joinToString(",") +
                    " operation=" + p.toolPattern +
                    " failure_classes=" + p.failureClasses.joinToString(",") +
                    " cause=" + p.causeKnowledge +
                    " expected_checks=" + p.conditions.requiredPostconditions.joinToString(",") +
                    " observed=" + p.observedPostcondition +
                    " mechanism=" + p.mechanism + " condition=" + p.condition).take(MAX_TEXT)
                // Include applicability even if the bounded embedding text was truncated.
                val revision = hash(listOf(VERSION, p.id,
                    ExperienceScope.normalized(p.scopeHash).orEmpty(), text, p.codons,
                    p.conditions.toString(), p.applicabilityFingerprint,
                    p.dependencyFingerprints.sorted().toString(), p.evidenceIds.sorted().toString(),
                    p.sourceTaskHashes.sorted().toString(), p.sourceRecordIds.sorted().toString(),
                    p.counterexampleRecordIds.sorted().toString(),
                    p.observedSuccesses.toString(), p.observedFailures.toString(),
                    p.independentGoalProofs.toString(), p.contractTestAnchors.sorted().toString()
                ).joinToString("") { "${it.length}:$it" })
                SemanticDocument(p.id, p.stage, ExperienceScope.normalized(p.scopeHash),
                    text, revision, p.evidenceIds.size)

            }
    }

    /** Advance by source revision, not by repeatedly selecting the first page. */
    fun pending(
        documents: List<SemanticDocument>,
        indexedRevisions: Map<String, String>,
        limit: Int = MAX_SESSION
    ): List<SemanticDocument> {
        require(limit in 1..MAX_SESSION)
        return documents.filter { indexedRevisions[it.id] != it.revision }.take(limit)
    }

    fun normalized(numbers: List<Double>): FloatArray {
        require(numbers.size in 2..MAX_DIM && numbers.all {
            it.isFinite() && kotlin.math.abs(it) <= 1e10
        }) { "Invalid embedding vector" }
        val n = sqrt(numbers.sumOf { it * it })
        require(n.isFinite() && n > 1e-12) { "Zero embedding" }
        return FloatArray(numbers.size) { (numbers[it] / n).toFloat() }
    }

    fun pack(values: FloatArray): ByteArray {
        require(values.size in 2..MAX_DIM && values.all(Float::isFinite))
        val b = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { b.putFloat(it) }
        return b.array()
    }

    fun unpack(bytes: ByteArray, dimensions: Int): FloatArray {
        require(dimensions in 2..MAX_DIM && bytes.size == dimensions * 4)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimensions) { buf.float }.also { require(it.all(Float::isFinite)) }
    }

    /** Global query sees only global rules; project query sees global + SAME project. */
    fun rank(
        query: FloatArray,
        vectors: List<SemanticVector>,
        liveDocuments: List<SemanticDocument>,
        scopeHash: String? = null,
        limit: Int = 6
    ): List<SemanticHit> {
        require(limit in 1..20)
        val q = normalized(query.map(Float::toDouble))
        val current = liveDocuments.associateBy { it.id }
        return vectors.mapNotNull { entry ->
            val doc = current[entry.id] ?: return@mapNotNull null
            if (doc.revision != entry.revision) return@mapNotNull null
            if (!ExperienceScope.visible(doc.scopeHash, scopeHash)) return@mapNotNull null
            if (q.size != entry.values.size) return@mapNotNull null
            val vec = runCatching { normalized(entry.values.map(Float::toDouble)) }.getOrNull()
                ?: return@mapNotNull null
            val similarity = q.indices.sumOf { q[it].toDouble() * vec[it].toDouble() }
                .coerceIn(-1.0, 1.0)
            SemanticHit(doc.id, doc.stage, similarity, doc.scopeHash, doc.text, doc.evidenceCount)
        }.sortedWith(compareByDescending<SemanticHit> { it.score }.thenBy { it.id })
            .take(limit)
    }
}
