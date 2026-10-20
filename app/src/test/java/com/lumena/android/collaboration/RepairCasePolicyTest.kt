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
        val later = fail()
        assertEquals(later.intentId, RepairCasePolicy.derive(listOf(fail(), pass(), later)).episodes.single { it.status == "OPEN" }.id)
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
    @Test fun invalidIntervalCannotRedrawAnEpisodeUntilARealPass() {
        val first = fail()
        val incomplete = fail().let { it.copy(result = it.result!!.copy(verification = stamp.copy(complete = false))) }
        val unknown = patch().let { it.copy(result = it.result!!.copy(outcomeUnknown = true)) }
        for (broken in listOf(incomplete, unknown, event("python.run", true))) {
            val retry = fail()
            val history = listOf(first, broken, retry)
            val stopped = RepairCasePolicy.derive(history)
            assertEquals("INVALID_INTERVAL_REDRAWN", listOf(first.intentId), stopped.episodes.map { it.id })
            assertEquals("UNRESOLVED", stopped.episodes.single().status)
            val fresh = fail()
            val recovered = RepairCasePolicy.derive(history + pass() + fresh)
            assertEquals(fresh.intentId, recovered.episodes.single { it.status == "OPEN" }.id)
        }
    }
    @Test fun trackedTrialOutcomeSurvivesHundredsOfUnrelatedLaterEpisodes() {
        val first = fail()
        val history = mutableListOf(first, patch(), pass())
        repeat(300) { n ->
            listOf(fail(), patch(), pass()).forEach { e -> history += e.copy(scope = e.scope.copy(obligationId = "other-$n")) }
        }
        val retained = RepairCasePolicy.derive(history, setOf(first.intentId))
        assertEquals("SOLVED_OUTCOME_EVICTED", "SOLVED", retained.episodes.single { it.id == first.intentId }.status)
        assertEquals(RepairCasePolicy.MAX_CASES, retained.cases.size)
    }

    // 7.9 fix 1: retrieval is about the same problem in the same project; creation validity stays strict.
    private val failedId = "tests/test_calc.py::tests.test_calc::test_one"
    private val named = stamp.copy(failedTestId = failedId, failureSummary = "AssertionError|assert 2 == 3")
    private fun namedCase(): RepairCase =
        RepairCasePolicy.derive(listOf(event("python.tests", false, proof = named), patch(), pass())).cases.single()
    private fun openEpisode(proof: VerificationEvidence): RepairEpisode =
        RepairCasePolicy.derive(listOf(event("python.tests", false, 'e', proof))).episodes.single()

    @Test fun sameFailedTestStillFindsItsCaseAfterTheSuiteGrows() {
        val case = namedCase()
        assertEquals(failedId, case.testId)
        val grown = openEpisode(named.copy(boundaryHash = "9".repeat(64), selectedTestsHash = "8".repeat(64),
            failureSignature = "7".repeat(64)))
        assertEquals("CASE_LOST_WHEN_SUITE_GREW", listOf(case.id), RepairCasePolicy.eligible(listOf(case), grown).map { it.id })
        assertTrue(RepairCasePolicy.advice(listOf(case), grown, true).single().contains("changed since: tests"))
    }

    @Test fun aGenericMessageInAnotherTestFileNoLongerJoinsUnrelatedCases() {
        val case = namedCase()
        val other = openEpisode(named.copy(failedTestId = "other/test_io.py::other.test_io::test_read"))
        assertEquals(case.check, other.check)
        assertEquals(case.signature, other.signature)
        assertTrue("UNRELATED_TEST_JOINED", RepairCasePolicy.eligible(listOf(case), other).isEmpty())
    }

    @Test fun sameTestRanksAboveSameFileAndOtherProjectsNeverMatch() {
        val exact = namedCase()
        val sibling = exact.copy(id = "sibling", testId = "tests/test_calc.py::tests.test_calc::test_two", at = exact.at + 100)
        val episode = openEpisode(named.copy(boundaryHash = "9".repeat(64)))
        assertEquals(listOf(exact.id, "sibling"), RepairCasePolicy.eligible(listOf(exact, sibling), episode).map { it.id })
        val foreign = exact.copy(id = "foreign", project = RepairCasePolicy.project("elsewhere", "other"))
        assertTrue("FOREIGN_PROJECT_CASE", RepairCasePolicy.eligible(listOf(foreign), episode).isEmpty())
    }

    @Test fun changedGoalKeepsTheCaseWithALabel() {
        val case = namedCase()
        val episode = openEpisode(named).copy(goal = "0".repeat(64))
        assertEquals(1, RepairCasePolicy.eligible(listOf(case), episode).size)
        assertTrue(RepairCasePolicy.advice(listOf(case), episode, true).single().contains("changed since: goal"))
    }

    // 7.9 fix 2: reads required by read-before-write do not consume the effectful step budget.
    @Test fun readsBeforeAPatchDoNotConsumeEffectfulSteps() {
        val history = listOf(fail()) + (1..10).map { event("file.read", true) } + listOf(patch(), pass())
        val s = RepairCasePolicy.derive(history)
        assertEquals("CAREFUL_READING_PENALIZED", "SOLVED", s.episodes.single().status)
        assertEquals(2, s.episodes.single().steps)
        assertEquals(10, s.episodes.single().reads)
        assertEquals(1, s.cases.size)
    }

    @Test fun ninePatchesWithoutPassHitTheEffectfulStepLimit() {
        val s = RepairCasePolicy.derive(listOf(fail()) + (1..9).map { patch() })
        assertEquals("STEP_LIMIT", s.episodes.single().reason)
    }

    @Test fun endlessReadingIsUnresolvedWithItsOwnReason() {
        val s = RepairCasePolicy.derive(listOf(fail()) + (1..RepairCasePolicy.MAX_READS + 1).map { event("file.read", true) })
        assertEquals("UNRESOLVED", s.episodes.single().status)
        assertEquals("READ_LIMIT", s.episodes.single().reason)
    }

    @Test fun siblingFailedTestNeedsMeaningfulFailureOverlap() {
        val generic = namedCase()
        val unrelated = openEpisode(named.copy(
            failedTestId = "tests/test_calc.py::tests.test_calc::test_unrelated",
            failureSummary = "AssertionError|assert 9 == 12"
        ))
        assertTrue("SIBLING_DIAGNOSTIC_MISMATCH",
            RepairCasePolicy.eligible(listOf(generic), unrelated).isEmpty())

        val specific = named.copy(failureSummary = "ValueError|calculation limit exceeded")
        val case = RepairCasePolicy.derive(listOf(
            event("python.tests", false, proof = specific), patch(), pass()
        )).cases.single()
        val related = openEpisode(specific.copy(
            failedTestId = "tests/test_calc.py::tests.test_calc::test_other",
            failureSummary = "ValueError|calculation limit reached"
        ))
        assertEquals(listOf(case.id), RepairCasePolicy.eligible(listOf(case), related).map { it.id })
    }

}
