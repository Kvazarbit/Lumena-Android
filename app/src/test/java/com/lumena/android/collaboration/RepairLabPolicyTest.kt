package com.lumena.android.collaboration

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class RepairLabPolicyTest {
    private val case = RepairCase("c", "scope", "check", "signature", "fail", "pass", "x - 1 → x + 1", 2, listOf("model"), 1)
    private val cfg = RepairLabConfig("experiment", "scope", "p", "local", "a".repeat(64), "build", "phone", listOf(case))
    private fun episode(id: String = "first") = RepairEpisode(id, "scope", "task", "check", "signature", 0, "OPEN", "PENDING", "model")
    @Test fun retryContinuationAndColdDecodeNeverRedrawAssignment() {
        val first = RepairLabPolicy.assign(RepairLabState(cfg), episode())
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(RepairLabState::class.java)
        val cold = requireNotNull(adapter.fromJson(adapter.toJson(first)))
        val resumed = RepairLabPolicy.assign(cold, episode().copy(taskId = "continued", steps = 4))
        assertEquals("ARM_REDRAWN", first.rows.single().exposed, resumed.rows.single().exposed)
        assertEquals(listOf("c"), resumed.rows.single().cases)
        assertEquals("continued", resumed.rows.single().taskId)
        assertEquals(1, resumed.rows.size)
    }
    @Test fun assignmentApproximatesEqualArmsWithoutUsingOutcome() {
        val shown = (1..2000).count { RepairLabPolicy.arm("experiment", "failure-$it") }
        assertTrue("UNBALANCED_ASSIGNMENT: $shown", shown in 900..1100)
    }
    @Test fun foreignOrChangedVerificationAndInactiveExperimentsGetNoRow() {
        val state = RepairLabState(cfg)
        for (e in listOf(episode().copy(scope = "other"), episode().copy(check = "other"), episode().copy(signature = "other")))
            assertEquals(state, RepairLabPolicy.assign(state, e))
        val stopped = state.copy(config = cfg.copy(active = false))
        assertEquals(stopped, RepairLabPolicy.assign(stopped, episode()))
    }
    @Test fun holdoutOffersNoExamplesDespiteAnEligibleCase() {
        val id = (1..100).map { "failure-$it" }.first { !RepairLabPolicy.arm(cfg.id, it) }
        val e = episode(id)
        val row = RepairLabPolicy.assign(RepairLabState(cfg), e).rows.single()
        assertFalse(row.exposed)
        assertTrue(RepairCasePolicy.advice(cfg.library, e, row.exposed).isEmpty())
        assertEquals(listOf("c"), row.cases)
    }
    @Test fun unknownAbandonedAndChangedConditionsStayInDenominator() {
        val e = episode()
        val row = RepairLabPolicy.assign(RepairLabState(cfg), e).rows.single().copy(ended = true, rejectedDone = 2)
        val state = RepairLabState(cfg, listOf(row, row.copy(episodeId = "second", invalidConditions = true)))
        val solved = e.copy(status = "SOLVED", steps = 3)
        val report = RepairLabPolicy.report(state, RepairSnapshot(emptyList(), listOf(solved, solved.copy(id = "second"))))
        assertTrue(report, report.contains("SOLVED 1; UNRESOLVED 1"))
        assertTrue(report.contains("відхилених DONE 4"))
        assertTrue(report.contains("ЗАМАЛО ДАНИХ"))
    }
    @Test fun invalidConditionsCannotBeResurrectedByRetry() {
        val row = RepairLabPolicy.assign(RepairLabState(cfg), episode()).rows.single().copy(invalidConditions = true, ended = true)
        assertTrue(RepairLabPolicy.assign(RepairLabState(cfg, listOf(row)), episode()).rows.single().invalidConditions)
    }
    @Test fun capacityDoesNotSilentlyDropOldAssignments() {
        val state = RepairLabState(cfg, (1..256).map { RepairLabRow("old-$it", "task", false, listOf("c")) })
        assertThrows(IllegalArgumentException::class.java) { RepairLabPolicy.assign(state, episode()) }
        assertEquals(256, state.rows.size)
    }
    @Test fun repeatedProblemsAreNeverDeclaredProvenLearning() {
        val rows = (1..80).map { RepairLabRow("r-$it", "t", it <= 40, listOf("c"), ended = true) }
        val report = RepairLabPolicy.report(RepairLabState(cfg, rows), RepairSnapshot(emptyList(), emptyList()))
        assertTrue(report.contains("повтори схожих задач залежні"))
        assertTrue(report.contains("Автоматичної промоції правил немає"))
    }
    @Test fun randomizedExposureWithoutTextIsNotCountedAsReceived() {
        val row = RepairLabRow("e", "task", true, listOf(case.id))
        val prepared = RepairLabPolicy.request(RepairLabState(cfg, listOf(row)), "task", "owner goal and receipts only")
        assertEquals(1, prepared.rows.single().modelRequests)
        assertEquals(0, prepared.rows.single().requestsWithCases)
        val actual = RepairLabPolicy.request(prepared, "task", "CASE c historical DATA, no command/proof")
        assertEquals(1, actual.rows.single().requestsWithCases)
    }
    @Test fun caseTextLeakingIntoHoldoutInvalidatesObservationWithoutDroppingIt() {
        val row = RepairLabRow("e", "task", false, listOf(case.id))
        val leaked = RepairLabPolicy.request(RepairLabState(cfg, listOf(row)), "task", "CASE c historical DATA, no command/proof")
        assertEquals(1, leaked.rows.size)
        assertTrue(leaked.rows.single().invalidConditions)
    }
}
