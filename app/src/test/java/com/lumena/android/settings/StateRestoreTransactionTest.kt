package com.lumena.android.settings

import org.junit.Assert.*
import org.junit.Test

class StateRestoreTransactionTest {
    @Test fun interruptedRestoreRollsBackBeforeAcceptingFurtherWork() {
        var current = "old".toByteArray()
        var pending: ByteArray? = "new".toByteArray()
        var rollback: ByteArray? = null
        try {
            StateRestoreTransaction.run(pending, rollback, { current }, { rollback = it },
                { _, _ -> current = "mixed-partial".toByteArray(); throw IllegalStateException("power loss") },
                { pending = null }, { rollback = null })
            fail("Expected interruption")
        } catch (_: IllegalStateException) { }
        assertNotNull(rollback)
        var sanitized = true
        StateRestoreTransaction.run(pending, rollback, { error("must not snapshot mixed state") },
            { error("must not replace rollback") }, { bytes, safe -> current = bytes; sanitized = safe },
            { pending = null }, { rollback = null })
        assertEquals("old", String(current))
        assertFalse(sanitized)
        assertNull(pending); assertNull(rollback)
    }
    @Test fun successfulRestoreSanitizesAndCommitsInOrder() {
        val steps = mutableListOf<String>()
        StateRestoreTransaction.run(byteArrayOf(2), null, { steps += "snapshot"; byteArrayOf(1) },
            { steps += "journal" }, { _, safe -> assertTrue(safe); steps += "apply" },
            { steps += "clear-pending" }, { steps += "clear-journal" })
        assertEquals(listOf("snapshot", "journal", "apply", "clear-pending", "clear-journal"), steps)
    }
    @Test fun failedJournalWriteNeverTouchesWorkingState() {
        var applied = false
        try {
            StateRestoreTransaction.run(byteArrayOf(2), null, { byteArrayOf(1) },
                { throw IllegalStateException("full disk") }, { _, _ -> applied = true }, {}, {})
            fail("Expected disk failure")
        } catch (_: IllegalStateException) { }
        assertFalse(applied)
    }
}
