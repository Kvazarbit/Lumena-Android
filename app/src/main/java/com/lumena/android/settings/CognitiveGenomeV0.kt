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
    val counterexampleRecordIds: List<String> = emptyList(),
    val observedSuccesses: Int = 0,
    val observedFailures: Int = 0,
    /** Sanitized structured failure codes; never free-form model error prose. */
    val failureClasses: List<String> = emptyList(),
    val dependencyFingerprints: List<String> = emptyList(),
    /** No independent task-level verification is derivable from tool records. */
    val independentGoalProofs: Int = 0,
    /** Test names are review pointers, NOT runtime receipts or 100% proof. */
    val contractTestAnchors: List<String> = emptyList()
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

    fun derive(state: FractalExperienceCanvasState): List<CognitivePrinciple> {
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
        val positive = mutableListOf<CognitivePrinciple>()
        val negative = mutableListOf<CognitivePrinciple>()

        grouped.toSortedMap().forEach { (shape, members) ->
            val successes = members.filter {
                it.kind == CoordinatorExampleKind.VERIFIED_SEQUENCE &&
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
                failures.groupBy { record ->
                    record.outcomes.indexOfFirst { outcome -> !outcome }
                }.toSortedMap().forEach { (failedIndex, failedAtStep) ->
                    val failing = failedAtStep.first()
                    val prefix = failing.tools.take(failedIndex + 1)
                    val contested = successes.isNotEmpty()
                    negative += CognitivePrinciple(
                        id = "cg0-negative-$hash-$failedIndex",
                        stage = if (contested) CognitivePrincipleStage.CONTESTED
                            else CognitivePrincipleStage.NEGATIVE_DEAD_END,
                        codons = if (contested) codons else atomsFor(prefix),
                        mechanism = "Observed failed step/prefix; exact semantic cause is not proven.",
                        condition = "The same scoped step and dependency state; inspect before retry",
                        scopeHash = first.scopeHash,
                        toolPattern = if (contested) first.tools.joinToString(">")
                            else prefix.joinToString(">"),
                        evidenceIds = (successes + failedAtStep)
                            .flatMap { it.evidenceIds }
                            .filter(String::isNotBlank)
                            .distinct().sorted().take(64),
                        sourceTaskHashes = (successes + failedAtStep)
                            .map { it.sourceTaskHash }.distinct().sorted().take(64),
                        counterexampleRecordIds = (successes + failedAtStep)
                            .map { it.id }.distinct().sorted().take(64),
                        observedSuccesses = successes.size,
                        observedFailures = failedAtStep.size,
                        failureClasses = failedAtStep
                            .mapNotNull { it.failureClasses.getOrNull(failedIndex) }
                            .filter { it.matches(Regex("[A-Z0-9_]{1,80}")) }
                            .distinct().sorted().take(8),
                        dependencyFingerprints = failedAtStep
                            .mapNotNull { it.dependencies.getOrNull(failedIndex) }
                            .filter(String::isNotBlank)
                            .map { FractalExperienceCanvasPolicy.hash(it).take(16) }
                            .distinct().sorted().take(8)
                    )
                }
            } else if (successes.isNotEmpty()) {
                positive += CognitivePrinciple(
                    id = "cg0-positive-$hash",
                    stage = CognitivePrincipleStage.POSITIVE_CANDIDATE,
                    codons = codons,
                    mechanism = "A tool sequence succeeded; its goal-level utility remains unproven.",
                    condition = "Matching scope and verified current preconditions only",
                    scopeHash = first.scopeHash,
                    toolPattern = first.tools.joinToString(">"),
                    evidenceIds = shared,
                    sourceTaskHashes = sources,
                    observedSuccesses = successes.size
                )
            }
        }

        // A recovery and its failed prefix must BOTH survive. The parent Fractal
        // record already contributes the negative path above; causal links add
        // a separately labelled, evidence-grounded recovery candidate.
        val recovered = FractalCausalExperiencePolicy.links(records)
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
                    scopeHash = link.scopeHash,
                    toolPattern = (listOf(link.failedTool) + link.recoveryTools)
                        .joinToString(">"),
                    evidenceIds = link.evidenceIds.distinct().sorted().take(64),
                    sourceTaskHashes = listOf(link.sourceTaskHash),
                    counterexampleRecordIds = listOf(link.sourceRecordId),
                    observedSuccesses = link.recoveryOutcomes.count { it },
                    observedFailures = 1 + link.recoveryOutcomes.count { !it },
                    failureClasses = listOfNotNull(link.causeFailureClass)
                        .filter { it.matches(Regex("[A-Z0-9_]{1,80}")) },
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
        return ordered.distinctBy { it.id }.take(MAX_DERIVED)
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

    private fun shapeKey(record: FractalExampleRecord): String =
        record.scopeHash.orEmpty() + "|" +
            record.tools.joinToString(">") + "|" +
            // A fingerprint avoids replaying source paths/secrets in a skill.
            FractalExperienceCanvasPolicy.hash(
                record.targets.joinToString("|")
            ).take(24)

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
