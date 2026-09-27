package com.lumena.android.settings

/** Startup-only journal. Interrupted application always rolls back before normal app startup. */
object StateRestoreTransaction {
    fun run(pending: ByteArray?, rollback: ByteArray?, current: () -> ByteArray,
            saveRollback: (ByteArray) -> Unit, apply: (ByteArray, Boolean) -> Unit,
            clearPending: () -> Unit, clearRollback: () -> Unit): String? {
        if (rollback != null) {
            apply(rollback, false)
            clearPending()
            clearRollback()
            return "Незавершене відновлення скасовано; попередній стан повернуто"
        }
        if (pending == null) return null
        // Caller validates pending before invoking this transaction.
        saveRollback(current())
        apply(pending, true)
        clearPending()
        clearRollback()
        return "Стан відновлено. Дозволи й незавершені дії не активовано."
    }
}
