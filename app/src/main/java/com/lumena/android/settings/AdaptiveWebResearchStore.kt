package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.AdaptiveWebStrategyFactory
import com.lumena.android.agent.core.JevLikeWebCalibrationRanker
import com.lumena.android.agent.core.ResearchThreadResolver
import com.lumena.android.agent.core.ResearchThreadState
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.WebQueryClass
import com.lumena.android.agent.core.WebStrategyKind
import com.lumena.android.agent.core.WebStrategyObservation
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.lumena.android.ollama.OllamaMessage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

data class AdaptiveWebResearchState(
    val version: Int = 1,
    val observations: List<WebStrategyObservation> = emptyList()
)

/**
 * Converts verified TOOL_RESULT outcomes into bounded calibration observations.
 *
 * Raw page/search bodies are never persisted. Local bridge/auth failures are
 * excluded from route calibration so a token/config problem cannot damage a
 * public domain's reputation.
 */
object AdaptiveWebObservationFactory {
    private val webTools = setOf(
        "web.search",
        "web.read",
        "http.get",
        "http.json"
    )

    private val moshi = Moshi.Builder().build()
    private val anyAdapter = moshi.adapter(Any::class.java)

    fun from(
        task: TaskState,
        request: ToolRequest,
        result: ToolResult,
        elapsedMs: Long,
        evidenceId: String?,
        now: Long
    ): WebStrategyObservation? {
        if (result.outcomeUnknown) return null

        val tool = ToolRegistry.canonicalize(request.tool)
        if (tool !in webTools) return null

        val spec = ToolRegistry.get(tool) ?: return null
        if (spec.risk.name != "READ_ONLY") return null

        val query = request.args["query"].orEmpty()
        val url = request.args["url"].orEmpty()
        val queryClass = AdaptiveWebStrategyFactory.classifyQuery(query)

        val kind = when (tool) {
            "web.search" ->
                if (queryClass == WebQueryClass.ENGLISH) {
                    WebStrategyKind.SEARCH_ENGLISH_TECHNICAL
                } else {
                    WebStrategyKind.SEARCH_CURRENT_LANGUAGE
                }
            "web.read" -> WebStrategyKind.READ_DIRECT
            "http.get" -> WebStrategyKind.HTTP_GET
            "http.json" -> WebStrategyKind.HTTP_JSON
            else -> return null
        }

        val host = if (url.isBlank()) null
        else AdaptiveWebStrategyFactory.normalizedHost(url)

        val failureClass = result.failureClass
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(80)

        val dependency = result.dependency
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(120)

        val localFailure =
            failureClass in setOf(
                "AUTH_OR_CONFIG",
                "UNKNOWN_EFFECT",
                "TRANSIENT_TRANSPORT"
            ) ||
                dependency == "termux_bridge" ||
                result.errorCode in setOf(
                    "BRIDGE_START_FAILED",
                    "BRIDGE_TRANSPORT"
                )

        val relevance = if (!result.ok) {
            null
        } else {
            when (tool) {
                "web.search" ->
                    relevanceForSearch(
                        query = query,
                        stdout = result.stdout
                    )
                "web.read", "http.get", "http.json" ->
                    relevanceForRead(
                        goal = task.goal,
                        stdout = result.stdout
                    )
                else -> null
            }
        }

        val identity = buildString {
            append(evidenceId.orEmpty())
            append('|')
            append(request.requestId.orEmpty())
            append('|')
            append(tool)
            append('|')
            append(host.orEmpty())
            append('|')
            append(queryClass.name)
            append('|')
            append(now)
        }

        return WebStrategyObservation(
            id = hash(identity).take(24),
            kind = kind,
            tool = tool,
            host = host,
            queryClass = queryClass,
            ok = result.ok,
            relevance = relevance,
            elapsedMs = elapsedMs.coerceAtLeast(0),
            failureClass = failureClass,
            dependency = dependency,
            calibrationEligible = !localFailure,
            at = now
        )
    }

    internal fun relevanceForSearch(
        query: String,
        stdout: String
    ): Double? {
        val root = parseObject(stdout) ?: return null
        val results = root["results"] as? List<*> ?: return null
        if (results.isEmpty()) return 0.0

        val searchable = buildString {
            results.take(8).forEach { item ->
                val map = item as? Map<*, *> ?: return@forEach
                listOf("title", "snippet", "url").forEach { key ->
                    map[key]?.toString()?.let {
                        append(' ')
                        append(it)
                    }
                }
            }
        }

        return tokenRelevance(query, searchable)
    }

    internal fun relevanceForRead(
        goal: String,
        stdout: String
    ): Double? {
        val root = parseObject(stdout)
        val searchable = if (root != null) {
            buildString {
                listOf("title", "text", "url").forEach { key ->
                    root[key]?.toString()?.let {
                        append(' ')
                        append(it.take(12_000))
                    }
                }
            }
        } else {
            stdout.take(12_000)
        }

        if (searchable.isBlank()) return null
        return tokenRelevance(goal, searchable)
    }

    private fun tokenRelevance(
        query: String,
        searchable: String
    ): Double? {
        val wanted = meaningfulTokens(query)
        if (wanted.isEmpty()) return null

        val found = meaningfulTokens(searchable)
        if (found.isEmpty()) return 0.0

        val overlap = wanted.count { it in found }
        return (overlap.toDouble() / wanted.size.toDouble())
            .coerceIn(0.0, 1.0)
    }

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
            .filter { it.length >= 3 && it !in stop }
            .toSet()
            .take(24)
            .toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseObject(raw: String): Map<String, Any?>? =
        runCatching {
            anyAdapter.fromJson(raw) as? Map<String, Any?>
        }.getOrNull()

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * App-private, atomic calibration store.
 *
 * This is advisory experience only. It cannot execute a tool or alter
 * ToolRegistry/ToolGate/confirmation.
 */
object AdaptiveWebResearchStore {
    private const val FILE_NAME =
        "lumena_adaptive_web_research.json"
    private const val MAX_OBSERVATIONS = 2_048

    private val lock = Any()
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(AdaptiveWebResearchState::class.java)

    fun load(context: Context): AdaptiveWebResearchState =
        synchronized(lock) {
            val file = atomicFile(context)
            if (
                !file.baseFile.exists() &&
                !File(file.baseFile.path + ".bak").exists()
            ) {
                return@synchronized AdaptiveWebResearchState()
            }

            val parsed = try {
                adapter.fromJson(
                    file.openRead().bufferedReader().use {
                        it.readText()
                    }
                )
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Adaptive web calibration store is unreadable; refusing to overwrite learned history.",
                    failure
                )
            }

            requireNotNull(parsed) {
                "Adaptive web calibration store is empty/corrupt; refusing to overwrite learned history."
            }
        }

    fun record(
        context: Context,
        task: TaskState,
        request: ToolRequest,
        result: ToolResult,
        elapsedMs: Long,
        evidenceId: String?,
        now: Long = System.currentTimeMillis()
    ): WebStrategyObservation? = synchronized(lock) {
        val observation = AdaptiveWebObservationFactory.from(
            task = task,
            request = request,
            result = result,
            elapsedMs = elapsedMs,
            evidenceId = evidenceId,
            now = now
        ) ?: return@synchronized null

        val state = load(context)
        if (state.observations.any { it.id == observation.id }) {
            return@synchronized observation
        }

        val next = state.copy(
            version = 1,
            observations =
                (state.observations + observation)
                    .takeLast(MAX_OBSERVATIONS)
        )
        save(context, next)
        observation
    }

    fun advice(
        context: Context,
        researchGoal: String,
        history: List<OllamaMessage>,
        limit: Int = 3,
        now: Long = System.currentTimeMillis()
    ): List<String> = synchronized(lock) {
        val sourceUrls = ResearchThreadResolver.observeHistory(
            history = history,
            thread = ResearchThreadState(
                rootGoal = researchGoal
            )
        )?.discoveredUrls.orEmpty()

        val candidates = AdaptiveWebStrategyFactory.candidates(
            goal = researchGoal,
            sourceUrls = sourceUrls
        )
        if (candidates.isEmpty()) return@synchronized emptyList()

        val ranking = JevLikeWebCalibrationRanker.rank(
            candidates = candidates,
            observations = load(context).observations,
            now = now
        )

        ranking.scores
            .take(limit.coerceIn(1, 6))
            .map { score ->
                buildString {
                    append(
                        "JEV-LIKE WEB CALIBRATION " +
                            "(advisory only; not permission): "
                    )
                    append("strategy=")
                    append(score.candidate.kind.name)
                    score.candidate.host?.let {
                        append("; host=")
                        append(it)
                    }
                    append("; tool=")
                    append(score.candidate.tool)
                    append("; access≈")
                    append(percent(score.accessProbability))
                    append("; relevance≈")
                    append(percent(score.relevanceProbability))
                    append("; selectionUtility=")
                    append(percent(score.utility))
                    append("; calibration=")
                    append(percent(score.calibrationConfidence))
                    append("; evidence=")
                    append(score.evidenceCount)
                    append("; calibrated=")
                    append(score.calibrated)
                    append(". ")
                    append(score.candidate.guidance)
                    append(
                        " Planner/model still chooses through normal protocol; " +
                            "ToolRegistry and ToolGate remain authoritative."
                    )
                }.take(1_200)
            }
    }

    fun clear(context: Context) = synchronized(lock) {
        val file = atomicFile(context).baseFile
        if (file.exists()) file.delete()
        File(file.path + ".bak")
            .takeIf(File::exists)
            ?.delete()
    }

    private fun save(
        context: Context,
        state: AdaptiveWebResearchState
    ) {
        val file = atomicFile(context)
        val out = file.startWrite()
        try {
            out.write(
                adapter.toJson(state)
                    .toByteArray(Charsets.UTF_8)
            )
            file.finishWrite(out)
        } catch (failure: Exception) {
            file.failWrite(out)
            throw failure
        }
    }

    private fun percent(value: Double): String =
        ((value.coerceIn(0.0, 1.0) * 100.0)
            .roundToInt())
            .toString() + "%"

    private fun atomicFile(context: Context) =
        AtomicFile(
            File(
                context.applicationContext.filesDir,
                FILE_NAME
            )
        )
}
