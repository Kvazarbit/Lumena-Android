package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Copy-before-compaction. AtomicFile rollback and StateVault retain the source bytes. */
object FractalEvidenceLedgerStore {
    const val FILE_NAME = "lumena_fractal_evidence_ledger_v1.json"
    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    fun load(context: Context): FractalEvidenceLedgerState = synchronized(StateVaultLock.monitor) {
        val atomic = file(context)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
            return@synchronized FractalEvidenceLedgerState()
        }
        // Missing is empty; corrupt/unreadable is a visible error, never a silent reset.
        FractalEvidenceLedgerCodec.decode(atomic.openRead().use {
            StateArchive.readBounded(it).toString(Charsets.UTF_8)
        })
    }

    fun append(context: Context, records: List<FractalExampleRecord>) = synchronized(StateVaultLock.monitor) {
        val current = load(context)
        val next = FractalEvidenceLedgerPolicy.append(current, records)
        persist(context, current, next)
    }

    /** Preserve individual failures even if no successful retry/sequence is ever formed. */
    fun recordEvent(
        context: Context, event: CoordinatorEpisodeEvent, previousEvents: List<CoordinatorEpisodeEvent>
    ) = synchronized(StateVaultLock.monitor) {
        val current = load(context)
        // Upgrade migration must precede the next bounded coordinator write,
        // even if the owner never opens the optional semantic-memory panel.
        val migrated = FractalEvidenceLedgerPolicy.backfillCoordinator(current, previousEvents)
        persist(context, current, FractalEvidenceLedgerPolicy.captureEvent(migrated, event))
    }

    fun backfillCoordinator(context: Context) = synchronized(StateVaultLock.monitor) {
        val current = load(context)
        if (current.coordinatorBackfillVersion < 1) {
            persist(context, current, FractalEvidenceLedgerPolicy.backfillCoordinator(
                current, CoordinatorExperienceStore.load(context).events))
        }
    }

    private fun persist(context: Context, current: FractalEvidenceLedgerState, next: FractalEvidenceLedgerState) {
        if (next != current) {
            val bytes = FractalEvidenceLedgerCodec.encode(next).toByteArray(Charsets.UTF_8)
            val atomic = file(context)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (failure: Exception) {
                atomic.failWrite(stream)
                throw failure
            }
            StateVault.requestSave(context)
        }
    }

    /** Called only by the existing explicit clear-experience operation. */
    fun clear(context: Context) = synchronized(StateVaultLock.monitor) { file(context).delete() }
}
