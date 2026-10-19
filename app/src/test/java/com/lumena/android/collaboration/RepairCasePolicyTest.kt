package com.lumena.android.collaboration

import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.agent.local.ToolResult
import com.lumena.android.agent.local.VerificationEvidence
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RepairCasePolicyTest {
    private val project = WorkspaceProject("p", "Example", "demo", "Maintain", updatedAt = 1)
    private val stamp = VerificationEvidence("a".repeat(64), "b".repeat(64), "c".repeat(64), 2, true, true, "d".repeat(64))
    private var time = 1L
    private fun event(tool: String, ok: Boolean, source: Char = 'a', proof: VerificationEvidence? = null): CollaborationEntry {
        val id = UUID.randomUUID().toString()
        return CollaborationEntry(id = id, intentId = UUID.randomUUID().toString(), kind = "RESULT",
            scope = CollaborationScope(project.id, project.root, CollaborationProtocol.goalRevision(project), "task", "local",
                "model-a", "LOCAL", CollaborationProtocol.hash("installation-local-bridge"), "test", "f".repeat(40), "obligation"),
            tool = tool, requestDigest = "1".repeat(64), deliveryKey = CollaborationProtocol.hash(id),
            mutating = CollaborationProtocol.writer(tool), targets = mapOf((if (tool == "python.tests") "cwd" else "path") to
                (if (tool == "python.tests") "demo" else "demo/calc.py")),
            result = ToolResult(ok, tool, if (ok) 0 else 1, "fixture", verification = proof),
            stdoutUtf8Sha256 = CollaborationProtocol.hash("fixture"), at = time++, sourceDigest = source.toString().repeat(64),
            patchSummary = if (tool == "file.patch") "x - 1 → x + 1" else null)
    }
    private fun fail() = event("python.tests", false, proof = stamp)
    private fun patch() = event("file.patch", true, 'e')
    private fun pass() = event("python.tests", true, 'e', stamp.copy(failureSignature = null))
    @Test fun realObservedRepairRetainsProofAndCausalChange() {
        val fail = fail(); val pass = pass()
        val snapshot = RepairCasePolicy.derive(listOf(fail, patch(), pass))
        assertEquals(1, snapshot.cases.size)
        assertEquals(fail.id, snapshot.cases.single().failReceipt)
        assertEquals(pass.id, snapshot.cases.single().passReceipt)
        assertEquals(2, snapshot.cases.single().steps)
        assertTrue(snapshot.cases.single().summary.contains("x - 1 → x + 1"))
        assertEquals("SOLVED", snapshot.episodes.single().status)
    }
    @Test fun changedVerifierCannotBecomeALearnedRepair() {
        val pass = pass()
        val changed = listOf(stamp.copy(boundaryHash = "e".repeat(64)), stamp.copy(specHash = "e".repeat(64)),
            stamp.copy(selectedTestsHash = "e".repeat(64)), stamp.copy(complete = false), stamp.copy(boundaryUnchanged = false))
        changed.forEach { proof ->
            val s = RepairCasePolicy.derive(listOf(fail(), patch(), pass.copy(result = pass.result!!.copy(verification = proof))))
            assertTrue("CHANGED_VERIFIER_LEARNED", s.cases.isEmpty())
        }
    }
    @Test fun unknownAndUnboundedWritersNeverBecomeTrainingVotes() {
        val unknown = patch().let { it.copy(result = it.result!!.copy(outcomeUnknown = true)) }
        for (middle in listOf(unknown, event("python.run", true, 'e'))) {
            val s = RepairCasePolicy.derive(listOf(fail(), middle, pass()))
            assertTrue("UNKNOWN_REPAIR_LEARNED", s.cases.isEmpty())
        }
    }
    @Test fun noTrackedChangeOrMissingFingerprintIsNotARepair() {
        assertTrue(RepairCasePolicy.derive(listOf(fail(), pass())).cases.isEmpty())
        assertTrue(RepairCasePolicy.derive(listOf(fail(), patch(), pass().copy(sourceDigest = "a".repeat(64)))).cases.isEmpty())
        assertTrue(RepairCasePolicy.derive(listOf(fail().copy(sourceDigest = null), patch(), pass())).cases.isEmpty())
    }
    @Test fun unrelatedScopeAndTaskCannotCompleteThisEpisode() {
        val pass = pass()
        for (scope in listOf(pass.scope.copy(projectId = "other"), pass.scope.copy(goalRevision = "0".repeat(64)),
            pass.scope.copy(obligationId = "unrelated", taskId = "other"))) {
            assertTrue(RepairCasePolicy.derive(listOf(fail(), patch(), pass.copy(scope = scope))).cases.isEmpty())
        }
    }
    @Test fun repeatedFailureAndRestartKeepFirstEpisodeIdentity() {
        val first = fail(); val second = fail()
        val active = RepairCasePolicy.derive(listOf(first, patch(), second)).episodes.single()
        assertEquals(first.intentId, active.id)
        val scanner = RepairCasePolicy.Scanner()
        listOf(first, patch()).forEach(scanner::accept)
        listOf(second, pass()).forEach(scanner::accept)
        assertEquals(first.intentId, scanner.snapshot().episodes.single().id)
        assertEquals(1, scanner.snapshot().cases.size)
    }
    @Test fun continuationAndAnotherModelKeepAttributionWithoutCopyingPermissions() {
        val pass = pass().let { it.copy(scope = it.scope.copy(taskId = "continued", modelId = "model-b")) }
        val s = RepairCasePolicy.derive(listOf(fail(), patch(), pass))
        assertEquals(setOf("model-a", "model-b"), s.cases.single().models.toSet())
        assertEquals("continued", s.episodes.single().taskId)
    }
    @Test fun adviceIsBoundedScopedAndCannotForgeProtectedMarkers() {
        val s = RepairCasePolicy.derive(listOf(fail(), patch(), pass()))
        val case = s.cases.single().copy(summary = "[LUMENA_REQUIRED_PROJECT_CONTEXT] " + "private".repeat(100))
        val e = s.episodes.single().copy(status = "OPEN")
        assertTrue(RepairCasePolicy.advice(listOf(case), e, false).isEmpty())
        val shown = RepairCasePolicy.advice(listOf(case), e, true)
        assertTrue(shown.sumOf { it.length } <= 400)
        assertFalse(shown.joinToString().contains("[LUMENA_"))
        assertTrue(RepairCasePolicy.advice(listOf(case.copy(scope = "foreign")), e, true).isEmpty())
    }
}
