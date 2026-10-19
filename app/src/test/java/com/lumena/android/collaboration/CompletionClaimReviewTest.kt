package com.lumena.android.collaboration

import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.*
import org.junit.Test

class CompletionClaimReviewTest {
    private val p = WorkspaceProject("p", "Demo", "demo", "Improve", updatedAt = 1)
    private val build = "f".repeat(40)
    private val entry = CollaborationEntry(id = "result", intentId = "intent", kind = "RESULT",
        scope = CollaborationScope(p.id, p.root, CollaborationProtocol.goalRevision(p), "t", "s", "m", "LOCAL", "a".repeat(64), "test", build),
        tool = "python.tests", requestDigest = "b".repeat(64), deliveryKey = "c".repeat(64), mutating = true, targets = mapOf("cwd" to "demo"),
        result = ToolResult(true, exitCode = 0, stdout = "2 passed"), stdoutUtf8Sha256 = CollaborationProtocol.hash("2 passed"),
        at = 1, sourceDigest = "d".repeat(64))
    private fun problem(text: String, entries: List<CollaborationEntry> = listOf(entry), current: SourceObservation = SourceObservation("d".repeat(64))) =
        CompletionClaimReview.problem(text, entries, p, build, current)
    @Test fun positiveTestClaimRequiresCurrentReceiptCountAndSource() {
        assertNull(problem("2 tests passed."))
        assertNotNull(problem("3 tests passed."))
        assertNotNull(problem("2 tests passed.", emptyList()))
        assertNotNull(problem("2 tests passed.", current = SourceObservation("e".repeat(64))))
        assertNotNull(problem("2 tests passed.", current = SourceObservation.UNKNOWN))
        assertNotNull(problem("2 tests passed.", listOf(entry.copy(sourceDigest = null))))
        val proof = com.lumena.android.agent.local.VerificationEvidence("a".repeat(64), "b".repeat(64), "c".repeat(64), 2,
            true, true, testFiles = listOf("tests/test_calc.py"))
        assertNull(problem("tests/test_calc.py passed 2 tests.", listOf(entry.copy(result = entry.result!!.copy(verification = proof)))))
        assertNotNull(problem("other/test_calc.py passed 2 tests.", listOf(entry.copy(result = entry.result!!.copy(verification = proof)))))
    }
    @Test fun negativeAndUnrecognizedNarrativeIsLeftToGoalController() {
        assertNull(problem("Tests failed earlier; the owner should review the files."))
        assertNull(problem("Read the notes."))
    }
    @Test fun newerUnknownCannotBorrowEarlierSuccess() {
        val unknown = entry.copy(id = "unknown", at = 2, result = entry.result!!.copy(ok = false, outcomeUnknown = true))
        assertNotNull(problem("2 tests passed.", listOf(entry, unknown)))
    }
}
