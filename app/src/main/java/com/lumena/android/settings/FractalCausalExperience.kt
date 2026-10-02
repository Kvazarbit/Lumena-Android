package com.lumena.android.settings

import java.security.MessageDigest

enum class FractalCausalResolution {
    RECOVERED,
    FAILED_AGAIN
}

enum class FractalCausalCauseKnowledge {
    UNKNOWN_NOT_CAPTURED,
    STRUCTURED_TOOL_FAILURE
}

data class FractalCausalLink(
    val id: String,
    val sourceRecordId: String,
    val sourceTaskHash: String,
    val scopeHash: String?,
    val failedStepIndex: Int,
    val failedTool: String,
    val targetHints: List<String>,
    val recoveryTools: List<String>,
    val recoveryOutcomes: List<Boolean>,
    val resolution: FractalCausalResolution,
    val causeKnowledge: FractalCausalCauseKnowledge,
    val causeFailureClass: String? = null,
    val causeErrorCode: String? = null,
    val causeRetryable: Boolean? = null,
    val causeDependency: String? = null,
    val evidenceIds: List<String>,
    val contributorModelIds: List<String>,
    val origin: FractalExperienceOrigin,
    val updatedAt: Long
)

data class FractalCausalStats(
    val links: Int,
    val recovered: Int,
    val unresolved: Int,
    val liveLinks: Int,
    val legacyLinks: Int,
    val revalidatedPatterns: Int,
    val causeUnknown: Int = 0,
    val causeStructured: Int = 0,
    val causeHypothesis: Int = 0,
    val causeProbed: Int = 0,
    val causeVerified: Int = 0,
    val causeContested: Int = 0,
    val causeRejected: Int = 0
)

/**
 * Deterministic causal projection over already-verified Fractal records.
 *
 * Coordinator memory proves tool outcomes and ordering. When ToolResult also
 * supplies structured failure metadata (failureClass/errorCode/retryable/
 * dependency), v1 carries that metadata as evidence-grounded WHY_FAILED.
 * Otherwise WHY_FAILED remains UNKNOWN_NOT_CAPTURED; semantic causes are never
 * inferred from prose or invented.
 *
 * Advisory-only: no ToolGate, permission, execution, Constitution-promotion,
 * or Laya-authority API exists here.
 */
object FractalCausalExperiencePolicy {
    const val MAX_LINKS = 512

    fun links(
        records: List<FractalExampleRecord>
    ): List<FractalCausalLink> =
        records
            .mapNotNull(::toLink)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<FractalCausalLink> { it.updatedAt }
                    .thenBy { it.id }
            )
            .takeLast(MAX_LINKS)

    fun stats(
        records: List<FractalExampleRecord>
    ): FractalCausalStats {
        val links = links(records)
        val revalidated = links
            .groupBy(::structuralKey)
            .values
            .count { group ->
                val origins = group.map { it.origin }.toSet()
                val tasks = group.map { it.sourceTaskHash }.toSet()
                origins.contains(FractalExperienceOrigin.LIVE) &&
                    origins.contains(FractalExperienceOrigin.LEGACY_BACKFILL) &&
                    tasks.size >= 2 &&
                    group.all {
                        it.resolution == FractalCausalResolution.RECOVERED
                    }
            }

        return FractalCausalStats(
            links = links.size,
            recovered = links.count {
                it.resolution == FractalCausalResolution.RECOVERED
            },
            unresolved = links.count {
                it.resolution == FractalCausalResolution.FAILED_AGAIN
            },
            liveLinks = links.count {
                it.origin == FractalExperienceOrigin.LIVE
            },
            legacyLinks = links.count {
                it.origin == FractalExperienceOrigin.LEGACY_BACKFILL
            },
            revalidatedPatterns = revalidated,
            causeUnknown = links.count {
                VerifiedCauseLadderPolicy.baseStage(it) ==
                    CauseLadderStage.UNKNOWN
            },
            causeStructured = links.count {
                VerifiedCauseLadderPolicy.baseStage(it) ==
                    CauseLadderStage.STRUCTURED
            }
        )
    }

    fun relevant(
        records: List<FractalExampleRecord>,
        query: String,
        scopeHash: String?,
        limit: Int = 2
    ): List<FractalCausalLink> {
        val queryTokens = tokenize(query)
        return links(records)
            .asSequence()
            .filter {
                scopeHash == null || it.scopeHash == scopeHash
            }
            .map { link ->
                val searchable = tokenize(
                    buildString {
                        append(link.failedTool)
                        append(' ')
                        append(link.recoveryTools.joinToString(" "))
                        append(' ')
                        append(link.targetHints.joinToString(" "))
                        append(' ')
                        append(link.causeFailureClass.orEmpty())
                        append(' ')
                        append(link.causeErrorCode.orEmpty())
                        append(' ')
                        append(link.causeDependency.orEmpty())
                    }
                )
                val overlap = searchable.count { it in queryTokens }
                link to overlap
            }
            .filter { (_, overlap) ->
                queryTokens.isEmpty() || overlap > 0
            }
            .sortedWith(
                compareByDescending<Pair<FractalCausalLink, Int>> {
                    it.second * 100 +
                        if (
                            it.first.origin ==
                            FractalExperienceOrigin.LIVE
                        ) 10 else 0
                }.thenByDescending { it.first.updatedAt }
            )
            .take(limit.coerceIn(1, 16))
            .map { it.first }
            .toList()
    }

    fun formatForPrompt(
        link: FractalCausalLink
    ): String =
        buildString {
            append(
                if (
                    link.resolution ==
                    FractalCausalResolution.RECOVERED
                ) {
                    "CAUSAL VERIFIED RECOVERY"
                } else {
                    "CAUSAL UNRESOLVED FAILURE"
                }
            )
            append(" · BAD_PATH=")
            append(link.failedTool)
            append("[failed]")
            if (link.targetHints.isNotEmpty()) {
                append(" targets=")
                append(link.targetHints.take(3).joinToString(","))
            }
            append(" · WHY_FAILED=")
            append(link.causeKnowledge.name)
            link.causeFailureClass?.let {
                append(" class=")
                append(it)
            }
            link.causeErrorCode?.let {
                append(" code=")
                append(it)
            }
            link.causeRetryable?.let {
                append(" retryable=")
                append(it)
            }
            link.causeDependency?.let {
                append(" dependency=")
                append(it)
            }
            append(" · RECOVERY=")
            append(
                if (link.recoveryTools.isEmpty()) {
                    "(none observed)"
                } else {
                    link.recoveryTools
                        .zip(link.recoveryOutcomes)
                        .joinToString(" -> ") { pair ->
                            pair.first + "[" +
                                (if (pair.second) "ok" else "failed") +
                                "]"
                        }
                }
            )
            append(" · RESULT=")
            append(link.resolution.name)
            append(" · origin=")
            append(link.origin.name)
            append(" · evidence=")
            append(link.evidenceIds.size)
            append(" · advisory only; revalidate current state")
        }
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(900)

    private fun toLink(
        record: FractalExampleRecord
    ): FractalCausalLink? {
        if (
            record.kind != CoordinatorExampleKind.RECOVERY &&
            record.kind != CoordinatorExampleKind.FAILED_RECOVERY
        ) {
            return null
        }
        if (
            record.tools.isEmpty() ||
            record.tools.size != record.outcomes.size ||
            record.evidenceIds.isEmpty()
        ) {
            return null
        }

        val failedIndex = record.outcomes.indexOfFirst { !it }
        if (failedIndex !in record.tools.indices) return null

        val recovered =
            record.kind == CoordinatorExampleKind.RECOVERY &&
                record.outcomes.lastOrNull() == true
        val resolution =
            if (recovered) {
                FractalCausalResolution.RECOVERED
            } else {
                FractalCausalResolution.FAILED_AGAIN
            }
        val recoveryTools = record.tools.drop(failedIndex + 1)
        val recoveryOutcomes = record.outcomes.drop(failedIndex + 1)
        val causeFailureClass = record.failureClasses.getOrNull(failedIndex)
            ?.takeIf(String::isNotBlank)
        val causeErrorCode = record.errorCodes.getOrNull(failedIndex)
            ?.takeIf(String::isNotBlank)
        val causeRetryable = record.retryableFlags.getOrNull(failedIndex)
        val causeDependency = record.dependencies.getOrNull(failedIndex)
            ?.takeIf(String::isNotBlank)
        val causeKnowledge =
            if (
                causeFailureClass != null ||
                causeErrorCode != null ||
                causeRetryable != null ||
                causeDependency != null
            ) {
                FractalCausalCauseKnowledge.STRUCTURED_TOOL_FAILURE
            } else {
                FractalCausalCauseKnowledge.UNKNOWN_NOT_CAPTURED
            }

        return FractalCausalLink(
            id = "fx-causal-" +
                hash(
                    record.id + "|" +
                        failedIndex + "|" +
                        resolution.name
                ).take(20),
            sourceRecordId = record.id,
            sourceTaskHash = record.sourceTaskHash,
            scopeHash = record.scopeHash,
            failedStepIndex = failedIndex,
            failedTool = record.tools[failedIndex],
            targetHints = record.targets
                .filter(String::isNotBlank)
                .distinct()
                .take(8),
            recoveryTools = recoveryTools.take(15),
            recoveryOutcomes = recoveryOutcomes.take(15),
            resolution = resolution,
            causeKnowledge = causeKnowledge,
            causeFailureClass = causeFailureClass,
            causeErrorCode = causeErrorCode,
            causeRetryable = causeRetryable,
            causeDependency = causeDependency,
            evidenceIds = record.evidenceIds
                .filter(String::isNotBlank)
                .distinct()
                .take(64),
            contributorModelIds = record.contributorModelIds
                .filter(String::isNotBlank)
                .distinct()
                .take(16),
            origin = record.origin,
            updatedAt = record.updatedAt
        )
    }

    private fun structuralKey(
        link: FractalCausalLink
    ): String =
        buildString {
            append(link.scopeHash.orEmpty())
            append('|')
            append(link.failedTool)
            append('|')
            append(link.causeKnowledge.name)
            append('|')
            append(link.causeFailureClass.orEmpty())
            append('|')
            append(link.causeErrorCode.orEmpty())
            append('|')
            append(link.causeDependency.orEmpty())
            append('|')
            append(link.recoveryTools.joinToString(">"))
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

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
