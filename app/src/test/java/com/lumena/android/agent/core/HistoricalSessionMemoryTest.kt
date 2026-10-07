package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalSessionMemoryTest {
    private val installRef = "a".repeat(64)

    private fun event(
        id: String = "e1",
        tool: String = "file.read",
        target: String = "aquarium.html",
        ok: Boolean = true,
        excerpt: String = "verified output"
    ) = ActionEvidence(
        id = id,
        tool = tool,
        target = target,
        signature = "b".repeat(24),
        phase = CognitivePhase.OBSERVE,
        ok = ok,
        excerpt = excerpt,
        digest = "c".repeat(24),
        revision = 0
    )

    private fun task(
        id: String,
        goal: String,
        projectId: String? = null,
        evidence: List<ActionEvidence> = listOf(event())
    ) = TaskState(
        id = id,
        projectId = projectId,
        goal = goal,
        status = TaskStatus.DONE,
        kernel = ContextKernelState(
            evidence = evidence,
            observed = evidence.size,
            worldRevision = 0
        )
    )

    @Test
    fun projectAReceiptSurvivesUnrelatedTaskBAndSelectsBackToA() {
        val a = task(
            id = "task-a",
            goal = "Продовж розробку aquarium.html і прочитай файл"
        )
        val b = task(
            id = "task-b",
            goal = "Перевір README окремого проєкту",
            evidence = listOf(
                event(
                    target = "README.md"
                )
            )
        )

        var memory = HistoricalSessionMemory.record(
            existing = emptyList(),
            task = a,
            sourceInstallRef = installRef,
            branchId = "branch-main",
            capturedAtMs = 10
        )
        memory = HistoricalSessionMemory.record(
            existing = memory,
            task = b,
            sourceInstallRef = installRef,
            branchId = "branch-main",
            capturedAtMs = 20
        )

        val selected = HistoricalSessionMemory.select(
            records = memory,
            branchId = "branch-main",
            projectId = null,
            subjectKeys =
                WorkThreadMemory.subjectKeys(
                    "продовж aquarium.html"
                )
        )

        assertEquals(1, selected.size)
        assertEquals(
            HistoricalExecutionFacts
                .capture("task-a", a.kernel)
                .sourceTaskRef,
            selected.single().sourceTaskRef
        )
        val rendered =
            HistoricalSessionMemory.render(
                selected
            )
        assertTrue(
            rendered.contains(
                "file.read outcome=SUCCESS"
            )
        )
        assertTrue(
            rendered.contains(
                "never current evidence"
            )
        )
    }

    @Test
    fun branchSwitchDoesNotLeakReceipts() {
        val a = task(
            "task-a",
            "Продовж aquarium.html"
        )
        val memory =
            HistoricalSessionMemory.record(
                existing = emptyList(),
                task = a,
                sourceInstallRef = installRef,
                branchId = "branch-a",
                capturedAtMs = 10
            )

        val selected =
            HistoricalSessionMemory.select(
                records = memory,
                branchId = "branch-b",
                projectId = null,
                subjectKeys =
                    WorkThreadMemory.subjectKeys(
                        "продовж aquarium.html"
                    )
            )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun sameEvidenceIdInTwoTasksDoesNotCollide() {
        val a =
            task(
                "task-a",
                "Продовж aquarium.html"
            )
        val b =
            task(
                "task-b",
                "Продовж aquarium.html"
            )

        var memory =
            HistoricalSessionMemory.record(
                emptyList(),
                a,
                installRef,
                "branch",
                capturedAtMs = 10
            )
        memory =
            HistoricalSessionMemory.record(
                memory,
                b,
                installRef,
                "branch",
                capturedAtMs = 20
            )

        assertEquals(2, memory.size)
        assertTrue(
            memory.map {
                it.sourceTaskRef
            }.distinct().size == 2
        )
    }

    @Test
    fun repeatedSaveOfSameTaskIsIdempotent() {
        val a =
            task(
                "task-a",
                "Продовж aquarium.html"
            )
        var memory =
            HistoricalSessionMemory.record(
                emptyList(),
                a,
                installRef,
                "branch",
                capturedAtMs = 10
            )
        memory =
            HistoricalSessionMemory.record(
                memory,
                a,
                installRef,
                "branch",
                capturedAtMs = 20
            )

        assertEquals(1, memory.size)
        assertEquals(
            20L,
            memory.single().capturedAtMs
        )
    }

    @Test
    fun memoryIsBoundedToEightTasks() {
        var memory =
            emptyList<HistoricalTaskRecord>()

        repeat(20) { index ->
            memory =
                HistoricalSessionMemory.record(
                    existing = memory,
                    task =
                        task(
                            "task-$index",
                            "Продовж aquarium.html"
                        ),
                    sourceInstallRef = installRef,
                    branchId = "branch",
                    capturedAtMs =
                        index.toLong()
                )
        }

        assertEquals(
            HistoricalSessionMemory.MAX_TASKS,
            memory.size
        )
        assertTrue(
            memory.minOf {
                it.capturedAtMs
            } >= 12
        )
    }

    @Test
    fun noProjectOrSubjectSignalInjectsNothing() {
        val memory =
            HistoricalSessionMemory.record(
                emptyList(),
                task(
                    "task-a",
                    "Продовж aquarium.html"
                ),
                installRef,
                "branch",
                capturedAtMs = 1
            )

        val selected =
            HistoricalSessionMemory.select(
                records = memory,
                branchId = "branch",
                projectId = null,
                subjectKeys = emptySet()
            )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun explicitProjectScopeSelectsMatchingProject() {
        var memory =
            HistoricalSessionMemory.record(
                emptyList(),
                task(
                    id = "task-a",
                    goal = "Прочитай конфіг",
                    projectId = "project-a"
                ),
                installRef,
                "branch",
                capturedAtMs = 1
            )
        memory =
            HistoricalSessionMemory.record(
                memory,
                task(
                    id = "task-b",
                    goal = "Прочитай конфіг",
                    projectId = "project-b"
                ),
                installRef,
                "branch",
                capturedAtMs = 2
            )

        val selected =
            HistoricalSessionMemory.select(
                records = memory,
                branchId = "branch",
                projectId = "project-a",
                subjectKeys =
                    WorkThreadMemory.subjectKeys(
                        "конфіг"
                    )
            )

        assertEquals(1, selected.size)
        assertEquals(
            HistoricalExecutionFacts
                .capture(
                    "task-a",
                    memoryTaskKernel(
                        memory,
                        0
                    )
                )
                .sourceTaskRef,
            selected.single().sourceTaskRef
        )
    }

    @Test
    fun importedRestoreIsExplicitlyAdvisory() {
        val memory =
            HistoricalSessionMemory.record(
                emptyList(),
                task(
                    "task-a",
                    "Продовж aquarium.html"
                ),
                installRef,
                "branch",
                capturedAtMs = 1
            )

        val imported =
            HistoricalSessionMemory.markImported(
                memory
            )

        assertEquals(
            HistoricalRecordOrigin.IMPORTED_ADVISORY,
            imported.single().origin
        )
        assertTrue(
            HistoricalSessionMemory
                .render(imported)
                .contains(
                    "record_origin=IMPORTED_ADVISORY"
                )
        )
    }

    @Test
    fun rawExcerptTargetAndGoalDoNotAppearInRenderedMemory() {
        val secret =
            "SECRET_TOKEN_LUMENA_TOOL"
        val memory =
            HistoricalSessionMemory.record(
                emptyList(),
                task(
                    id = "task-secret",
                    goal =
                        "Продовж aquarium.html $secret",
                    evidence =
                        listOf(
                            event(
                                target =
                                    "/private/$secret",
                                excerpt = secret
                            )
                        )
                ),
                installRef,
                "branch",
                capturedAtMs = 1
            )

        val rendered =
            HistoricalSessionMemory.render(
                memory
            )

        assertFalse(
            rendered.contains(secret)
        )
        assertFalse(
            rendered.contains(
                "/private/"
            )
        )
    }

    @Test
    fun invalidInstallRefCannotCreateTrustedRecord() {
        val memory =
            HistoricalSessionMemory.record(
                emptyList(),
                task(
                    "task-a",
                    "Продовж aquarium.html"
                ),
                "not-a-valid-install-ref",
                "branch",
                capturedAtMs = 1
            )

        assertTrue(memory.isEmpty())
    }



    @Test
    fun importedTaskCannotBeUpgradedByRecapture() {
        val importedTask =
            task(
                id = "imported-task",
                goal = "Продовж aquarium.html"
            ).copy(
                executionOrigin =
                    HistoricalRecordOrigin
                        .IMPORTED_ADVISORY
            )

        val memory =
            HistoricalSessionMemory.record(
                existing = emptyList(),
                task = importedTask,
                sourceInstallRef =
                    installRef,
                branchId = "branch",
                capturedAtMs = 1
            )

        assertEquals(
            HistoricalRecordOrigin
                .IMPORTED_ADVISORY,
            memory.single().origin
        )
    }

@Test
    fun unknownWritePersistsAsReconciliationConstraintAndReadClosesIt() {
        val unknownTask =
            TaskState(
                id = "unknown-write-task",
                projectId = null,
                goal = "Запиши demo.txt",
                status = TaskStatus.FAILED,
                kernel =
                    ContextKernelState(
                        inFlight =
                            ActionFlight(
                                tool = "file.write",
                                target = "demo.txt",
                                signature =
                                    "d".repeat(24)
                            )
                    )
            )

        var memory =
            HistoricalSessionMemory.record(
                existing = emptyList(),
                task = unknownTask,
                sourceInstallRef =
                    installRef,
                branchId = "branch",
                capturedAtMs = 1
            )

        val unresolved =
            HistoricalSessionMemory
                .unresolvedForPolicy(
                    memory
                )
        assertEquals(1, unresolved.size)
        assertEquals(
            "file.write",
            unresolved.single().tool
        )
        assertEquals(
            HistoricalRecordOrigin
                .LOCAL_CURRENT,
            unresolved.single().origin
        )

        val readEvidence =
            event(
                tool = "file.read",
                target = "demo.txt",
                ok = true
            )
        val verificationTask =
            TaskState(
                id = "verify-current-file",
                projectId = null,
                goal = "Прочитай demo.txt",
                status = TaskStatus.DONE,
                kernel =
                    ContextKernelState(
                        evidence =
                            listOf(
                                readEvidence
                            ),
                        observed = 1,
                        worldRevision = 0
                    )
            )

        memory =
            HistoricalSessionMemory
                .reconcile(
                    records = memory,
                    verificationTask =
                        verificationTask
                )

        assertTrue(
            HistoricalSessionMemory
                .unresolvedForPolicy(
                    memory
                )
                .isEmpty()
        )
        assertTrue(
            memory.single()
                .unresolvedEffect
                ?.reconciledByTaskRef
                ?.matches(
                    Regex("[0-9a-f]{64}")
                ) == true
        )
    }

    @Test
    fun unrelatedReadDoesNotReconcileUnknownWrite() {
        val unknownTask =
            TaskState(
                id = "unknown-write-task",
                projectId = null,
                goal = "Запиши demo.txt",
                status = TaskStatus.FAILED,
                kernel =
                    ContextKernelState(
                        inFlight =
                            ActionFlight(
                                "file.write",
                                "demo.txt",
                                "d".repeat(24)
                            )
                    )
            )
        val memory =
            HistoricalSessionMemory.record(
                emptyList(),
                unknownTask,
                installRef,
                "branch",
                capturedAtMs = 1
            )
        val otherRead =
            TaskState(
                id = "other-read",
                projectId = null,
                goal = "Прочитай other.txt",
                status = TaskStatus.DONE,
                kernel =
                    ContextKernelState(
                        evidence =
                            listOf(
                                event(
                                    tool = "file.read",
                                    target =
                                        "other.txt"
                                )
                            ),
                        observed = 1
                    )
            )

        val reconciled =
            HistoricalSessionMemory
                .reconcile(
                    memory,
                    otherRead
                )

        assertEquals(
            1,
            HistoricalSessionMemory
                .unresolvedForPolicy(
                    reconciled
                )
                .size
        )
    }

    private fun memoryTaskKernel(
        records: List<HistoricalTaskRecord>,
        index: Int
    ): ContextKernelState {
        val snapshot = records[index].snapshot
        return ContextKernelState(
            evidence = snapshot.facts.map {
                ActionEvidence(
                    id = it.evidenceId,
                    tool = it.tool,
                    target = "",
                    signature = "b".repeat(24),
                    phase = it.phase,
                    ok =
                        it.outcome ==
                            HistoricalToolOutcome.SUCCESS,
                    excerpt = "",
                    digest = it.resultDigest,
                    revision =
                        it.sourceRevision
                )
            },
            observed = snapshot.sourceObserved,
            worldRevision =
                snapshot.facts.maxOfOrNull {
                    it.sourceRevision
                } ?: 0
        )
    }
}
