package com.lumena.android.settings

import com.lumena.android.agent.core.AgentDecision
import com.lumena.android.agent.core.ConstitutionGeneStage
import com.lumena.android.agent.core.ConstitutionGenomePolicy
import com.lumena.android.agent.core.ConstitutionGenomeState
import com.lumena.android.agent.core.ConstitutionRuleStatus
import com.lumena.android.agent.core.EvidenceApplicationStatus
import com.lumena.android.agent.core.EvidenceAutomaticProjectBindingPolicy
import com.lumena.android.agent.core.EvidenceGraphReducer
import com.lumena.android.agent.core.EvidenceGraphState
import com.lumena.android.agent.core.EvidenceObservation
import com.lumena.android.agent.core.EvidenceProjectApplicationPolicy
import com.lumena.android.agent.core.EvidenceProjectOutcome
import com.lumena.android.agent.core.EvidenceRelation
import com.lumena.android.agent.core.EvidenceSourceKind
import com.lumena.android.agent.core.EvidenceVerificationState
import com.lumena.android.agent.core.ProjectContextResolver
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivingContextEndToEndTest {
    private val t0 = 1_800_000_000_000L

    @Test
    fun verifiedEvidenceProjectMutationTestsAndGenomeCalibrationCloseTheLoop() {
        val projectId = requireNotNull(
            ProjectContextResolver.resolve(
                text =
                    "Працюй у проєкті demo_project і використай pathlib Path.mkdir parents true.",
                previousProjectId = null,
                carryForward = false
            )
        )
        assertEquals("demo_project", projectId)

        val task1 = TaskState(
            id = "e2e-task-1",
            projectId = projectId,
            goal =
                "Use pathlib Path.mkdir parents true to create missing parent directories in demo_project",
            status = TaskStatus.WAITING_MODEL
        )

        var graph = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            EvidenceObservation(
                claimKey = "pathlib-mkdir-parents",
                statement =
                    "Path.mkdir with parents true creates missing parent directories.",
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = "https://docs.python.org/3/library/pathlib.html",
                sourceKind = EvidenceSourceKind.WEB_PAGE,
                retrievalMethod = "web.read",
                evidenceId = "web-read-pathlib",
                observedAt = t0,
                projectId = projectId,
                projectRelevance = 0.95
            )
        ).state

        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            graph.claims.single().verificationState
        )

        val firstPath =
            "demo_project/evidence_step8_a.py"
        val firstCall = AgentDecision.ToolCall(
            tool = "file.write",
            args = mapOf(
                "path" to firstPath,
                "content" to
                    "from pathlib import Path\n\ndef ensure_directory(path):\n    Path(path).mkdir(parents=True, exist_ok=True)\n"
            )
        )
        val validation = ToolRegistry.validate(firstCall)
        assertTrue(validation.allowed)
        assertTrue(validation.requiresConfirmation)

        val firstWrite = ToolRequest(
            tool = "file.write",
            args = firstCall.args
        )
        val firstWriteResult = ToolResult(
            ok = true,
            tool = "file.write"
        )

        val auto1 =
            EvidenceAutomaticProjectBindingPolicy
                .bindForSuccessfulMutation(
                    state = graph,
                    projectId = projectId,
                    taskGoal = task1.goal,
                    request = firstWrite,
                    result = firstWriteResult,
                    now = t0 + 1
                )

        assertEquals(1, auto1.bindingIds.size)
        assertEquals(
            EvidenceApplicationStatus.PENDING,
            auto1.state.applications.single().status
        )

        val applied1 =
            EvidenceProjectApplicationPolicy.observeToolResult(
                state = auto1.state,
                bindingId = auto1.bindingIds.single(),
                taskProjectId = projectId,
                request = firstWrite,
                result = firstWriteResult,
                evidenceId = "artifact-task-1",
                now = t0 + 2
            )
        assertTrue(applied1.accepted)
        assertEquals(
            EvidenceApplicationStatus.APPLIED,
            applied1.state.applications.single().status
        )

        val tests1 = ToolRequest(
            tool = "python.tests",
            args = mapOf("cwd" to projectId)
        )
        val verified1 =
            EvidenceProjectApplicationPolicy.observeToolResult(
                state = applied1.state,
                bindingId = auto1.bindingIds.single(),
                taskProjectId = projectId,
                request = tests1,
                result = ToolResult(
                    ok = true,
                    tool = "python.tests"
                ),
                evidenceId = "pytest-task-1",
                now = t0 + 3
            )
        assertTrue(verified1.accepted)

        val firstBinding =
            verified1.state.applications.single {
                it.id == auto1.bindingIds.single()
            }
        assertEquals(
            EvidenceApplicationStatus.VERIFIED,
            firstBinding.status
        )
        assertEquals(
            EvidenceProjectOutcome.VERIFIED_BY_TEST,
            verified1.state.claims.single().outcome
        )

        val firstGene = requireNotNull(
            ConstitutionContributionPolicy
                .verifiedProjectApplicationRule(
                    task = task1,
                    binding = firstBinding,
                    contributorModelId = "fixture-model"
                )
        )
        var genome =
            ConstitutionGenomePolicy
                .contributeVerifiedAdvisory(
                    state = ConstitutionGenomeState(),
                    proposal = firstGene
                )

        var rule = genome.rules.single()
        var calibration =
            ConstitutionGenomePolicy.calibration(rule)
        assertEquals(
            ConstitutionRuleStatus.CANDIDATE,
            rule.status
        )
        assertEquals(
            ConstitutionGeneStage.SHADOW,
            calibration.stage
        )
        assertEquals(50, calibration.activationProgressPercent)
        assertFalse(
            ConstitutionGenomeRuntime
                .promptLines(genome, task1, limit = 16)
                .any {
                    it.contains(
                        "After applying a project mutation"
                    )
                }
        )

        val task2 = task1.copy(
            id = "e2e-task-2",
            goal =
                "Use pathlib Path.mkdir parents true for another verified implementation in demo_project"
        )
        val secondPath =
            "demo_project/evidence_step8_b.py"
        val secondWrite = ToolRequest(
            tool = "file.write",
            args = mapOf(
                "path" to secondPath,
                "content" to
                    "from pathlib import Path\n\ndef ensure_parent(path):\n    Path(path).mkdir(parents=True, exist_ok=True)\n"
            )
        )
        val secondWriteResult = ToolResult(
            ok = true,
            tool = "file.write"
        )

        val auto2 =
            EvidenceAutomaticProjectBindingPolicy
                .bindForSuccessfulMutation(
                    state = verified1.state,
                    projectId = projectId,
                    taskGoal = task2.goal,
                    request = secondWrite,
                    result = secondWriteResult,
                    now = t0 + 4
                )
        assertEquals(1, auto2.bindingIds.size)

        val applied2 =
            EvidenceProjectApplicationPolicy.observeToolResult(
                state = auto2.state,
                bindingId = auto2.bindingIds.single(),
                taskProjectId = projectId,
                request = secondWrite,
                result = secondWriteResult,
                evidenceId = "artifact-task-2",
                now = t0 + 5
            )
        assertTrue(applied2.accepted)

        val verified2 =
            EvidenceProjectApplicationPolicy.observeToolResult(
                state = applied2.state,
                bindingId = auto2.bindingIds.single(),
                taskProjectId = projectId,
                request = ToolRequest(
                    tool = "python.tests",
                    args = mapOf("cwd" to projectId)
                ),
                result = ToolResult(
                    ok = true,
                    tool = "python.tests"
                ),
                evidenceId = "pytest-task-2",
                now = t0 + 6
            )
        assertTrue(verified2.accepted)

        graph = verified2.state
        val secondBinding =
            graph.applications.single {
                it.id == auto2.bindingIds.single()
            }
        assertEquals(
            EvidenceApplicationStatus.VERIFIED,
            secondBinding.status
        )

        val secondGene = requireNotNull(
            ConstitutionContributionPolicy
                .verifiedProjectApplicationRule(
                    task = task2,
                    binding = secondBinding,
                    contributorModelId = "fixture-model"
                )
        )
        genome =
            ConstitutionGenomePolicy
                .contributeVerifiedAdvisory(
                    state = genome,
                    proposal = secondGene
                )

        rule = genome.rules.single()
        calibration =
            ConstitutionGenomePolicy.calibration(rule)

        assertEquals(
            ConstitutionRuleStatus.LEARNED,
            rule.status
        )
        assertEquals(
            ConstitutionGeneStage.ACTIVE,
            calibration.stage
        )
        assertEquals(2, calibration.distinctContextCount)
        assertEquals(2, calibration.pairedProjectContextCount)
        assertEquals(100, calibration.activationProgressPercent)
        assertTrue(calibration.activeEligible)
        assertTrue(
            ConstitutionGenomeRuntime
                .promptLines(genome, task2, limit = 16)
                .any {
                    it.contains(
                        "After applying a project mutation"
                    )
                }
        )
    }
}
