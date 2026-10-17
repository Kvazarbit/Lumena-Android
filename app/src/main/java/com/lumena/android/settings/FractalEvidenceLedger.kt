package com.lumena.android.settings

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class FractalEvidenceRevision(val revision: String, val record: FractalExampleRecord)
data class FractalEvidenceLedgerState(
    val version: Int = 1,
    val entries: List<FractalEvidenceRevision> = emptyList()
)

/** Immutable source snapshots, separate from the 512-record working Canvas. */
object FractalEvidenceLedgerPolicy {
    const val MAX_REVISIONS = 10_000
    const val MAX_BYTES = 32 * 1024 * 1024

    fun append(
        state: FractalEvidenceLedgerState,
        incoming: List<FractalExampleRecord>
    ): FractalEvidenceLedgerState {
        val known = state.entries.map { it.revision }.toHashSet()
        val receipts = state.entries.associate { it.record.id to it.record }.toMutableMap()
        val added = incoming.mapNotNull { record ->
            receipts[record.id]?.let { previous ->
                require(previous.tools == record.tools && previous.outcomes == record.outcomes &&
                    previous.evidenceIds == record.evidenceIds && previous.sourceTaskHash == record.sourceTaskHash) {
                    "A historical receipt cannot change its observed outcome; record a new incident"
                }
            }
            receipts[record.id] = record
            val revision = FractalEvidenceLedgerCodec.revision(record)
            if (!known.add(revision)) null else FractalEvidenceRevision(revision, record)
        }
        require(state.entries.size + added.size <= MAX_REVISIONS) {
            "Evidence archive full; refusing to discard historical evidence"
        }
        added.forEach { validate(it.record) }
        return if (added.isEmpty()) state else state.copy(entries = state.entries + added)
    }

    /** Revisions stay in the archive; a projection uses the latest eligible snapshot per ID. */
    fun latestRecords(state: FractalEvidenceLedgerState): List<FractalExampleRecord> {
        val selected = linkedMapOf<String, FractalExampleRecord>()
        state.entries.forEach { (_, candidate) ->
            val old = selected[candidate.id]
            if (old == null ||
                (candidate.origin == old.origin && candidate.updatedAt >= old.updatedAt) ||
                (old.origin == FractalExperienceOrigin.LEGACY_BACKFILL &&
                    candidate.origin == FractalExperienceOrigin.LIVE)) {
                selected[candidate.id] = candidate
            }
        }
        return selected.values.toList()
    }

    fun validate(record: FractalExampleRecord) {
        record.conditions.validate()
        require(record.argumentFingerprints.isEmpty() || record.argumentFingerprints.size == record.tools.size)
        require(record.argumentFingerprints.filterNotNull().all { it.matches(Regex("[a-f0-9]{64}")) })
        val known = com.lumena.android.agent.core.ToolRegistry.kernelToolNames() +
            com.lumena.android.agent.core.ModuleRegistry
                .accept(com.lumena.android.agent.core.ModuleRegistry.all())
                .flatMap { it.manifest.tools.map { entry -> entry.spec.name } }
        require(record.id.length in 1..128 && record.sourceTaskHash.length in 1..64)
        require(record.tools.size in 1..16 && record.tools.all { it in known })
        require(record.outcomes.size == record.tools.size)
        require(record.targets.size <= 16 && record.targets.all { it.length <= 240 })
        require(record.evidenceIds.size in 1..64 && record.evidenceIds.all { it.length in 1..160 })
        require(record.contributorModelIds.size <= 16 && record.contributorModelIds.all { it.length <= 160 })
        require(record.scopeHash == null || record.scopeHash.length <= 64)
        require(record.updatedAt > 0 && record.summary.length <= 900)
        listOf(record.failureClasses, record.errorCodes).forEach { values ->
            require(values.isEmpty() || values.size == record.tools.size)
            require(values.filterNotNull().all { it.length <= 120 })
        }
        require(record.dependencies.isEmpty() || record.dependencies.size == record.tools.size)
        require(record.dependencies.filterNotNull().all { it.length <= 160 })
        require(record.retryableFlags.isEmpty() || record.retryableFlags.size == record.tools.size)
    }
}

object FractalEvidenceLedgerCodec {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val records = moshi.adapter(FractalExampleRecord::class.java)
    private val ledger = moshi.adapter(FractalEvidenceLedgerState::class.java)
    fun revision(record: FractalExampleRecord): String =
        SemanticMemoryPolicy.hash(records.toJson(record))

    fun encode(state: FractalEvidenceLedgerState): String = ledger.toJson(state).also {
        require(it.toByteArray(Charsets.UTF_8).size <= FractalEvidenceLedgerPolicy.MAX_BYTES) {
            "Evidence archive full; no historical evidence deleted"
        }
    }

    fun decode(json: String): FractalEvidenceLedgerState {
        require(json.toByteArray(Charsets.UTF_8).size <= FractalEvidenceLedgerPolicy.MAX_BYTES)
        val parsed = requireNotNull(ledger.fromJson(json))
        require(parsed.version == 1 && parsed.entries.size <= FractalEvidenceLedgerPolicy.MAX_REVISIONS)
        require(parsed.entries.map { it.revision }.distinct().size == parsed.entries.size)
        parsed.entries.forEach {
            FractalEvidenceLedgerPolicy.validate(it.record)
            require(it.revision == revision(it.record)) { "Evidence archive revision mismatch" }
        }
        return parsed
    }
}
