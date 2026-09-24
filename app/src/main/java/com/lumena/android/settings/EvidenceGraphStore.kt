package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.EvidenceCandidateDirective
import com.lumena.android.agent.core.EvidenceClaimNode
import com.lumena.android.agent.core.EvidenceApplicationUpdate
import com.lumena.android.agent.core.EvidenceProjectApplicationPolicy
import com.lumena.android.agent.core.EvidenceClaimCandidateProvenance
import com.lumena.android.agent.core.EvidenceClaimProposal
import com.lumena.android.agent.core.EvidenceGraphClaimPolicy
import com.lumena.android.agent.core.EvidenceGraphReducer
import com.lumena.android.agent.core.EvidenceGraphState
import com.lumena.android.agent.core.EvidenceObservation
import com.lumena.android.agent.core.EvidenceRelation
import com.lumena.android.agent.core.EvidenceSourceKind
import com.lumena.android.agent.core.EvidenceVerificationState
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.net.URI

data class EvidenceGraphStats(
    val claims: Int,
    val sources: Int,
    val discovered: Int,
    val retrieved: Int,
    val corroborated: Int,
    val contested: Int
)

data class EvidenceModelProposalBatchResult(
    val accepted: Int,
    val rejected: Int,
    val candidateIds: List<String>,
    val rejectionReasons: List<String>
)

object EvidenceGraphCodec {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(EvidenceGraphState::class.java)

    fun encode(state: EvidenceGraphState): String =
        adapter.toJson(state)

    fun decode(raw: String): EvidenceGraphState =
        requireNotNull(adapter.fromJson(raw)) {
            "Evidence graph is empty or invalid JSON"
        }
}

/**
 * Deterministic projection from verified web TOOL_RESULT into typed source
 * evidence.
 *
 * Step 2 deliberately does not invent semantic cross-source claims. Each
 * source URL gets a stable source:* claim key. Search snippets can discover a
 * source and a later successful read of the same URL can upgrade it to
 * RETRIEVED. Semantic claim linking is a separate later step.
 */
object EvidenceGraphProjector {
    private val webTools = setOf(
        "web.search",
        "web.read",
        "http.get",
        "http.json"
    )

    private val anyAdapter = Moshi.Builder()
        .build()
        .adapter(Any::class.java)

    fun fromToolResult(
        task: TaskState,
        request: ToolRequest,
        result: ToolResult,
        evidenceId: String?,
        now: Long
    ): List<EvidenceObservation> {
        if (
            !result.ok ||
            result.outcomeUnknown ||
            evidenceId.isNullOrBlank() ||
            now <= 0
        ) {
            return emptyList()
        }

        val tool = ToolRegistry.canonicalize(request.tool)
        if (tool !in webTools) return emptyList()

        val spec = ToolRegistry.get(tool) ?: return emptyList()
        if (spec.risk.name != "READ_ONLY") return emptyList()

        return when (tool) {
            "web.search" -> projectSearch(
                task = task,
                stdout = result.stdout,
                evidenceId = evidenceId,
                now = now
            )

            "web.read" -> projectRead(
                task = task,
                requestUrl = request.args["url"].orEmpty(),
                stdout = result.stdout,
                evidenceId = evidenceId,
                now = now,
                sourceKind = EvidenceSourceKind.WEB_PAGE,
                method = tool
            )

            "http.get" -> projectRead(
                task = task,
                requestUrl = request.args["url"].orEmpty(),
                stdout = result.stdout,
                evidenceId = evidenceId,
                now = now,
                sourceKind = EvidenceSourceKind.WEB_PAGE,
                method = tool
            )

            "http.json" -> projectRead(
                task = task,
                requestUrl = request.args["url"].orEmpty(),
                stdout = result.stdout,
                evidenceId = evidenceId,
                now = now,
                sourceKind = EvidenceSourceKind.PUBLIC_API,
                method = tool
            )

            else -> emptyList()
        }
    }

    private fun projectSearch(
        task: TaskState,
        stdout: String,
        evidenceId: String,
        now: Long
    ): List<EvidenceObservation> {
        val root = parseObject(stdout) ?: return emptyList()
        val results = root["results"] as? List<*> ?: return emptyList()

        return results
            .asSequence()
            .mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val url = map["url"]
                    ?.toString()
                    ?.let(::normalizeUrl)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val title = map["title"]
                    ?.toString()
                    ?.clean(500)
                    .orEmpty()
                val snippet = map["snippet"]
                    ?.toString()
                    ?.clean(1_200)
                    .orEmpty()

                val statement = listOf(title, snippet)
                    .filter(String::isNotBlank)
                    .joinToString(" — ")
                    .ifBlank { "Source discovered for: ${task.goal.clean(900)}" }
                    .take(1_600)

                EvidenceObservation(
                    claimKey = sourceClaimKey(url),
                    statement = statement,
                    relation = EvidenceRelation.SUPPORTS,
                    sourceUri = url,
                    sourceKind = EvidenceSourceKind.SEARCH_SNIPPET,
                    retrievalMethod = "web.search",
                    evidenceId = evidenceId,
                    observedAt = now,
                    projectId = task.projectId,
                    projectRelevance = relevance(
                        task.goal,
                        "$title $snippet $url"
                    ),
                    verifiedToolResult = true,
                    outcomeUnknown = false
                )
            }
            .distinctBy { it.sourceUri }
            .take(12)
            .toList()
    }

    private fun projectRead(
        task: TaskState,
        requestUrl: String,
        stdout: String,
        evidenceId: String,
        now: Long,
        sourceKind: EvidenceSourceKind,
        method: String
    ): List<EvidenceObservation> {
        val root = parseObject(stdout)
        val url = (
            root?.get("url")?.toString()
                ?: requestUrl
            )
            .let(::normalizeUrl)
            .takeIf { it.isNotBlank() }
            ?: return emptyList()

        val title = root
            ?.get("title")
            ?.toString()
            ?.clean(500)
            .orEmpty()

        val text = root
            ?.get("text")
            ?.toString()
            ?.clean(1_200)
            ?: stdout.clean(1_200)

        val statement = listOf(title, text)
            .filter(String::isNotBlank)
            .joinToString(" — ")
            .ifBlank { "Retrieved source for: ${task.goal.clean(900)}" }
            .take(1_600)

        return listOf(
            EvidenceObservation(
                claimKey = sourceClaimKey(url),
                statement = statement,
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = url,
                sourceKind = sourceKind,
                retrievalMethod = method,
                evidenceId = evidenceId,
                observedAt = now,
                projectId = task.projectId,
                projectRelevance = relevance(
                    task.goal,
                    "$title $text $url"
                ),
                verifiedToolResult = true,
                outcomeUnknown = false
            )
        )
    }

    internal fun sourceClaimKey(url: String): String =
        "source:${normalizeUrl(url)}"

    internal fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""

        return runCatching {
            val uri = URI(trimmed)
            if (
                uri.scheme?.lowercase() !in setOf("https", "http") ||
                uri.host.isNullOrBlank()
            ) {
                return@runCatching ""
            }

            val scheme = uri.scheme.lowercase()
            val host = uri.host
                .lowercase()
                .removePrefix("www.")
            val port = when {
                uri.port < 0 -> ""
                scheme == "https" && uri.port == 443 -> ""
                scheme == "http" && uri.port == 80 -> ""
                else -> ":${uri.port}"
            }
            val path = uri.rawPath
                ?.takeIf { it.isNotBlank() }
                ?: "/"
            val normalizedPath =
                if (path.length > 1) path.trimEnd('/') else path
            val query = uri.rawQuery
                ?.takeIf { it.isNotBlank() }
                ?.let { "?$it" }
                .orEmpty()

            "$scheme://$host$port$normalizedPath$query"
        }.getOrDefault("")
    }

    internal fun relevance(
        goal: String,
        searchable: String
    ): Double {
        val wanted = meaningfulTokens(goal)
        if (wanted.isEmpty()) return 0.0

        val found = meaningfulTokens(searchable)
        if (found.isEmpty()) return 0.0

        val overlap = wanted.count { it in found }
        return (
            overlap.toDouble() /
                wanted.size.toDouble()
            )
            .coerceIn(0.0, 1.0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseObject(
        raw: String
    ): Map<String, Any?>? =
        runCatching {
            anyAdapter.fromJson(raw) as? Map<String, Any?>
        }.getOrNull()

    private fun meaningfulTokens(text: String): Set<String> {
        val stop = setOf(
            "the", "and", "for", "with", "from", "this", "that",
            "про", "для", "та", "або", "що", "цей", "цю", "знайди",
            "найди", "пошукай", "в", "на", "у", "і", "й",
            "oraz", "dla", "ten", "ta", "to", "znajdź"
        )

        return text
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}._+-]+"))
            .map(String::trim)
            .filter {
                it.length >= 3 &&
                    it !in stop
            }
            .take(32)
            .toSet()
    }

    private fun String.clean(maxChars: Int): String =
        replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(maxChars)
}

/**
 * App-private atomic source-evidence store.
 *
 * Corrupt persistence fails closed instead of silently replacing history.
 * Raw TOOL_RESULT bodies are not stored; only bounded typed graph nodes are
 * persisted.
 */
object EvidenceGraphStore {
    private const val FILE_NAME =
        "lumena_evidence_graph.json"
    private const val MAX_CLAIMS = 512
    private const val MAX_SOURCES = 512
    private const val MAX_CANDIDATES = 256
    private const val MAX_APPLICATIONS = 256

    private val lock = Any()

    fun load(context: Context): EvidenceGraphState =
        synchronized(lock) {
            val file = atomicFile(context)
            if (
                !file.baseFile.exists() &&
                !File(file.baseFile.path + ".bak").exists()
            ) {
                return@synchronized EvidenceGraphState()
            }

            val parsed = try {
                EvidenceGraphCodec.decode(
                    file.openRead()
                        .bufferedReader()
                        .use { it.readText() }
                )
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Evidence graph is unreadable; refusing to overwrite evidence history.",
                    failure
                )
            }

            require(parsed.schemaVersion == 1) {
                "Unsupported evidence graph schema ${parsed.schemaVersion}"
            }
            parsed
        }

    fun record(
        context: Context,
        task: TaskState,
        request: ToolRequest,
        result: ToolResult,
        evidenceId: String?,
        now: Long = System.currentTimeMillis()
    ): List<EvidenceObservation> =
        synchronized(lock) {
            val observations = EvidenceGraphProjector.fromToolResult(
                task = task,
                request = request,
                result = result,
                evidenceId = evidenceId,
                now = now
            )
            if (observations.isEmpty()) {
                return@synchronized emptyList()
            }

            var state = load(context)
            var changed = false

            observations.forEach { observation ->
                val update = EvidenceGraphReducer.record(
                    state,
                    observation
                )
                if (update.accepted && update.state != state) {
                    state = update.state
                    changed = true
                }
            }

            if (changed) {
                save(context, trim(state))
            }

            observations
        }

    fun proposeModelCandidates(
        context: Context,
        task: TaskState,
        directives: List<EvidenceCandidateDirective>,
        now: Long = System.currentTimeMillis()
    ): EvidenceModelProposalBatchResult = synchronized(lock) {
        if (directives.isEmpty()) {
            return@synchronized EvidenceModelProposalBatchResult(
                accepted = 0,
                rejected = 0,
                candidateIds = emptyList(),
                rejectionReasons = emptyList()
            )
        }

        var state = load(context)
        var changed = false
        val ids = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        directives.take(4).forEach { directive ->
            val sourceIds = directive.sourceIds
                .distinct()
                .take(8)

            val relevance = state.claims
                .filter { claim ->
                    val linked = (
                        claim.supportSourceIds +
                            claim.contradictionSourceIds +
                            claim.mentionSourceIds
                        ).toSet()
                    sourceIds.any { it in linked }
                }
                .maxOfOrNull { it.projectRelevance }
                ?: 0.0

            val update = EvidenceGraphClaimPolicy.propose(
                state = state,
                proposal = EvidenceClaimProposal(
                    claimKey = directive.claimKey,
                    statement = directive.statement,
                    sourceIds = sourceIds,
                    provenance =
                        EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                    proposedAt = now,
                    projectId = task.projectId,
                    projectRelevance = relevance
                )
            )

            if (update.accepted) {
                state = update.state
                update.candidateId?.let(ids::add)
                changed = true
            } else {
                reasons += (
                    update.reason
                        ?: "EVIDENCE_CANDIDATE_REJECTED"
                    )
            }
        }

        if (changed) {
            save(context, trim(state))
        }

        EvidenceModelProposalBatchResult(
            accepted = ids.distinct().size,
            rejected = reasons.size,
            candidateIds = ids.distinct(),
            rejectionReasons = reasons
                .distinct()
                .take(8)
        )
    }

    fun proposeModelClaim(
        context: Context,
        claimKey: String,
        statement: String,
        sourceIds: List<String>,
        projectId: String?,
        projectRelevance: Double,
        now: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val current = load(context)
        val update = EvidenceGraphClaimPolicy.propose(
            state = current,
            proposal = EvidenceClaimProposal(
                claimKey = claimKey,
                statement = statement,
                sourceIds = sourceIds,
                provenance =
                    EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                proposedAt = now,
                projectId = projectId,
                projectRelevance = projectRelevance
            )
        )
        if (update.accepted && update.state != current) {
            save(context, trim(update.state))
        }
        update
    }

    fun bindClaimToProject(
        context: Context,
        claimKey: String,
        projectId: String,
        target: String,
        now: Long = System.currentTimeMillis()
    ): EvidenceApplicationUpdate = synchronized(lock) {
        val current = load(context)
        val update = EvidenceProjectApplicationPolicy.bind(
            state = current,
            claimKey = claimKey,
            projectId = projectId,
            target = target,
            now = now
        )
        if (update.accepted && update.state != current) {
            save(context, trim(update.state))
        }
        update
    }

    fun recordProjectOutcome(
        context: Context,
        bindingId: String,
        taskProjectId: String,
        request: ToolRequest,
        result: ToolResult,
        evidenceId: String?,
        now: Long = System.currentTimeMillis()
    ): EvidenceApplicationUpdate = synchronized(lock) {
        val current = load(context)
        val update = EvidenceProjectApplicationPolicy.observeToolResult(
            state = current,
            bindingId = bindingId,
            taskProjectId = taskProjectId,
            request = request,
            result = result,
            evidenceId = evidenceId,
            now = now
        )
        if (update.accepted && update.state != current) {
            save(context, trim(update.state))
        }
        update
    }

    fun relevant(
        context: Context,
        query: String,
        limit: Int = 6,
        now: Long = System.currentTimeMillis()
    ): List<String> =
        synchronized(lock) {
            val state = load(context)
            EvidenceGraphReducer.relevantClaims(
                state = state,
                query = query,
                now = now,
                limit = limit
            ).map { claim ->
                formatClaim(
                    state = state,
                    claim = claim,
                    now = now
                )
            }
        }

    fun stats(context: Context): EvidenceGraphStats =
        synchronized(lock) {
            val state = load(context)
            EvidenceGraphStats(
                claims = state.claims.size,
                sources = state.sources.size,
                discovered = state.claims.count {
                    it.verificationState ==
                        EvidenceVerificationState.DISCOVERED
                },
                retrieved = state.claims.count {
                    it.verificationState ==
                        EvidenceVerificationState.RETRIEVED
                },
                corroborated = state.claims.count {
                    it.verificationState ==
                        EvidenceVerificationState.CORROBORATED
                },
                contested = state.claims.count {
                    it.verificationState ==
                        EvidenceVerificationState.CONTESTED
                }
            )
        }

    fun clear(context: Context) =
        synchronized(lock) {
            val file = atomicFile(context).baseFile
            if (file.exists()) file.delete()
            File(file.path + ".bak")
                .takeIf(File::exists)
                ?.delete()
        }

    internal fun trim(
        state: EvidenceGraphState
    ): EvidenceGraphState {
        val orderedClaims = state.claims
            .sortedByDescending { it.lastObservedAt }

        val retainedClaims = mutableListOf<EvidenceClaimNode>()
        val retainedSourceIds = linkedSetOf<String>()

        for (claim in orderedClaims) {
            if (retainedClaims.size >= MAX_CLAIMS) break

            val claimSourceIds = (
                claim.supportSourceIds +
                    claim.contradictionSourceIds +
                    claim.mentionSourceIds
                )
                .distinct()

            // Never partially retain a claim's source set. Verification state
            // (for example CORROBORATED/CONTESTED) was derived from these exact
            // links, so dropping only some links would make the stored state
            // semantically inconsistent.
            val nextUnionSize =
                (retainedSourceIds + claimSourceIds).size
            if (
                claimSourceIds.isNotEmpty() &&
                nextUnionSize > MAX_SOURCES
            ) {
                continue
            }

            retainedClaims += claim
            retainedSourceIds += claimSourceIds
        }

        val retainedSources = state.sources
            .filter { it.id in retainedSourceIds }
            .sortedByDescending { it.lastObservedAt }

        val retainedCandidates = state.candidates
            .sortedByDescending { it.proposedAt }
            .filter { candidate ->
                candidate.sourceIds.all {
                    it in retainedSourceIds
                }
            }
            .take(MAX_CANDIDATES)

        val retainedClaimKeys = retainedClaims
            .map { it.claimKey }
            .toSet()

        val retainedApplications = state.applications
            .sortedByDescending { it.updatedAt }
            .filter {
                it.claimKey in retainedClaimKeys
            }
            .take(MAX_APPLICATIONS)

        return state.copy(
            claims = retainedClaims,
            sources = retainedSources,
            candidates = retainedCandidates,
            applications = retainedApplications
        )
    }

    private fun formatClaim(
        state: EvidenceGraphState,
        claim: EvidenceClaimNode,
        now: Long
    ): String {
        val effective =
            EvidenceGraphReducer.effectiveVerificationState(
                claim,
                now
            )

        val sourceIds = (
            claim.supportSourceIds +
                claim.contradictionSourceIds +
                claim.mentionSourceIds
            ).distinct()

        val source = sourceIds
            .asSequence()
            .mapNotNull { id ->
                state.sources.firstOrNull { it.id == id }
            }
            .sortedByDescending { it.lastObservedAt }
            .firstOrNull()

        val sourceText = source?.let {
            "${it.uri} sourceId=${it.id} via=${it.retrievalMethod}"
        } ?: "(source unavailable)"

        return buildString {
            append("EVIDENCE [")
            append(effective.name)
            append("] ")
            append(claim.statement.take(500))
            append(" · source=")
            append(sourceText.take(700))
            append(" · projectRelevance=")
            append(
                (
                    claim.projectRelevance * 100.0
                    ).toInt()
            )
            append("%")
            append(" · outcome=")
            append(claim.outcome.name)
            append(
                " · verified tool evidence only; not permission or completion proof"
            )
        }.take(1_400)
    }

    private fun save(
        context: Context,
        state: EvidenceGraphState
    ) {
        val file = atomicFile(context)
        val out = file.startWrite()
        try {
            out.write(
                EvidenceGraphCodec.encode(state)
                    .toByteArray(Charsets.UTF_8)
            )
            file.finishWrite(out)
        } catch (failure: Exception) {
            file.failWrite(out)
            throw failure
        }
    }

    private fun atomicFile(context: Context) =
        AtomicFile(
            File(
                context.applicationContext.filesDir,
                FILE_NAME
            )
        )
}
