package com.lumena.android.settings

/**
 * Cognitive Genome V0: a small, deterministic vocabulary for reasoning traces.
 *
 * This is an advisory projection of existing Fractal Canvas evidence, NOT a
 * second database, a learned model, an execution API, or a replacement for the
 * Constitution/ToolGate. Four symbols express operation TYPES, not semantics:
 *
 *   O = observe, H = hypothesize, A = act, V = verify.
 *
 * A codon stream alone is not an executable program. Its meaning requires
 * scoped provenance, conditions, counterexamples, and postcondition evidence.
 */
enum class CognitiveAtom(val symbol: Char) {
    OBSERVE('O'), HYPOTHESIZE('H'), ACT('A'), VERIFY('V')
}

object CognitiveGenomeCodec {
    const val VERSION = "CG0"
    const val MAX_ATOMS = 48

    fun encode(atoms: List<CognitiveAtom>): String {
        require(atoms.isNotEmpty() && atoms.size <= MAX_ATOMS)
        return VERSION + ":" +
            atoms.joinToString("") { it.symbol.toString() }
                .chunked(3).joinToString(".")
    }

    /** Strict, canonical, versioned, lossless round-trip for the symbol stream. */
    fun decode(codons: String): List<CognitiveAtom> {
        require(codons.startsWith("$VERSION:"))
        val groups = codons.removePrefix("$VERSION:").split('.')
        require(groups.isNotEmpty() && groups.all { it.isNotEmpty() && it.length <= 3 })
        require(groups.dropLast(1).all { it.length == 3 })
        val symbols = groups.joinToString("")
        require(symbols.isNotEmpty() && symbols.length <= MAX_ATOMS)
        val atoms = symbols.map { symbol ->
            CognitiveAtom.values().singleOrNull { it.symbol == symbol }
                ?: throw IllegalArgumentException("Unknown cognitive atom")
        }
        require(encode(atoms) == codons) { "Non-canonical cognitive genome" }
        return atoms
    }
}

enum class CognitivePrincipleStage {
    /** Locally specified kernel rule; NOT a claim of universal empirical truth. */
    KERNEL_CONTRACT,
    /** A tool-success trace, not an independently verified user-level gain. */
    POSITIVE_CANDIDATE,
    /** One or more tool failures with preserved source evidence. */
    NEGATIVE_DEAD_END,
    /** A failed step followed by an observed recovery result. */
    RECOVERY_CANDIDATE,
    /** Both success and failure are observed for the same scoped action shape. */
    CONTESTED
}

data class CognitivePrinciple(
    val id: String,
    val stage: CognitivePrincipleStage,
    val codons: String,
    val mechanism: String,
    val condition: String,
    val scopeHash: String?,
    val toolPattern: String,
    val evidenceIds: List<String>,
    val sourceTaskHashes: List<String>,
    /** Source IDs are not counterexamples; opposing observations are. */
    val sourceRecordIds: List<String> = emptyList(),
    val counterexampleRecordIds: List<String> = emptyList(),
    val observedSuccesses: Int = 0,
    val observedFailures: Int = 0,
    /** Sanitized structured failure codes; never free-form model error prose. */
    val failureClasses: List<String> = emptyList(),
    val dependencyFingerprints: List<String> = emptyList(),
    /** No independent task-level verification is derivable from tool records. */
    val independentGoalProofs: Int = 0,
    /** Test names are review pointers, NOT runtime receipts or 100% proof. */
    val contractTestAnchors: List<String> = emptyList(),
    val conditions: ExperienceConditions = ExperienceConditions(),
    val targetKinds: List<String> = emptyList(),
    val applicabilityFingerprint: String = "UNKNOWN",
    val causeKnowledge: String = "UNKNOWN",
    val observedPostcondition: String = "TOOL_RECEIPTS_ONLY"
)

data class CognitiveGenomeSummary(
    val contractPrinciples: Int,
    val positiveCandidates: Int,
    val negativeDeadEnds: Int,
    val recoveryCandidates: Int,
    val contested: Int,
    val independentGoalProofs: Int
)

/**
 * Model-independent, read-only, reversible projection.
 *
 * Positive evidence requires actual recorded success and evidence IDs.
 * Negative evidence is NEVER overwritten by a later positive. A contradiction
 * stays CONTESTED. No trace becomes a "golden" rule by repetition, confidence,
 * contributor count, or a fabricated 100% success rate.
 */
object CognitiveGenomePolicy {
    const val VERSION = "cognitive-genome-v0"
    const val MAX_DERIVED = 256
    // At most 16 failed steps + one recovery + one positive per archived source.
    const val MAX_ARCHIVED_DERIVED = 180_004

    private fun code(vararg atoms: CognitiveAtom): String =
        CognitiveGenomeCodec.encode(atoms.toList())

    /** These describe contracts to check in the LOCAL implementation. */
    fun contractCatalog(): List<CognitivePrinciple> = listOf(
        CognitivePrinciple(
            id = "cg0-contract-receipt",
            stage = CognitivePrincipleStage.KERNEL_CONTRACT,
            codons = code(CognitiveAtom.OBSERVE, CognitiveAtom.VERIFY),
            mechanism = "Model prose is not a tool receipt.",
            condition = "Claims of local execution",
            scopeHash = null,
            toolPattern = "ContextKernel:TOOL_RESULT",
            evidenceIds = emptyList(),
            sourceTaskHashes = emptyList(),
            contractTestAnchors = listOf("CognitiveContourAutonomyLabTest")
        ),
        CognitivePrinciple(
            id = "cg0-contract-authority",
            stage = CognitivePrincipleStage.KERNEL_CONTRACT,
            codons = code(CognitiveAtom.OBSERVE, CognitiveAtom.ACT, CognitiveAtom.VERIFY),
            mechanism = "History and model advice never grant permission to execute.",
            condition = "Every proposed tool call",
            scopeHash = null,
            toolPattern = "ToolRegistry:ToolGate",
            evidenceIds = emptyList(),
            sourceTaskHashes = emptyList(),
            contractTestAnchors = listOf("ModuleKernelBoundaryTest", "TrustGateTest")
        ),
        CognitivePrinciple(
            id = "cg0-contract-goal",
            stage = CognitivePrincipleStage.KERNEL_CONTRACT,
            codons = code(CognitiveAtom.ACT, CognitiveAtom.VERIFY),
            mechanism = "Tool success alone does not prove goal completion.",
            condition = "Tasks with typed mandatory acceptance criteria",
            scopeHash = null,
            toolPattern = "GoalContract:ContextKernel",
            evidenceIds = emptyList(),
            sourceTaskHashes = emptyList(),
            contractTestAnchors = listOf("CognitiveContourAutonomyLabTest", "AgentControllerTest")
        ),
        CognitivePrinciple(
            id = "cg0-contract-unknown",
            stage = CognitivePrincipleStage.KERNEL_CONTRACT,
            codons = code(CognitiveAtom.OBSERVE, CognitiveAtom.HYPOTHESIZE, CognitiveAtom.VERIFY),
            mechanism = "Unknown and conflicting results are not positive training labels.",
            condition = "Missing, failed, or contradictory verification",
            scopeHash = null,
            toolPattern = "Evidence:UNKNOWN:CONTESTED",
            evidenceIds = emptyList(),
            sourceTaskHashes = emptyList(),
            contractTestAnchors = listOf("FractalCausalExperiencePolicyTest")
        )
    )

    fun derive(
        state: FractalExperienceCanvasState,
        limit: Int = MAX_DERIVED
    ): List<CognitivePrinciple> {
        require(limit in 1..MAX_ARCHIVED_DERIVED)
        // Only historical tool receipts with shape-consistent outcomes.
        // Validate against the STABLE accepted manifests, not the current
        // enabled-modules list. Disabled modules are valid historical sources
        // but cannot be executed through the live ToolGate.
        val knownHistoricalTools =
            com.lumena.android.agent.core.ToolRegistry.kernelToolNames() +
                com.lumena.android.agent.core.ModuleRegistry
                    .accept(com.lumena.android.agent.core.ModuleRegistry.all())
                    .flatMap { module -> module.manifest.tools.map { it.spec.name } }
                    .toSet()
        val records = state.records.filter {
            it.tools.isNotEmpty() &&
                it.tools.size == it.outcomes.size &&
                it.evidenceIds.isNotEmpty() &&
                it.tools.all { name -> name in knownHistoricalTools } &&
                (it.kind != CoordinatorExampleKind.FAILED_RECOVERY ||
                    it.outcomes.any { outcome -> !outcome })
        }
        val grouped = records.groupBy(::shapeKey)
        val successfulSteps = records.filter {
            it.kind in setOf(CoordinatorExampleKind.VERIFIED_SEQUENCE, CoordinatorExampleKind.TOOL_OUTCOME) && it.outcomes.all { ok -> ok }
        }.flatMap { record -> record.tools.indices.map { step -> actionKey(record, step) to record } }
            .groupBy({ it.first }, { it.second })
        val failedActions = records.flatMap { record ->
            record.outcomes.indices.filter { !record.outcomes[it] }.map { actionKey(record, it) }
        }.toSet()
        val positive = mutableListOf<CognitivePrinciple>()
        val negative = mutableListOf<CognitivePrinciple>()

        grouped.toSortedMap().forEach { (shape, members) ->
            val successes = members.filter {
                it.kind in setOf(CoordinatorExampleKind.VERIFIED_SEQUENCE, CoordinatorExampleKind.TOOL_OUTCOME) &&
                    it.outcomes.all { outcome -> outcome }
            }
            val failures = members.filter { it.outcomes.any { outcome -> !outcome } }
            val first = members.first()
            val hash = FractalExperienceCanvasPolicy.hash(shape).take(20)
            val codons = atomsFor(first.tools)
            val shared = members.flatMap { it.evidenceIds }
                .filter(String::isNotBlank).distinct().sorted().take(64)
            val sources = members.map { it.sourceTaskHash }
                .distinct().sorted().take(64)
            if (failures.isNotEmpty()) {
                // A recovered multi-step sequence must not be mislabeled as
                // an entirely bad route: identify each actually FAILED
                // prefix independently. Different failed steps are different
                // dead ends even when the full action shape is identical.
                val byFailedStep = failures.flatMap { record ->
                    record.outcomes.indices
                        .filter { index -> !record.outcomes[index] }
                        .map { index -> index to record }
                }.groupBy({ it.first }, { it.second })
                byFailedStep.toSortedMap().forEach { (failedIndex, failedAtStep) ->
                    val failing = failedAtStep.first()
                    val prefix = failing.tools.take(failedIndex + 1)
                    val opposites = successfulSteps[actionKey(failing, failedIndex)].orEmpty()
                        .distinctBy { it.sourceTaskHash }
                    val contested = opposites.isNotEmpty()
                    negative += CognitivePrinciple(
                        id = "cg0-negative-$hash-$failedIndex",
                        stage = if (contested) CognitivePrincipleStage.CONTESTED
                            else CognitivePrincipleStage.NEGATIVE_DEAD_END,
                        codons = if (contested) codons else atomsFor(prefix),
                        mechanism = "Observed failed step/prefix; exact semantic cause is not proven.",
                        condition = "The same scoped step and dependency state; inspect before retry",
                        scopeHash = ExperienceScope.normalized(first.scopeHash),
                        toolPattern = if (contested) first.tools.joinToString(">")
                            else prefix.joinToString(">"),
                        evidenceIds = (opposites + failedAtStep)
                            .flatMap { it.evidenceIds }
                            .filter(String::isNotBlank)
                            .distinct().sorted().take(64),
                        sourceTaskHashes = (opposites + failedAtStep)
                            .map { it.sourceTaskHash }.distinct().sorted().take(64),
                        sourceRecordIds = failedAtStep.map { it.id }
                            .distinct().sorted().take(64),
                        counterexampleRecordIds = opposites.map { it.id }
                            .distinct().sorted().take(64),
                        observedSuccesses = opposites.size,
                        observedFailures = failedAtStep.map { it.sourceTaskHash }.distinct().size,
                        failureClasses = failedAtStep
                            .mapNotNull { it.failureClasses.getOrNull(failedIndex) }
                            .filter { it.matches(Regex("[A-Z0-9_]{1,80}")) }
                            .distinct().sorted().take(8),
                        conditions = first.conditions,
                        targetKinds = targetKinds(failing),
                        applicabilityFingerprint = FractalExperienceCanvasPolicy.hash(actionKey(failing, failedIndex)),
                        causeKnowledge = "UNKNOWN",
                        observedPostcondition = "FAILED_TOOL_STEP",
                        dependencyFingerprints = failedAtStep
                            .mapNotNull { it.dependencies.getOrNull(failedIndex) }
                            .filter(String::isNotBlank)
                            .map { FractalExperienceCanvasPolicy.hash(it).take(16) }
                            .distinct().sorted().take(8)
                    )
                }
            } else if (successes.isNotEmpty() && first.tools.indices.none {
                actionKey(first, it) in failedActions
            }) {
                positive += CognitivePrinciple(
                    id = "cg0-positive-$hash",
                    stage = CognitivePrincipleStage.POSITIVE_CANDIDATE,
                    codons = codons,
                    mechanism = "A tool sequence succeeded; its goal-level utility remains unproven.",
                    condition = "Matching scope and verified current preconditions only",
                    scopeHash = ExperienceScope.normalized(first.scopeHash),
                    toolPattern = first.tools.joinToString(">"),
                    evidenceIds = shared,
                    sourceTaskHashes = sources,
                    observedSuccesses = successes.map { it.sourceTaskHash }.distinct().size,
                    conditions = first.conditions,
                    targetKinds = targetKinds(first),
                    applicabilityFingerprint = FractalExperienceCanvasPolicy.hash(shape),
                    dependencyFingerprints = first.dependencies.filterNotNull()
                        .map { FractalExperienceCanvasPolicy.hash(it).take(16) }.distinct(),
                    sourceRecordIds = successes.map { it.id }
                        .distinct().sorted().take(64)
                )
            }
        }

        // A recovery and its failed prefix must BOTH survive. The parent Fractal
        // record already contributes the negative path above; causal links add
        // a separately labelled, evidence-grounded recovery candidate.
        val recovered = FractalCausalExperiencePolicy.links(records,
            limit = records.size.coerceIn(1, FractalEvidenceLedgerPolicy.MAX_REVISIONS))
            .filter { it.resolution == FractalCausalResolution.RECOVERED }
            .map { link ->
                CognitivePrinciple(
                    id = "cg0-recovery-" +
                        FractalExperienceCanvasPolicy.hash(link.id).take(20),
                    stage = CognitivePrincipleStage.RECOVERY_CANDIDATE,
                    codons = atomsFor(
                        listOf(link.failedTool) + link.recoveryTools
                    ),
                    mechanism = "Observed recovery after failure; semantic cause may still be unknown.",
                    condition = "A fresh local failure with independently rechecked conditions",
                    scopeHash = ExperienceScope.normalized(link.scopeHash),
                    toolPattern = (listOf(link.failedTool) + link.recoveryTools)
                        .joinToString(">"),
                    evidenceIds = link.evidenceIds.distinct().sorted().take(64),
                    sourceTaskHashes = listOf(link.sourceTaskHash),
                    sourceRecordIds = listOf(link.sourceRecordId),
                    observedSuccesses = link.recoveryOutcomes.count { it },
                    observedFailures = 1 + link.recoveryOutcomes.count { !it },
                    failureClasses = listOfNotNull(link.causeFailureClass)
                        .filter { it.matches(Regex("[A-Z0-9_]{1,80}")) },
                    conditions = records.first { it.id == link.sourceRecordId }.conditions,
                    targetKinds = targetKinds(records.first { it.id == link.sourceRecordId }),
                    applicabilityFingerprint = FractalExperienceCanvasPolicy.hash(
                        shapeKey(records.first { it.id == link.sourceRecordId })
                    ),
                    observedPostcondition = "RETRY_TOOL_SUCCEEDED_NOT_GOAL_PROOF",
                    dependencyFingerprints = listOfNotNull(link.causeDependency)
                        .map { FractalExperienceCanvasPolicy.hash(it).take(16) }
                )
            }

        // Risk preserving eviction: contracts first, then negative/contested,
        // then recovery, with ordinary positive examples last.
        val ordered = contractCatalog() +
            negative.sortedBy { it.id } +
            recovered.sortedBy { it.id } +
            positive.sortedBy { it.id }
        return ordered.distinctBy { it.id }.take(limit)
    }

    fun summary(principles: List<CognitivePrinciple>): CognitiveGenomeSummary =
        CognitiveGenomeSummary(
            contractPrinciples = principles.count {
                it.stage == CognitivePrincipleStage.KERNEL_CONTRACT
            },
            positiveCandidates = principles.count {
                it.stage == CognitivePrincipleStage.POSITIVE_CANDIDATE
            },
            negativeDeadEnds = principles.count {
                it.stage == CognitivePrincipleStage.NEGATIVE_DEAD_END
            },
            recoveryCandidates = principles.count {
                it.stage == CognitivePrincipleStage.RECOVERY_CANDIDATE
            },
            contested = principles.count {
                it.stage == CognitivePrincipleStage.CONTESTED
            },
            independentGoalProofs = principles.sumOf { it.independentGoalProofs }
        )

    /** No prose from tools/models enters this bounded suggestion. */
    fun advisoryLine(rule: CognitivePrinciple): String =
        ("COGNITIVE_GENOME " + VERSION +
            " id=" + rule.id +
            " stage=" + rule.stage.name +
            " codons=" + rule.codons +
            " tools=" + rule.toolPattern +
            " successes=" + rule.observedSuccesses +
            " failures=" + rule.observedFailures +
            " independent_goal_proofs=" + rule.independentGoalProofs +
            " failure_classes=" + rule.failureClasses.joinToString(",") +
            " · advisory only; not permission or universal truth.")
            .take(500)

    /** Length-delimited keys avoid conflating paths containing separators. */
    private fun key(parts: List<String>): String = parts.joinToString("") { "${it.length}:$it" }

    private fun shapeKey(record: FractalExampleRecord): String = key(listOf(
        ExperienceScope.normalized(record.scopeHash).orEmpty(),
        key(record.tools), key(record.targets), key(record.dependencies.map { it ?: "UNKNOWN" }),
        key(record.argumentFingerprints.map { it ?: "UNKNOWN" }),
        record.conditions.toString()
    ))

    private fun actionKey(record: FractalExampleRecord, step: Int): String = key(listOf(
        ExperienceScope.normalized(record.scopeHash).orEmpty(), record.tools[step],
        // Old single-target records describe a shared target. Incomplete multi-target
        // records cannot establish cross-sequence contradictions without alignment.
        if (record.targets.size == record.tools.size) record.targets[step]
        else if (record.targets.size == 1) record.targets.single()
        else "UNALIGNED:" + key(record.tools) + key(record.targets) + step,
        record.dependencies.getOrNull(step) ?: "UNKNOWN",
        record.argumentFingerprints.getOrNull(step) ?: "UNKNOWN",
        record.conditions.toString()
    ))

    /** Only allowlisted resource TYPES, never a source filename, URL or secret. */
    private fun targetKinds(record: FractalExampleRecord): List<String> {
        val suffixes = setOf("py", "kt", "kts", "js", "ts", "json", "html", "css", "md", "txt", "csv")
        return record.targets.flatMap { target ->
            Regex("""\.([a-zA-Z0-9]{1,5})(?:$|[ ?&#,;])""").findAll(target).mapNotNull {
                it.groupValues[1].lowercase().takeIf { ext -> ext in suffixes }
            }.map { "FILE_" + it.uppercase() }.toList()
        }.distinct().sorted()
    }

    private fun atomsFor(tools: List<String>): String =
        CognitiveGenomeCodec.encode(
            tools.take(CognitiveGenomeCodec.MAX_ATOMS).map { tool ->
                when {
                    tool == "python.tests" || tool == "python.syntax_check" ->
                        CognitiveAtom.VERIFY
                    tool == "ollama.generate" -> CognitiveAtom.HYPOTHESIZE
                    tool in setOf("file.read", "file.list", "file.search",
                        "workspace.list", "context.snapshot", "health",
                        "system.info", "system.time", "git.status",
                        "git.diff", "git.log", "process.status") ||
                        tool.endsWith(".search") || tool.endsWith(".read") ||
                        tool.endsWith(".list") || tool.startsWith("web.") ||
                        tool.startsWith("http.") -> CognitiveAtom.OBSERVE
                    else -> CognitiveAtom.ACT
                }
            }
        )
}
