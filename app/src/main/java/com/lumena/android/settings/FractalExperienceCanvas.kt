package com.lumena.android.settings

import com.lumena.android.agent.core.ToolRegistry
import java.security.MessageDigest

enum class FractalExperienceLevel {
    EPISODE,
    PATTERN,
    STRATEGY,
    META_RULE
}

enum class FractalExperiencePeak {
    BEST,
    WORST,
    UNKNOWN,
    CONTESTED
}

enum class FractalExperienceStage {
    SHADOW,
    TRANSFERRED_SHADOW
}

enum class FractalExperienceOrigin {
    LIVE,
    LEGACY_BACKFILL
}

data class FractalExampleRecord(
    val id: String,
    val kind: CoordinatorExampleKind,
    val sourceTaskHash: String,
    val tools: List<String>,
    val targets: List<String>,
    val outcomes: List<Boolean>,
    val evidenceIds: List<String>,
    val contributorModelIds: List<String>,
    val scopeHash: String?,
    val updatedAt: Long,
    val summary: String,
    val origin: FractalExperienceOrigin = FractalExperienceOrigin.LIVE,
    val failureClasses: List<String?> = emptyList(),
    val errorCodes: List<String?> = emptyList(),
    val retryableFlags: List<Boolean?> = emptyList(),
    val dependencies: List<String?> = emptyList()
)

data class FractalExperienceNode(
    val id: String,
    val level: FractalExperienceLevel,
    val peak: FractalExperiencePeak,
    val stage: FractalExperienceStage,
    val scopeHash: String?,
    val key: String,
    val summary: String,
    val supportCount: Int,
    val failureCount: Int,
    val distinctTasks: Int,
    val contributorModelIds: List<String>,
    val childIds: List<String>,
    val evidenceIds: List<String>,
    val counterexampleIds: List<String>,
    val updatedAt: Long,
    val confidence: Double
)

data class FractalLanguageObservation(
    val id: String,
    val phrase: String,
    val canonicalIntent: String,
    val sourceTaskHash: String,
    val at: Long
)

data class FractalLanguageCue(
    val phrase: String,
    val peak: FractalExperiencePeak,
    val stage: FractalExperienceStage,
    val canonicalIntent: String?,
    val observations: Int,
    val distinctTasks: Int,
    val competingIntents: List<String>,
    val updatedAt: Long
)

data class FractalExperienceCanvasState(
    val version: Int = 1,
    val records: List<FractalExampleRecord> = emptyList(),
    val nodes: List<FractalExperienceNode> = emptyList(),
    val languageObservations: List<FractalLanguageObservation> = emptyList(),
    val legacyBackfillVersion: Int = 0,
    /** Evaluated whole-task traces left by every model (see FractalTaskTrail). */
    val taskEpisodes: List<FractalTaskEpisode> = emptyList(),
    /** Explicit normative user priorities. Never evidence confidence. */
    val userValueWeights: List<FractalUserValueWeight> = emptyList()
)

object FractalLanguageIntentPolicy {
    fun canonicalIntent(
        codeContinued: Boolean,
        researchFollowUpKind: com.lumena.android.agent.core.ResearchFollowUpKind,
        resolvedGoal: String
    ): String = when {
        codeContinued -> "CONTINUE_CODE"
        researchFollowUpKind !=
            com.lumena.android.agent.core.ResearchFollowUpKind.NONE ->
            "RESEARCH_" + researchFollowUpKind.name
        else ->
            com.lumena.android.agent.core.TaskIntentRouter
                .route(resolvedGoal)
                .intent
                .name
    }
}

/**
 * Deterministic projection of verified coordinator examples into a four-level
 * experience hierarchy. This policy is advisory-only: it has no permission,
 * tool execution or Constitution activation API.
 */
object FractalExperienceCanvasPolicy {
    const val MAX_RECORDS = 512
    const val MAX_NODES = 1024
    const val MAX_LANGUAGE_OBSERVATIONS = 512
    const val MAX_SHORT_CUE_CHARS = 96

    fun ingest(
        state: FractalExperienceCanvasState,
        examples: List<CoordinatorExecutionExample>
    ): FractalExperienceCanvasState {
        if (examples.isEmpty()) return state

        return normalize(
            state.copy(
                version = 1,
                records = mergeRecords(
                    state.records,
                    examples.mapNotNull {
                        toRecord(it, FractalExperienceOrigin.LIVE)
                    }
                )
            )
        )
    }

    fun backfillLegacy(
        state: FractalExperienceCanvasState,
        examples: List<CoordinatorExecutionExample>
    ): FractalExperienceCanvasState {
        if (state.legacyBackfillVersion >= 1) return state

        val backfilled = examples.mapNotNull {
            toRecord(it, FractalExperienceOrigin.LEGACY_BACKFILL)
        }
        return normalize(
            state.copy(
                version = 1,
                records = mergeRecords(state.records, backfilled),
                legacyBackfillVersion = 1
            )
        )
    }

    fun normalize(
        state: FractalExperienceCanvasState
    ): FractalExperienceCanvasState {
        val records = mergeRecords(
            emptyList(),
            state.records
        )
        val language = state.languageObservations
            .distinctBy { it.id }
            .sortedWith(
                compareBy<FractalLanguageObservation> { it.at }
                    .thenBy { it.id }
            )
            .takeLast(MAX_LANGUAGE_OBSERVATIONS)
        val projectedNodes =
            project(records)
        // User-value records are audit history, not a cache. Keep them even
        // when compaction/reprojection temporarily removes the target node.
        // A missing node simply means the record has no ranking effect now.
        val userValues =
            FractalUserValueWeightPolicy
                .normalize(
                    state.userValueWeights
                )

        return state.copy(
            version = 1,
            records = records,
            nodes = projectedNodes,
            languageObservations = language,
            userValueWeights = userValues
        )
    }

    fun observeLanguage(
        state: FractalExperienceCanvasState,
        phrase: String,
        canonicalIntent: String,
        sourceTaskId: String,
        at: Long
    ): FractalExperienceCanvasState {
        val cue = normalizeCue(phrase) ?: return state
        if (canonicalIntent.isBlank() || at <= 0L || sourceTaskId.isBlank()) {
            return state
        }

        val taskHash = hash(sourceTaskId).take(16)
        val id = hash("$cue|$canonicalIntent|$taskHash").take(24)
        val observation = FractalLanguageObservation(
            id = id,
            phrase = cue,
            canonicalIntent = canonicalIntent.trim().take(80),
            sourceTaskHash = taskHash,
            at = at
        )
        if (state.languageObservations.any { it.id == id }) return state

        return normalize(
            state.copy(
                version = 1,
                languageObservations = state.languageObservations + observation
            )
        )
    }

    fun languageCues(
        state: FractalExperienceCanvasState,
        limit: Int = 32
    ): List<FractalLanguageCue> =
        state.languageObservations
            .groupBy { it.phrase }
            .map { (phrase, observations) ->
                val byIntent = observations.groupBy { it.canonicalIntent }
                val tasks = observations.map { it.sourceTaskHash }.toSet()
                val peak = when {
                    byIntent.size > 1 -> FractalExperiencePeak.CONTESTED
                    tasks.size < 2 -> FractalExperiencePeak.UNKNOWN
                    else -> FractalExperiencePeak.BEST
                }
                val stage =
                    if (
                        peak != FractalExperiencePeak.CONTESTED &&
                        tasks.size >= 2
                    ) {
                        FractalExperienceStage.TRANSFERRED_SHADOW
                    } else {
                        FractalExperienceStage.SHADOW
                    }
                FractalLanguageCue(
                    phrase = phrase,
                    peak = peak,
                    stage = stage,
                    canonicalIntent = byIntent.keys.singleOrNull(),
                    observations = observations.size,
                    distinctTasks = tasks.size,
                    competingIntents = byIntent.keys.sorted().take(8),
                    updatedAt = observations.maxOf { it.at }
                )
            }
            .sortedWith(
                compareByDescending<FractalLanguageCue> {
                    it.stage == FractalExperienceStage.TRANSFERRED_SHADOW
                }.thenByDescending { it.distinctTasks }
                    .thenByDescending { it.updatedAt }
            )
            .take(limit.coerceIn(1, 128))

    fun relevant(
        state: FractalExperienceCanvasState,
        query: String,
        scopeHash: String?,
        limit: Int = 6
    ): List<FractalExperienceNode> {
        val queryTokens = tokenize(query)
        return state.nodes
            .asSequence()
            .filter {
                it.level != FractalExperienceLevel.EPISODE &&
                    (scopeHash == null || it.scopeHash == scopeHash)
            }
            .map { node ->
                val tokens = tokenize(node.key + " " + node.summary)
                val overlap = tokens.count { it in queryTokens }
                node to overlap
            }
            .filter { (_, overlap) -> queryTokens.isEmpty() || overlap > 0 }
            .sortedWith(
                compareByDescending<Pair<FractalExperienceNode, Int>> {
                    it.second
                }.thenByDescending {
                    it.first.distinctTasks
                }.thenByDescending {
                    FractalUserValueWeightPolicy
                        .activeWeight(
                            state,
                            it.first.id
                        )
                }.thenByDescending {
                    it.first.confidence
                }.thenByDescending {
                    it.first.updatedAt
                }
            )
            .take(limit.coerceIn(1, 32))
            .map { it.first }
            .toList()
    }

    fun formatForPrompt(node: FractalExperienceNode): String =
        buildString {
            append(
                when (node.peak) {
                    FractalExperiencePeak.WORST ->
                        "FRACTAL IMMUNE-WORST "
                    FractalExperiencePeak.CONTESTED ->
                        "FRACTAL IMMUNE-CONTESTED "
                    FractalExperiencePeak.UNKNOWN ->
                        "FRACTAL UNKNOWN "
                    FractalExperiencePeak.BEST ->
                        "FRACTAL BEST "
                }
            )
            append(node.level.name)
            append(" · peak=")
            append(node.peak.name)
            append(" · stage=")
            append(node.stage.name)
            append(" · confidence=")
            append(((node.confidence.coerceIn(0.0, 1.0) * 100).toInt()))
            append("% · tasks=")
            append(node.distinctTasks)
            append(" · models=")
            append(node.contributorModelIds.size)
            append(" · ")
            append(node.summary)
            append(" · advisory only; revalidate current state")
        }
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(900)

    private fun project(
        records: List<FractalExampleRecord>
    ): List<FractalExperienceNode> {
        if (records.isEmpty()) return emptyList()

        val episodeNodes = records.map(::episodeNode)
        val patternNodes = aggregate(
            records = records,
            level = FractalExperienceLevel.PATTERN,
            keyOf = { record ->
                "pattern:" +
                    record.tools.zip(record.outcomes)
                        .joinToString(">") { (tool, ok) ->
                            "$tool:${if (ok) "ok" else "fail"}"
                        }
            },
            childIdOf = { record -> episodeId(record.id) },
            summaryOf = { key, grouped ->
                "Observed exact execution shape ${key.removePrefix("pattern:")}." +
                    topicHints(grouped)
            }
        )
        val patternByRecord = records.associate { record ->
            record.id to patternId(record)
        }
        val strategyNodes = aggregate(
            records = records,
            level = FractalExperienceLevel.STRATEGY,
            keyOf = { record ->
                "strategy:" +
                    record.tools.zip(record.outcomes)
                        .joinToString(">") { (tool, ok) ->
                            "${toolFamily(tool)}:${if (ok) "ok" else "fail"}"
                        }
            },
            childIdOf = { record -> patternByRecord.getValue(record.id) },
            summaryOf = { key, grouped ->
                "Coarse action strategy ${key.removePrefix("strategy:")}." +
                    topicHints(grouped)
            }
        )
        val strategyByRecord = records.associate { record ->
            record.id to strategyId(record)
        }
        val metaNodes = aggregate(
            records = records,
            level = FractalExperienceLevel.META_RULE,
            keyOf = { record -> "meta:" + metaRuleKey(record) },
            childIdOf = { record -> strategyByRecord.getValue(record.id) },
            summaryOf = { key, grouped ->
                metaRuleSummary(key.removePrefix("meta:")) +
                    topicHints(grouped)
            }
        )

        return (episodeNodes + patternNodes + strategyNodes + metaNodes)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<FractalExperienceNode> { it.level.ordinal }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.id }
            )
            .takeLast(MAX_NODES)
    }

    private fun aggregate(
        records: List<FractalExampleRecord>,
        level: FractalExperienceLevel,
        keyOf: (FractalExampleRecord) -> String,
        childIdOf: (FractalExampleRecord) -> String,
        summaryOf: (String, List<FractalExampleRecord>) -> String
    ): List<FractalExperienceNode> =
        records
            .groupBy { record ->
                val scope = record.scopeHash.orEmpty()
                "$scope|${keyOf(record)}"
            }
            .map { (_, grouped) ->
                val key = keyOf(grouped.first())
                val positive = grouped.filterNot(::isFailure)
                val negative = grouped.filter(::isFailure)
                val peak = peak(
                    support = positive.size,
                    failure = negative.size
                )
                val tasks = grouped.map { it.sourceTaskHash }.toSet()
                val children = grouped.map(childIdOf).distinct().sorted()
                val positiveChildren = positive.map(childIdOf).toSet()
                val negativeChildren = negative.map(childIdOf).toSet()
                val counterexamples = when (peak) {
                    FractalExperiencePeak.CONTESTED ->
                        (positiveChildren + negativeChildren).sorted()
                    FractalExperiencePeak.BEST ->
                        negativeChildren.sorted()
                    FractalExperiencePeak.WORST ->
                        positiveChildren.sorted()
                    FractalExperiencePeak.UNKNOWN ->
                        emptyList()
                }
                val scope = grouped.first().scopeHash
                val id = nodeId(level, scope, key)
                FractalExperienceNode(
                    id = id,
                    level = level,
                    peak = peak,
                    stage = stage(
                        peak = peak,
                        records = grouped,
                        distinctTasks = tasks.size
                    ),
                    scopeHash = scope,
                    key = key,
                    summary = summaryOf(key, grouped).take(600),
                    supportCount = positive.size,
                    failureCount = negative.size,
                    distinctTasks = tasks.size,
                    contributorModelIds = grouped
                        .flatMap { it.contributorModelIds }
                        .distinct()
                        .sorted()
                        .take(16),
                    childIds = children.take(64),
                    evidenceIds = grouped
                        .flatMap { it.evidenceIds }
                        .distinct()
                        .take(64),
                    counterexampleIds = counterexamples.take(64),
                    updatedAt = grouped.maxOf { it.updatedAt },
                    confidence = confidence(
                        support = positive.size,
                        failure = negative.size,
                        distinctTasks = tasks.size
                    )
                )
            }

    private fun episodeNode(
        record: FractalExampleRecord
    ): FractalExperienceNode {
        val failure = isFailure(record)
        val peak =
            if (failure) FractalExperiencePeak.WORST
            else FractalExperiencePeak.BEST
        return FractalExperienceNode(
            id = episodeId(record.id),
            level = FractalExperienceLevel.EPISODE,
            peak = peak,
            stage = FractalExperienceStage.SHADOW,
            scopeHash = record.scopeHash,
            key = "episode:${record.id}",
            summary = record.summary.take(600),
            supportCount = if (failure) 0 else 1,
            failureCount = if (failure) 1 else 0,
            distinctTasks = 1,
            contributorModelIds = record.contributorModelIds.take(16),
            childIds = emptyList(),
            evidenceIds = record.evidenceIds.take(64),
            counterexampleIds = emptyList(),
            updatedAt = record.updatedAt,
            confidence = 0.34
        )
    }

    private fun toRecord(
        example: CoordinatorExecutionExample,
        origin: FractalExperienceOrigin
    ): FractalExampleRecord? {
        if (example.id.isBlank() || example.updatedAt <= 0L) return null
        if (example.tools.isEmpty()) return null
        if (example.outcomes.size != example.tools.size) return null
        if (example.evidenceIds.none(String::isNotBlank)) return null
        val tools = example.tools
            .map(ToolRegistry::canonicalize)
        if (tools.any { ToolRegistry.get(it) == null }) return null

        return FractalExampleRecord(
            id = example.id.take(128),
            kind = example.kind,
            sourceTaskHash = example.sourceSessionHash.take(64),
            tools = tools.take(16),
            targets = example.targets
                .map { sanitizeTarget(it) }
                .filter(String::isNotBlank)
                .take(16),
            outcomes = example.outcomes.take(16),
            evidenceIds = example.evidenceIds
                .filter(String::isNotBlank)
                .distinct()
                .take(64),
            contributorModelIds = example.contributorModelIds
                .filter(String::isNotBlank)
                .distinct()
                .map { it.take(160) }
                .take(16),
            scopeHash = example.scopeHash
                ?.takeIf(String::isNotBlank)
                ?.take(64),
            updatedAt = example.updatedAt,
            summary = CoordinatorExperiencePolicy.formatForPrompt(example),
            origin = origin,
            failureClasses = List(tools.take(16).size) { index ->
                sanitizeOutcomeMetadata(example.failureClasses.getOrNull(index), 120)
            },
            errorCodes = List(tools.take(16).size) { index ->
                sanitizeOutcomeMetadata(example.errorCodes.getOrNull(index), 120)
            },
            retryableFlags = List(tools.take(16).size) { index ->
                example.retryableFlags.getOrNull(index)
            },
            dependencies = List(tools.take(16).size) { index ->
                sanitizeOutcomeMetadata(example.dependencies.getOrNull(index), 160)
            }
        )
    }

    private fun isFailure(record: FractalExampleRecord): Boolean =
        record.kind == CoordinatorExampleKind.FAILED_RECOVERY ||
            record.outcomes.lastOrNull() == false

    private fun patternId(record: FractalExampleRecord): String {
        val key = "pattern:" +
            record.tools.zip(record.outcomes)
                .joinToString(">") { (tool, ok) ->
                    "$tool:${if (ok) "ok" else "fail"}"
                }
        return nodeId(FractalExperienceLevel.PATTERN, record.scopeHash, key)
    }

    private fun strategyId(record: FractalExampleRecord): String {
        val key = "strategy:" +
            record.tools.zip(record.outcomes)
                .joinToString(">") { (tool, ok) ->
                    "${toolFamily(tool)}:${if (ok) "ok" else "fail"}"
                }
        return nodeId(FractalExperienceLevel.STRATEGY, record.scopeHash, key)
    }

    private fun episodeId(exampleId: String): String =
        "fx-episode-" + hash(exampleId).take(20)

    private fun nodeId(
        level: FractalExperienceLevel,
        scopeHash: String?,
        key: String
    ): String =
        "fx-${level.name.lowercase()}-" +
            hash(scopeHash.orEmpty() + "|" + key).take(20)

    private fun peak(
        support: Int,
        failure: Int
    ): FractalExperiencePeak = when {
        support > 0 && failure > 0 -> FractalExperiencePeak.CONTESTED
        support > 0 -> FractalExperiencePeak.BEST
        failure > 0 -> FractalExperiencePeak.WORST
        else -> FractalExperiencePeak.UNKNOWN
    }

    private fun stage(
        peak: FractalExperiencePeak,
        records: List<FractalExampleRecord>,
        distinctTasks: Int
    ): FractalExperienceStage =
        if (
            peak != FractalExperiencePeak.CONTESTED &&
            peak != FractalExperiencePeak.UNKNOWN &&
            distinctTasks >= 2 &&
            records.any { it.origin == FractalExperienceOrigin.LIVE }
        ) {
            FractalExperienceStage.TRANSFERRED_SHADOW
        } else {
            FractalExperienceStage.SHADOW
        }

    private fun mergeRecords(
        existing: List<FractalExampleRecord>,
        incoming: List<FractalExampleRecord>
    ): List<FractalExampleRecord> {
        val merged = linkedMapOf<String, FractalExampleRecord>()
        (existing + incoming).forEach { candidate ->
            val previous = merged[candidate.id]
            merged[candidate.id] = when {
                previous == null -> candidate
                previous.origin == FractalExperienceOrigin.LIVE &&
                    candidate.origin == FractalExperienceOrigin.LEGACY_BACKFILL ->
                    previous
                candidate.origin == FractalExperienceOrigin.LIVE &&
                    previous.origin == FractalExperienceOrigin.LEGACY_BACKFILL ->
                    candidate
                candidate.updatedAt >= previous.updatedAt -> candidate
                else -> previous
            }
        }
        return merged.values
            .sortedWith(
                compareBy<FractalExampleRecord> { it.updatedAt }
                    .thenBy { it.id }
            )
            .takeLast(MAX_RECORDS)
    }

    private fun confidence(
        support: Int,
        failure: Int,
        distinctTasks: Int
    ): Double {
        val total = support + failure
        if (total <= 0) return 0.0
        val agreement = maxOf(support, failure).toDouble() / total.toDouble()
        val diversity = (distinctTasks.coerceAtMost(3) / 3.0)
        return (agreement * (0.35 + 0.65 * diversity))
            .coerceIn(0.0, 1.0)
    }

    private fun toolFamily(tool: String): String = when {
        tool in setOf(
            "context.snapshot", "workspace.list", "file.list", "file.search",
            "file.read", "git.status", "git.diff", "git.log", "system.info",
            "system.time", "health", "process.status"
        ) -> "OBSERVE"

        tool in setOf(
            "web.search", "web.read", "http.get", "http.json", "image.search"
        ) -> "RESEARCH"

        tool in setOf(
            "file.write", "file.patch", "dir.create", "project.create",
            "git.add", "git.commit"
        ) -> "MUTATE"

        tool.startsWith("python.") -> "VERIFY"
        tool.startsWith("ollama.") -> "MODEL_RUNTIME"
        else -> "OTHER"
    }

    private fun metaRuleKey(
        record: FractalExampleRecord
    ): String {
        if (record.kind == CoordinatorExampleKind.FAILED_RECOVERY) {
            return "AVOID_BLIND_REPEAT_AFTER_FAILURE"
        }
        if (record.kind == CoordinatorExampleKind.RECOVERY) {
            return "RECOVERY_REQUIRES_NEW_VERIFIED_EVIDENCE"
        }

        val families = record.tools.map(::toolFamily)
        val mutate = families.indexOf("MUTATE")
        val verifyAfter = families.indexOfFirst { it == "VERIFY" }
        if (mutate >= 0 && verifyAfter > mutate) {
            return "MUTATE_THEN_VERIFY"
        }

        val observe = families.indexOf("OBSERVE")
        if (observe >= 0 && mutate > observe) {
            return "OBSERVE_BEFORE_MUTATE"
        }

        return "CHAIN_VERIFIED_STEPS"
    }

    private fun metaRuleSummary(key: String): String = when (key) {
        "AVOID_BLIND_REPEAT_AFTER_FAILURE" ->
            "A failed action should not be blindly repeated without new grounding or a materially different recovery path."

        "RECOVERY_REQUIRES_NEW_VERIFIED_EVIDENCE" ->
            "Treat recovery as successful only after a new verified tool result changes the failed state."

        "MUTATE_THEN_VERIFY" ->
            "After changing project state, obtain independent verification before treating the implementation as complete."

        "OBSERVE_BEFORE_MUTATE" ->
            "Ground project changes in current observed state before mutation."

        else ->
            "Prefer short chains of verified steps over unsupported completion claims."
    }

    private fun topicHints(
        records: List<FractalExampleRecord>
    ): String {
        val hints = records
            .flatMap { it.targets }
            .filter(String::isNotBlank)
            .distinct()
            .take(4)
        return if (hints.isEmpty()) {
            ""
        } else {
            " targets=" + hints.joinToString(",")
        }
    }

    private fun sanitizeTarget(value: String): String =
        value
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(240)

    private fun sanitizeOutcomeMetadata(
        value: String?,
        maxChars: Int
    ): String? =
        value
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.replace(Regex("\\s{2,}"), " ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(maxChars)

    private fun normalizeCue(raw: String): String? {
        val trimmed = raw
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .lowercase()
            .trimEnd('.', '!', '?', '…')
            .trim()
        if (
            trimmed.isBlank() ||
            trimmed.length > MAX_SHORT_CUE_CHARS ||
            "://" in trimmed
        ) {
            return null
        }
        return trimmed
    }

    private fun tokenize(value: String): Set<String> {
        val lower = value.lowercase()
        val compound = lower
            .split(Regex("[^\\p{L}\\p{N}._:@/=-]+"))
            .filter { it.length >= 2 }
        val components = lower
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        return (compound + components).toSet()
    }

    fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
