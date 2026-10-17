package com.lumena.android.settings

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class FractalEvidenceRevision(val revision: String, val record: FractalExampleRecord)
data class FractalUnresolvedIncident(
    val id: String,
    val sourceTaskHash: String,
    val tool: String,
    val scopeHash: String?,
    val argumentFingerprint: String?,
    val conditions: ExperienceConditions,
    val at: Long,
    val observedOutcome: String = "UNKNOWN"
)
data class FractalEvidenceLedgerState(
    val version: Int = 1,
    val entries: List<FractalEvidenceRevision> = emptyList(),
    val unresolved: List<FractalUnresolvedIncident> = emptyList(),
    val coordinatorBackfillVersion: Int = 0
)

/** Immutable source snapshots, separate from the 512-record working Canvas. */
object FractalEvidenceLedgerPolicy {
    const val MAX_REVISIONS = 10_000
    const val MAX_BYTES = 32 * 1024 * 1024

    fun backfillCoordinator(
        state: FractalEvidenceLedgerState, events: List<CoordinatorEpisodeEvent>
    ): FractalEvidenceLedgerState {
        if (state.coordinatorBackfillVersion >= 1) return state
        return events.fold(state) { current, event -> captureEvent(current, event) }
            .copy(coordinatorBackfillVersion = 1)
    }

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
        require(state.entries.size + state.unresolved.size + added.size <= MAX_REVISIONS) {
            "Evidence archive full; refusing to discard historical evidence"
        }
        added.forEach { validate(it.record) }
        return if (added.isEmpty()) state else state.copy(entries = state.entries + added)
    }

    /** Outcome-unknown observations are durable barriers, never boolean training labels. */
    fun captureEvent(state: FractalEvidenceLedgerState, event: CoordinatorEpisodeEvent): FractalEvidenceLedgerState {
        val taskHash = CoordinatorExperiencePolicy.hash(event.sessionId + "|" + (event.taskId ?: event.sessionId)).take(16)
        if (!event.outcomeKnown || event.experienceId.isNullOrBlank()) {
            return appendUnresolved(state,
                FractalUnresolvedIncident("incident-" + event.id, taskHash, event.tool, event.scopeHash,
                    event.argumentFingerprint, event.conditions, event.at))
        } else {
            return append(state, listOf(FractalExampleRecord(
                id = "receipt-" + event.id,
                kind = CoordinatorExampleKind.TOOL_OUTCOME,
                sourceTaskHash = taskHash,
                tools = listOf(event.tool), targets = listOf(event.target.take(240)),
                outcomes = listOf(event.ok), evidenceIds = listOf(event.experienceId),
                contributorModelIds = listOfNotNull(event.modelId), scopeHash = event.scopeHash,
                updatedAt = event.at, summary = "Individual recorded tool outcome; not a goal proof",
                failureClasses = listOf(event.failureClass), errorCodes = listOf(event.errorCode),
                retryableFlags = listOf(event.retryable), dependencies = listOf(event.dependency),
                conditions = event.conditions, argumentFingerprints = listOf(event.argumentFingerprint)
            )))
        }
    }

    fun appendUnresolved(
        state: FractalEvidenceLedgerState, incident: FractalUnresolvedIncident
    ): FractalEvidenceLedgerState {
        validateUnresolved(incident)
        state.unresolved.firstOrNull { it.id == incident.id }?.let {
            require(it == incident) { "Unknown incident identity changed" }
            return state
        }
        require(state.entries.size + state.unresolved.size < MAX_REVISIONS) { "Evidence archive full" }
        return state.copy(unresolved = state.unresolved + incident)
    }

    fun validateUnresolved(incident: FractalUnresolvedIncident) {
        incident.conditions.validate()
        require(incident.id.length in 1..128 && incident.sourceTaskHash.length in 1..64)
        require(incident.tool.matches(Regex("[a-z][a-z0-9_.]{1,80}")))
        require(incident.scopeHash == null || incident.scopeHash.length <= 64)
        require(incident.argumentFingerprint == null || incident.argumentFingerprint.matches(Regex("[a-f0-9]{64}")))
        require(incident.at > 0 && incident.observedOutcome == "UNKNOWN")
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
        require(parsed.version == 1 && parsed.entries.size + parsed.unresolved.size <= FractalEvidenceLedgerPolicy.MAX_REVISIONS)
        require(parsed.coordinatorBackfillVersion in 0..1)
        require(parsed.entries.map { it.revision }.distinct().size == parsed.entries.size)
        require(parsed.unresolved.map { it.id }.distinct().size == parsed.unresolved.size)
        parsed.unresolved.forEach(FractalEvidenceLedgerPolicy::validateUnresolved)
        parsed.entries.forEach {
            FractalEvidenceLedgerPolicy.validate(it.record)
            require(it.revision == revision(it.record)) { "Evidence archive revision mismatch" }
        }
        // The same identity cannot rewrite a receipt through a hand-edited archive.
        parsed.entries.groupBy { it.record.id }.values.forEach { revisions ->
            val first = revisions.first().record
            require(revisions.all { it.record.tools == first.tools && it.record.outcomes == first.outcomes &&
                it.record.evidenceIds == first.evidenceIds && it.record.sourceTaskHash == first.sourceTaskHash })
        }
        return parsed
    }
}
