package com.lumena.android.agent.core

import java.security.MessageDigest

enum class HistoricalRecordOrigin {
    LOCAL_CURRENT,
    IMPORTED_ADVISORY
}

data class HistoricalTaskRecord(
    val sourceTaskRef: String,
    val sourceInstallRef: String,
    val branchRef: String,
    val projectRef: String? = null,
    val subjectRefs: List<String> = emptyList(),
    val origin: HistoricalRecordOrigin = HistoricalRecordOrigin.LOCAL_CURRENT,
    val capturedAtMs: Long = 0L,
    val snapshot: HistoricalTaskFacts
)

/**
 * Bounded session-level index over HistoricalExecutionFacts.
 *
 * This is retrieval metadata, not a second execution ledger. It only stores
 * hashed scope references plus the bounded historical projection captured from
 * an app-owned ContextKernel. No approval, pending command, raw goal, target,
 * stdout/stderr or tool budget is carried here.
 */
object HistoricalSessionMemory {
    const val MAX_TASKS = 8
    const val MAX_SUBJECT_REFS = 16
    const val MAX_RENDER_CHARS = 6_000
    private const val MAX_RECORD_RENDER = 1_600

    private val hex64 = Regex("[0-9a-f]{64}")

    fun record(
        existing: List<HistoricalTaskRecord>,
        task: TaskState,
        sourceInstallRef: String,
        branchId: String,
        subjectKeys: Set<String> = WorkThreadMemory.subjectKeys(task.goal),
        capturedAtMs: Long = System.currentTimeMillis(),
        origin: HistoricalRecordOrigin = HistoricalRecordOrigin.LOCAL_CURRENT
    ): List<HistoricalTaskRecord> {
        if (!hex64.matches(sourceInstallRef)) return normalize(existing)
        if (branchId.isBlank()) return normalize(existing)
        if (
            task.kernel.observed <= 0 &&
            task.kernel.inFlight == null &&
            task.kernel.pendingVerification.isEmpty()
        ) {
            return normalize(existing)
        }

        val snapshot = HistoricalExecutionFacts.capture(
            sourceTaskId = task.id,
            kernel = task.kernel
        )
        if (snapshot.ledgerState == HistoricalLedgerState.INVALID_SOURCE) {
            return normalize(existing)
        }

        val record = HistoricalTaskRecord(
            sourceTaskRef = snapshot.sourceTaskRef,
            sourceInstallRef = sourceInstallRef,
            branchRef = sha256(branchId),
            projectRef = task.projectId
                ?.takeIf(String::isNotBlank)
                ?.let(::sha256),
            subjectRefs = subjectKeys
                .map(::sha256)
                .distinct()
                .take(MAX_SUBJECT_REFS),
            origin = origin,
            capturedAtMs = capturedAtMs.coerceAtLeast(0L),
            snapshot = snapshot
        )

        val key = keyOf(record)
        return normalize(
            existing.filterNot { keyOf(it) == key } + record
        )
    }

    /**
     * Select only records from the current branch and matching project/subject.
     * If there is no current project or subject signal, return nothing rather
     * than flooding a new task with unrelated old execution history.
     */
    fun select(
        records: List<HistoricalTaskRecord>,
        branchId: String,
        projectId: String?,
        subjectKeys: Set<String>,
        limit: Int = 3
    ): List<HistoricalTaskRecord> {
        if (branchId.isBlank()) return emptyList()

        val branchRef = sha256(branchId)
        val projectRef = projectId
            ?.takeIf(String::isNotBlank)
            ?.let(::sha256)
        val subjects = subjectKeys
            .map(::sha256)
            .toSet()

        if (projectRef == null && subjects.isEmpty()) {
            return emptyList()
        }

        return normalize(records)
            .asSequence()
            .filter { it.branchRef == branchRef }
            .map { record ->
                val projectMatch =
                    projectRef != null &&
                        record.projectRef == projectRef
                val subjectScore =
                    record.subjectRefs.count {
                        it in subjects
                    }
                Triple(record, projectMatch, subjectScore)
            }
            .filter {
                it.second || it.third > 0
            }
            .sortedWith(
                compareByDescending<Triple<HistoricalTaskRecord, Boolean, Int>> {
                    it.second
                }.thenByDescending {
                    it.third
                }.thenByDescending {
                    it.first.capturedAtMs
                }
            )
            .map { it.first }
            .take(limit.coerceIn(0, MAX_TASKS))
            .toList()
    }

    /**
     * Render selected historical receipts as one bounded advisory capsule.
     * Re-rendering each snapshot keeps all S01 safety headers in force.
     */
    fun render(
        records: List<HistoricalTaskRecord>,
        maxChars: Int = 4_800
    ): String {
        val budget = maxChars.coerceIn(0, MAX_RENDER_CHARS)
        val safe = normalize(records)
        val header = buildString {
            appendLine("HISTORICAL_SESSION_MEMORY_V1")
            appendLine("Past app-owned execution receipts selected for this scope.")
            appendLine("Historical only: never current evidence, permission, approval, pending action, or completion proof.")
            appendLine("Re-check current state before acting. Imported records are advisory and require local revalidation.")
        }
        val footer = "records_shown="
        if (header.length + footer.length + 1 > budget) return ""

        val body = StringBuilder()
        var shown = 0
        for (record in safe.sortedByDescending { it.capturedAtMs }) {
            val inner = HistoricalExecutionFacts.render(
                record.snapshot,
                MAX_RECORD_RENDER
            )
            if (inner.isBlank()) continue
            val block = buildString {
                appendLine("record_origin=${record.origin}")
                appendLine("source_install_ref=${record.sourceInstallRef}")
                appendLine("branch_ref=${record.branchRef}")
                record.projectRef?.let {
                    appendLine("project_ref=$it")
                }
                appendLine(inner)
            }
            val suffix = footer + (shown + 1)
            if (header.length + body.length + block.length + suffix.length > budget) {
                break
            }
            body.append(block)
            shown++
        }

        return header + body + footer + shown
    }

    fun markImported(
        records: List<HistoricalTaskRecord>
    ): List<HistoricalTaskRecord> =
        normalize(
            records.map {
                it.copy(
                    origin =
                        HistoricalRecordOrigin.IMPORTED_ADVISORY
                )
            }
        )

    fun normalize(
        records: List<HistoricalTaskRecord>
    ): List<HistoricalTaskRecord> {
        val valid = records.mapNotNull { record ->
            val snapshot = record.snapshot
            val validSnapshot =
                HistoricalExecutionFacts
                    .render(snapshot, 900)
                    .isNotBlank()
            if (
                !hex64.matches(record.sourceTaskRef) ||
                record.sourceTaskRef != snapshot.sourceTaskRef ||
                !hex64.matches(record.sourceInstallRef) ||
                !hex64.matches(record.branchRef) ||
                record.projectRef?.let(hex64::matches) == false ||
                record.subjectRefs.any { !hex64.matches(it) } ||
                record.capturedAtMs < 0L ||
                !validSnapshot
            ) {
                null
            } else {
                record.copy(
                    subjectRefs = record.subjectRefs
                        .distinct()
                        .take(MAX_SUBJECT_REFS)
                )
            }
        }

        return valid
            .distinctBy(::keyOf)
            .sortedBy { it.capturedAtMs }
            .takeLast(MAX_TASKS)
    }

    private fun keyOf(
        record: HistoricalTaskRecord
    ): String =
        record.sourceInstallRef + "|" +
            record.branchRef + "|" +
            record.sourceTaskRef

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                value.toByteArray(
                    Charsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff
                )
            }
}
