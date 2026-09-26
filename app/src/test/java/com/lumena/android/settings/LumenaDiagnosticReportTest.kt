package com.lumena.android.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenaDiagnosticReportTest {
    private fun input(
        version: String = "0.12.5-e2e87p",
        geneStage: String = "SHADOW",
        activation: Int = 50
    ) = LumenaDiagnosticInput(
        generatedAtMs = 123L,
        packageName = "com.lumena.android.e2e87p",
        versionName = version,
        versionCode = 29L,
        androidRelease = "14",
        androidApi = 34,
        device = "fixture-device",
        backend = "ollama",
        selectedModel = "fixture-model",
        computeMode = "auto",
        bridgeUrl = "http://127.0.0.1:8765",
        bridgeTokenPresent = true,
        chatMessages = 4,
        historyMessages = 8,
        pendingApproval = false,
        researchThreadPresent = true,
        partialOutcomeCapsulePresent = true,
        protocolRepairCount = 2,
        toolResultCount = 5,
        protocolTurnCapSeen = true,
        pytestNoTestsSeen = true,
        ollamaGenerateCount = 1,
        task = DiagnosticTaskView(
            id = "task-a",
            projectId = "e2e_step87",
            goal = "verify project",
            status = "PARTIAL",
            step = 8,
            maxSteps = 11,
            lastTool = "python.tests",
            lastResult = "no tests ran",
            pendingVerification =
                listOf("e2e_step87/test_dir_a.py"),
            errors =
                listOf("Protocol turn cap reached"),
            kernelObserved = 8,
            worldRevision = 2,
            inFlight = "",
            evidence = listOf(
                "e1|ACT|file.write|ok=true"
            )
        ),
        bridgeProbe = DiagnosticProbe(
            name = "bridge",
            configured = true,
            ok = true,
            stdout =
                """{"version":"0.25"}"""
        ),
        ollamaProbe = DiagnosticProbe(
            name = "ollama",
            configured = true,
            ok = true,
            stdout = "fixture-model"
        ),
        evidence = DiagnosticEvidenceView(
            totalClaims = 1,
            totalSources = 1,
            retrieved = 1,
            totalApplications = 1,
            verifiedApplications = 1,
            verifiedForCurrentProject = 1,
            applications = listOf(
                "binding|VERIFIED|project=e2e_step87"
            )
        ),
        genome = DiagnosticGenomeView(
            hard = 6,
            learned =
                if (geneStage == "ACTIVE") 1 else 0,
            shadow =
                if (geneStage == "SHADOW") 1 else 0,
            rules = listOf(
                DiagnosticGeneRow(
                    id = "gene-1",
                    claimKey =
                        "verify-project-mutation-before-success",
                    status =
                        if (geneStage == "ACTIVE") {
                            "LEARNED"
                        } else {
                            "CANDIDATE"
                        },
                    authority = "ADVISORY",
                    geneStage = geneStage,
                    activationPercent = activation,
                    evidenceCount = 2,
                    contexts =
                        if (geneStage == "ACTIVE") 2 else 1,
                    pairedProjectContexts =
                        if (geneStage == "ACTIVE") 2 else 1,
                    scope = "PROJECT:e2e_step87"
                )
            )
        )
    )

    @Test
    fun reportCarriesExactBuildIdentityAndRealE2eFacts() {
        val report =
            LumenaDiagnosticFormatter.render(input())

        assertTrue(
            report.contains(
                "package=com.lumena.android.e2e87p"
            )
        )
        assertTrue(
            report.contains(
                "version_name=0.12.5-e2e87p"
            )
        )
        assertTrue(
            report.contains(
                "verified_project_applications=1"
            )
        )
        assertTrue(
            report.contains(
                "verification_gene_stage=SHADOW"
            )
        )
        assertTrue(
            report.contains(
                "verification_gene_activation=50%"
            )
        )
        assertTrue(
            report.contains(
                "protocol_turn_cap_seen=true"
            )
        )
        assertTrue(
            report.contains(
                "pytest_no_tests_seen=true"
            )
        )
    }

    @Test
    fun activeGeneIsReportedWithoutTreatingModelSelfCheckAsProof() {
        val report =
            LumenaDiagnosticFormatter.render(
                input(
                    version = "0.12.5-e2e87d",
                    geneStage = "ACTIVE",
                    activation = 100
                )
            )

        assertTrue(
            report.contains(
                "verification_gene_stage=ACTIVE"
            )
        )
        assertTrue(
            report.contains(
                "verification_gene_activation=100%"
            )
        )
        assertTrue(
            report.contains(
                "model_self_check_is_proof=false"
            )
        )
        assertTrue(
            report.contains(
                "tool_success_alone_is_goal_proof=false"
            )
        )
    }

    @Test
    fun reportIncludesTinyJevCalibrationTelemetry() {
        val report =
            LumenaDiagnosticFormatter.render(
                input().copy(
                    tinyJevCalibration =
                        DiagnosticTinyJevCalibrationView(
                            pending = 2,
                            resolved = 12,
                            accuracy = 0.8333,
                            brierScore = 0.0912,
                            ece = 0.0745,
                            selectiveThreshold = 0.75,
                            selectiveCoverage = 0.50,
                            selectiveAccuracy = 1.0,
                            latencyP50Ms = 3L,
                            latencyP95Ms = 7L
                        )
                )
            )

        assertTrue(
            report.contains(
                "[TINYJEV_CALIBRATION]"
            )
        )
        assertTrue(report.contains("pending=2"))
        assertTrue(report.contains("resolved=12"))
        assertTrue(report.contains("brier=0.0912"))
        assertTrue(report.contains("ece=0.0745"))
        assertTrue(
            report.contains(
                "selective_coverage=0.5"
            )
        )
        assertTrue(
            report.contains(
                "latency_p95_ms=7"
            )
        )
    }

    @Test
    fun formatterRedactsBearerSecrets() {
        val dirty = input().copy(
            bridgeProbe = DiagnosticProbe(
                name = "bridge",
                configured = true,
                ok = false,
                error =
                    "Authorization: Bearer super-secret-token"
            )
        )

        val report =
            LumenaDiagnosticFormatter.render(dirty)

        assertTrue(
            report.contains(
                "Bearer [REDACTED]"
            )
        )
        assertFalse(
            report.contains(
                "super-secret-token"
            )
        )
    }
}
