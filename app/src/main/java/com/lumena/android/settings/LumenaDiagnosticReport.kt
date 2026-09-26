package com.lumena.android.settings

import android.content.Context
import android.os.Build
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolRequest

data class DiagnosticProbe(
    val name: String,
    val configured: Boolean,
    val ok: Boolean,
    val stdout: String = "",
    val error: String = "",
    val errorCode: String = "",
    val failureClass: String = ""
)

data class DiagnosticTaskView(
    val id: String,
    val projectId: String,
    val goal: String,
    val status: String,
    val step: Int,
    val maxSteps: Int,
    val lastTool: String,
    val lastResult: String,
    val pendingVerification: List<String>,
    val errors: List<String>,
    val kernelObserved: Int,
    val worldRevision: Int,
    val inFlight: String,
    val evidence: List<String>
)

data class DiagnosticEvidenceView(
    val error: String = "",
    val totalClaims: Int = 0,
    val totalSources: Int = 0,
    val discovered: Int = 0,
    val retrieved: Int = 0,
    val corroborated: Int = 0,
    val contested: Int = 0,
    val totalApplications: Int = 0,
    val pendingApplications: Int = 0,
    val appliedApplications: Int = 0,
    val verifiedApplications: Int = 0,
    val rejectedApplications: Int = 0,
    val verifiedForCurrentProject: Int = 0,
    val applications: List<String> = emptyList(),
    val claims: List<String> = emptyList()
)

data class DiagnosticGeneRow(
    val id: String,
    val claimKey: String,
    val status: String,
    val authority: String,
    val geneStage: String,
    val activationPercent: Int,
    val evidenceCount: Int,
    val contexts: Int,
    val pairedProjectContexts: Int,
    val scope: String
)

data class DiagnosticGenomeView(
    val error: String = "",
    val hard: Int = 0,
    val learned: Int = 0,
    val shadow: Int = 0,
    val userConstraints: Int = 0,
    val contested: Int = 0,
    val rules: List<DiagnosticGeneRow> = emptyList()
)

data class DiagnosticTinyJevCalibrationView(
    val error: String = "",
    val pending: Int = 0,
    val resolved: Int = 0,
    val accuracy: Double = 0.0,
    val brierScore: Double = 0.0,
    val ece: Double = 0.0,
    val selectiveThreshold: Double = 0.75,
    val selectiveCoverage: Double = 0.0,
    val selectiveAccuracy: Double? = null,
    val latencyP50Ms: Long? = null,
    val latencyP95Ms: Long? = null
)

data class LumenaDiagnosticInput(
    val generatedAtMs: Long,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val androidApi: Int,
    val device: String,
    val backend: String,
    val selectedModel: String,
    val computeMode: String,
    val bridgeUrl: String,
    val bridgeTokenPresent: Boolean,
    val chatMessages: Int,
    val historyMessages: Int,
    val pendingApproval: Boolean,
    val researchThreadPresent: Boolean,
    val partialOutcomeCapsulePresent: Boolean,
    val protocolRepairCount: Int,
    val toolResultCount: Int,
    val protocolTurnCapSeen: Boolean,
    val pytestNoTestsSeen: Boolean,
    val ollamaGenerateCount: Int,
    val task: DiagnosticTaskView?,
    val bridgeProbe: DiagnosticProbe,
    val ollamaProbe: DiagnosticProbe,
    val evidence: DiagnosticEvidenceView,
    val genome: DiagnosticGenomeView,
    val tinyJevCalibration: DiagnosticTinyJevCalibrationView =
        DiagnosticTinyJevCalibrationView()
)

object LumenaDiagnosticFormatter {
    fun render(input: LumenaDiagnosticInput): String {
        val verificationGene = input.genome.rules
            .firstOrNull {
                it.claimKey ==
                    "verify-project-mutation-before-success"
            }

        return buildString {
            appendLine("LUMENA_DIAGNOSTIC_V1")
            appendLine("generated_at_ms=${input.generatedAtMs}")
            appendLine()

            appendLine("[APP]")
            appendLine("package=${clean(input.packageName, 220)}")
            appendLine("version_name=${clean(input.versionName, 220)}")
            appendLine("version_code=${input.versionCode}")
            appendLine("android=${clean(input.androidRelease, 80)}")
            appendLine("api=${input.androidApi}")
            appendLine("device=${clean(input.device, 220)}")
            appendLine()

            appendLine("[RUNTIME]")
            appendLine("backend=${clean(input.backend, 80)}")
            appendLine("model=${clean(input.selectedModel, 220)}")
            appendLine("compute_mode=${clean(input.computeMode, 80)}")
            appendLine("bridge_url=${clean(input.bridgeUrl, 300)}")
            appendLine("bridge_token_present=${input.bridgeTokenPresent}")
            appendProbe(input.bridgeProbe)
            appendProbe(input.ollamaProbe)
            appendLine()

            appendLine("[SESSION]")
            appendLine("chat_messages=${input.chatMessages}")
            appendLine("history_messages=${input.historyMessages}")
            appendLine("pending_approval=${input.pendingApproval}")
            appendLine("research_thread_present=${input.researchThreadPresent}")
            appendLine(
                "partial_outcome_capsule_present=" +
                    input.partialOutcomeCapsulePresent
            )
            appendLine("protocol_repair_count=${input.protocolRepairCount}")
            appendLine("tool_result_count=${input.toolResultCount}")
            appendLine("protocol_turn_cap_seen=${input.protocolTurnCapSeen}")
            appendLine("pytest_no_tests_seen=${input.pytestNoTestsSeen}")
            appendLine("ollama_generate_count=${input.ollamaGenerateCount}")
            appendLine()

            appendLine("[TASK]")
            val task = input.task
            if (task == null) {
                appendLine("present=false")
            } else {
                appendLine("present=true")
                appendLine("id=${clean(task.id, 220)}")
                appendLine("project_id=${clean(task.projectId, 220)}")
                appendLine("status=${clean(task.status, 80)}")
                appendLine("step=${task.step}/${task.maxSteps}")
                appendLine("goal=${clean(task.goal, 1200)}")
                appendLine("last_tool=${clean(task.lastTool, 160)}")
                appendLine("last_result=${clean(task.lastResult, 1600)}")
                appendLine(
                    "pending_verification=" +
                        task.pendingVerification
                            .joinToString(",") { clean(it, 300) }
                )
                appendLine("kernel_observed=${task.kernelObserved}")
                appendLine("world_revision=${task.worldRevision}")
                appendLine("in_flight=${clean(task.inFlight, 300)}")
                if (task.errors.isNotEmpty()) {
                    appendLine("errors:")
                    task.errors.takeLast(6).forEach {
                        appendLine("- " + clean(it, 900))
                    }
                }
                if (task.evidence.isNotEmpty()) {
                    appendLine("kernel_evidence:")
                    task.evidence.takeLast(12).forEach {
                        appendLine("- " + clean(it, 900))
                    }
                }
            }
            appendLine()

            appendLine("[EVIDENCE_GRAPH]")
            if (input.evidence.error.isNotBlank()) {
                appendLine(
                    "error=" +
                        clean(input.evidence.error, 1200)
                )
            } else {
                appendLine("claims=${input.evidence.totalClaims}")
                appendLine("sources=${input.evidence.totalSources}")
                appendLine("discovered=${input.evidence.discovered}")
                appendLine("retrieved=${input.evidence.retrieved}")
                appendLine("corroborated=${input.evidence.corroborated}")
                appendLine("contested=${input.evidence.contested}")
                appendLine(
                    "applications_total=" +
                        input.evidence.totalApplications
                )
                appendLine(
                    "applications_pending=" +
                        input.evidence.pendingApplications
                )
                appendLine(
                    "applications_applied=" +
                        input.evidence.appliedApplications
                )
                appendLine(
                    "applications_verified=" +
                        input.evidence.verifiedApplications
                )
                appendLine(
                    "applications_rejected=" +
                        input.evidence.rejectedApplications
                )
                appendLine(
                    "verified_for_current_project=" +
                        input.evidence.verifiedForCurrentProject
                )
                if (input.evidence.applications.isNotEmpty()) {
                    appendLine("applications:")
                    input.evidence.applications.take(12).forEach {
                        appendLine("- " + clean(it, 1000))
                    }
                }
                if (input.evidence.claims.isNotEmpty()) {
                    appendLine("claims_top:")
                    input.evidence.claims.take(10).forEach {
                        appendLine("- " + clean(it, 1000))
                    }
                }
            }
            appendLine()

            appendLine("[CONSTITUTION_GENOME]")
            if (input.genome.error.isNotBlank()) {
                appendLine(
                    "error=" +
                        clean(input.genome.error, 1200)
                )
            } else {
                appendLine("hard=${input.genome.hard}")
                appendLine("learned_active=${input.genome.learned}")
                appendLine("shadow_candidates=${input.genome.shadow}")
                appendLine("user_constraints=${input.genome.userConstraints}")
                appendLine("contested=${input.genome.contested}")
                if (input.genome.rules.isNotEmpty()) {
                    appendLine("learned_and_shadow_rules:")
                    input.genome.rules.take(16).forEach { rule ->
                        appendLine(
                            "- id=${clean(rule.id, 180)}" +
                                " claim=${clean(rule.claimKey, 220)}" +
                                " status=${clean(rule.status, 80)}" +
                                " authority=${clean(rule.authority, 80)}" +
                                " stage=${clean(rule.geneStage, 80)}" +
                                " activation=${rule.activationPercent}%" +
                                " evidence=${rule.evidenceCount}" +
                                " contexts=${rule.contexts}" +
                                " paired=${rule.pairedProjectContexts}" +
                                " scope=${clean(rule.scope, 220)}"
                        )
                    }
                }
            }
            appendLine()

            appendLine("[TINYJEV_CALIBRATION]")
            val calibration = input.tinyJevCalibration
            if (calibration.error.isNotBlank()) {
                appendLine(
                    "error=" +
                        clean(calibration.error, 1200)
                )
            } else {
                appendLine("pending=${calibration.pending}")
                appendLine("resolved=${calibration.resolved}")
                appendLine(
                    "accuracy=" +
                        metric(calibration.accuracy)
                )
                appendLine(
                    "brier=" +
                        metric(calibration.brierScore)
                )
                appendLine(
                    "ece=" +
                        metric(calibration.ece)
                )
                appendLine(
                    "selective_threshold=" +
                        metric(calibration.selectiveThreshold)
                )
                appendLine(
                    "selective_coverage=" +
                        metric(calibration.selectiveCoverage)
                )
                appendLine(
                    "selective_accuracy=" +
                        (
                            calibration.selectiveAccuracy
                                ?.let(::metric)
                                ?: "NA"
                            )
                )
                appendLine(
                    "latency_p50_ms=" +
                        (
                            calibration.latencyP50Ms
                                ?.toString()
                                ?: "NA"
                            )
                )
                appendLine(
                    "latency_p95_ms=" +
                        (
                            calibration.latencyP95Ms
                                ?.toString()
                                ?: "NA"
                            )
                )
            }
            appendLine()

            appendLine("[E2E_FACTS]")
            appendLine(
                "verified_project_applications=" +
                    input.evidence.verifiedForCurrentProject
            )
            appendLine(
                "verification_gene_present=" +
                    (verificationGene != null)
            )
            appendLine(
                "verification_gene_stage=" +
                    (verificationGene?.geneStage ?: "NONE")
            )
            appendLine(
                "verification_gene_activation=" +
                    (verificationGene?.activationPercent ?: 0) +
                    "%"
            )
            appendLine(
                "model_self_check_is_proof=false"
            )
            appendLine(
                "tool_success_alone_is_goal_proof=false"
            )
            appendLine()

            appendLine("END_LUMENA_DIAGNOSTIC_V1")
        }.take(28_000)
    }

    private fun StringBuilder.appendProbe(
        probe: DiagnosticProbe
    ) {
        appendLine("${probe.name}_configured=${probe.configured}")
        appendLine("${probe.name}_ok=${probe.ok}")
        if (probe.errorCode.isNotBlank()) {
            appendLine(
                "${probe.name}_error_code=" +
                    clean(probe.errorCode, 180)
            )
        }
        if (probe.failureClass.isNotBlank()) {
            appendLine(
                "${probe.name}_failure_class=" +
                    clean(probe.failureClass, 180)
            )
        }
        if (probe.error.isNotBlank()) {
            appendLine(
                "${probe.name}_error=" +
                    clean(probe.error, 900)
            )
        }
        if (probe.stdout.isNotBlank()) {
            appendLine(
                "${probe.name}_stdout=" +
                    clean(probe.stdout, 1600)
            )
        }
    }

    private fun metric(
        value: Double
    ): String {
        val bounded = value.coerceIn(0.0, 1.0)
        val rounded =
            kotlin.math.round(bounded * 10_000.0) /
                10_000.0
        return rounded.toString()
    }

    internal fun clean(
        value: String,
        maxChars: Int
    ): String =
        value
            .replace(
                Regex("(?i)Bearer\\s+[^\\s,;]+"),
                "Bearer [REDACTED]"
            )
            .replace(
                Regex("[\\r\\n\\t]+"),
                " "
            )
            .replace(
                Regex("\\s{2,}"),
                " "
            )
            .trim()
            .take(maxChars)
}

object LumenaDiagnosticReport {
    suspend fun build(
        context: Context
    ): String {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val session = LocalSessionStore.load(app)
        val settings = LumenaPreferences.load(app)
        val packageInfo =
            app.packageManager.getPackageInfo(
                app.packageName,
                0
            )
        val task = session.task

        val taskView = task?.let(::taskView)
        val historyText =
            session.history.joinToString("\n") {
                it.content
            }

        val bridgeProbe = probe(
            context = app,
            name = "bridge",
            tool = "health"
        )
        val ollamaProbe = probe(
            context = app,
            name = "ollama",
            tool = "ollama.status"
        )

        val evidence = evidenceView(
            context = app,
            task = task,
            now = now
        )
        val genome = genomeView(app)
        val tinyJevCalibration =
            tinyJevCalibrationView(app)

        val input = LumenaDiagnosticInput(
            generatedAtMs = now,
            packageName = app.packageName,
            versionName =
                packageInfo.versionName.orEmpty(),
            versionCode =
                packageInfo.longVersionCode,
            androidRelease =
                Build.VERSION.RELEASE.orEmpty(),
            androidApi = Build.VERSION.SDK_INT,
            device =
                listOf(
                    Build.MANUFACTURER,
                    Build.MODEL
                )
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
            backend = settings.inferenceBackend,
            selectedModel =
                settings.selectedModel.ifBlank {
                    "(none)"
                },
            computeMode = settings.computeMode,
            bridgeUrl = settings.bridgeUrl,
            bridgeTokenPresent =
                settings.bridgeToken.isNotBlank(),
            chatMessages = session.chat.size,
            historyMessages = session.history.size,
            pendingApproval = session.pending != null,
            researchThreadPresent =
                session.researchThread != null,
            partialOutcomeCapsulePresent =
                session.history.any {
                    it.content.contains(
                        "PREVIOUS_TASK_OUTCOME"
                    ) &&
                        it.content.contains(
                            "status=PARTIAL"
                        )
                },
            protocolRepairCount =
                session.history.count {
                    it.content.contains(
                        "PROTOCOL_REPAIR_MODE"
                    )
                },
            toolResultCount =
                session.history.count {
                    it.content
                        .trimStart()
                        .startsWith(
                            "TOOL_RESULT for "
                        )
                },
            protocolTurnCapSeen =
                (
                    task?.errors.orEmpty()
                        .joinToString("\n") +
                        "\n" +
                        historyText
                    )
                    .contains(
                        "protocol turn cap",
                        ignoreCase = true
                    ),
            pytestNoTestsSeen =
                (
                    task?.lastResult.orEmpty() +
                        "\n" +
                        task?.errors.orEmpty()
                            .joinToString("\n") +
                        "\n" +
                        historyText
                    )
                    .contains(
                        "no tests ran",
                        ignoreCase = true
                    ),
            ollamaGenerateCount =
                task?.kernel?.evidence
                    ?.count {
                        it.tool ==
                            "ollama.generate"
                    }
                    ?: 0,
            task = taskView,
            bridgeProbe = bridgeProbe,
            ollamaProbe = ollamaProbe,
            evidence = evidence,
            genome = genome,
            tinyJevCalibration =
                tinyJevCalibration
        )

        return LumenaDiagnosticFormatter.render(
            input
        )
    }

    private fun tinyJevCalibrationView(
        context: Context
    ): DiagnosticTinyJevCalibrationView =
        runCatching {
            val state =
                TinyJevCalibrationStore.load(context)
            val metrics =
                TinyJevCalibrationPolicy.metrics(state)
            DiagnosticTinyJevCalibrationView(
                pending = state.pending.size,
                resolved = metrics.samples,
                accuracy = metrics.accuracy,
                brierScore = metrics.brierScore,
                ece = metrics.ece,
                selectiveThreshold =
                    metrics.selectiveThreshold,
                selectiveCoverage =
                    metrics.selectiveCoverage,
                selectiveAccuracy =
                    metrics.selectiveAccuracy,
                latencyP50Ms =
                    metrics.latencyP50Ms,
                latencyP95Ms =
                    metrics.latencyP95Ms
            )
        }.getOrElse { failure ->
            DiagnosticTinyJevCalibrationView(
                error =
                    failure.message
                        ?: failure::class.simpleName
                        ?: "calibration unavailable"
            )
        }

    private suspend fun probe(
        context: Context,
        name: String,
        tool: String
    ): DiagnosticProbe {
        val settings =
            LumenaPreferences.load(context)
        if (settings.bridgeToken.isBlank()) {
            return DiagnosticProbe(
                name = name,
                configured = false,
                ok = false,
                error = "bridge token is not configured"
            )
        }

        return runCatching {
            TermuxBridgeClient(
                settings.bridgeUrl,
                settings.bridgeToken,
                context
            ).execute(
                ToolRequest(tool = tool)
            )
        }.fold(
            onSuccess = { result ->
                DiagnosticProbe(
                    name = name,
                    configured = true,
                    ok = result.ok,
                    stdout = result.stdout,
                    error =
                        result.error.orEmpty(),
                    errorCode =
                        result.errorCode.orEmpty(),
                    failureClass =
                        result.failureClass.orEmpty()
                )
            },
            onFailure = { failure ->
                DiagnosticProbe(
                    name = name,
                    configured = true,
                    ok = false,
                    error =
                        failure.message
                            ?: failure::class.simpleName
                            ?: "probe failed"
                )
            }
        )
    }

    private fun taskView(
        task: TaskState
    ): DiagnosticTaskView =
        DiagnosticTaskView(
            id = task.id,
            projectId =
                task.projectId.orEmpty(),
            goal = task.goal,
            status = task.status.name,
            step = task.step,
            maxSteps = task.maxSteps,
            lastTool =
                task.lastTool.orEmpty(),
            lastResult =
                task.lastResult.orEmpty(),
            pendingVerification =
                task.kernel.pendingVerification
                    .sorted(),
            errors =
                task.errors.takeLast(6),
            kernelObserved =
                task.kernel.observed,
            worldRevision =
                task.kernel.worldRevision,
            inFlight =
                task.kernel.inFlight
                    ?.let {
                        "${it.tool}:${it.target}"
                    }
                    .orEmpty(),
            evidence =
                task.kernel.evidence
                    .takeLast(12)
                    .map {
                        "${it.id}|${it.phase}|${it.tool}|" +
                            "ok=${it.ok}|target=${it.target}|" +
                            "rev=${it.revision}|" +
                            it.excerpt
                    }
        )

    private fun evidenceView(
        context: Context,
        task: TaskState?,
        now: Long
    ): DiagnosticEvidenceView =
        runCatching {
            val snapshot =
                EvidenceGraphInspectorPolicy.build(
                    state =
                        EvidenceGraphStore.load(
                            context
                        ),
                    now = now.coerceAtLeast(1L)
                )
            val projectId =
                task?.projectId.orEmpty()
            DiagnosticEvidenceView(
                totalClaims =
                    snapshot.totalClaims,
                totalSources =
                    snapshot.totalSources,
                discovered =
                    snapshot.discovered,
                retrieved =
                    snapshot.retrieved,
                corroborated =
                    snapshot.corroborated,
                contested =
                    snapshot.contested,
                totalApplications =
                    snapshot.totalApplications,
                pendingApplications =
                    snapshot.pendingApplications,
                appliedApplications =
                    snapshot.appliedApplications,
                verifiedApplications =
                    snapshot.verifiedApplications,
                rejectedApplications =
                    snapshot.rejectedApplications,
                verifiedForCurrentProject =
                    snapshot.applications.count {
                        it.status == "VERIFIED" &&
                            (
                                projectId.isBlank() ||
                                    it.projectId ==
                                        projectId
                                )
                    },
                applications =
                    snapshot.applications
                        .take(12)
                        .map {
                            "${it.id}|${it.status}|" +
                                "project=${it.projectId}|" +
                                "target=${it.target}|" +
                                "artifact=${it.artifactEvidenceCount}|" +
                                "test=${it.testEvidenceCount}|" +
                                "claim=${it.claimKey}"
                        },
                claims =
                    snapshot.claims
                        .take(10)
                        .map {
                            "${it.claimKey}|" +
                                "state=${it.effectiveState}|" +
                                "outcome=${it.outcome}|" +
                                "project=${it.projectId.orEmpty()}|" +
                                "evidence=${it.evidenceCount}"
                        }
            )
        }.getOrElse { failure ->
            DiagnosticEvidenceView(
                error =
                    failure.message
                        ?: failure::class.simpleName
                        ?: "evidence graph failed"
            )
        }

    private fun genomeView(
        context: Context
    ): DiagnosticGenomeView =
        runCatching {
            val snapshot =
                ConstitutionGenomeInspectorPolicy.build(
                    localState =
                        ConstitutionGenomeStore.load(
                            context
                        ),
                    imported = null
                )
            val rules =
                (
                    snapshot.learned +
                        snapshot.shadowCandidates
                    )
                    .distinctBy { it.id }
                    .map {
                        DiagnosticGeneRow(
                            id = it.id,
                            claimKey = it.claimKey,
                            status = it.status,
                            authority = it.authority,
                            geneStage = it.geneStage,
                            activationPercent =
                                it.activationProgressPercent,
                            evidenceCount =
                                it.localEvidenceCount,
                            contexts =
                                it.distinctLocalContexts,
                            pairedProjectContexts =
                                it.pairedProjectContexts,
                            scope = it.scope
                        )
                    }

            DiagnosticGenomeView(
                hard =
                    snapshot.hardDna.size,
                learned =
                    snapshot.learned.size,
                shadow =
                    snapshot.shadowCandidates.size,
                userConstraints =
                    snapshot.userConstraints.size,
                contested =
                    snapshot.contested.size,
                rules = rules
            )
        }.getOrElse { failure ->
            DiagnosticGenomeView(
                error =
                    failure.message
                        ?: failure::class.simpleName
                        ?: "constitution genome failed"
            )
        }
}
