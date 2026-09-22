package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

data class CoordinatorEpisodeEvent(
    val id: String,
    val sessionId: String,
    val taskId: String?,
    val tool: String,
    val target: String,
    val ok: Boolean,
    val experienceId: String?,
    val at: Long,
    val surprise: Double
)

data class CoordinatorEpisodeState(
    val version: Int = 2,
    val events: List<CoordinatorEpisodeEvent> = emptyList(),
    val learnedExamples: List<CoordinatorExecutionExample> = emptyList()
)

enum class CoordinatorExampleKind {
    RECOVERY,
    VERIFIED_SEQUENCE
}

data class CoordinatorExecutionExample(
    val id: String,
    val kind: CoordinatorExampleKind,
    val sourceSessionHash: String,
    val tools: List<String>,
    val targets: List<String>,
    val evidenceIds: List<String>,
    val updatedAt: Long,
    val surprise: Double,
    val text: String
)

/**
 * Pure, bounded event-sourced policy.
 *
 * These events describe verified tool outcomes only. They are not proof that the
 * whole user goal succeeded, and they never grant permissions.
 */
object CoordinatorExperiencePolicy {
    const val MAX_EVENTS = 2_048
    const val MAX_LEARNED_EXAMPLES = 512
    const val MAX_SEQUENCE_STEPS = 6

    fun record(
        state: CoordinatorEpisodeState,
        event: CoordinatorEpisodeEvent
    ): CoordinatorEpisodeState {
        if (state.events.any { it.id == event.id }) return state
        require(event.sessionId.isNotBlank())
        require(event.tool.isNotBlank())
        require(ToolRegistry.get(event.tool) != null) {
            "Unknown tool in coordinator episode: ${event.tool}"
        }
        require(event.at > 0)
        require(event.surprise in 0.0..1.0)

        val allEvents = state.events + event
        val learned = (
            state.learnedExamples +
                deriveExamples(allEvents)
            )
            .distinctBy { it.id }
            .sortedWith(
                compareBy<CoordinatorExecutionExample> { it.updatedAt }
                    .thenBy { it.id }
            )
            .takeLast(MAX_LEARNED_EXAMPLES)

        return state.copy(
            version = 2,
            events = allEvents.takeLast(MAX_EVENTS),
            learnedExamples = learned
        )
    }

    /**
     * Heuristic novelty signal inspired by test-time memory systems.
     * It is not a calibrated probability.
     */
    fun surprise(
        state: CoordinatorEpisodeState,
        sessionId: String,
        tool: String,
        target: String,
        ok: Boolean
    ): Double {
        val canonical = ToolRegistry.canonicalize(tool)
        val same = state.events.filter {
            it.tool == canonical && it.target == target
        }
        val previous = same.lastOrNull()
        if (previous == null) return 0.80
        if (previous.ok != ok) return 1.00

        val sessionSame = same.count { it.sessionId == sessionId }
        return when {
            !ok -> 0.85
            sessionSame <= 1 -> 0.45
            sessionSame <= 3 -> 0.25
            sessionSame <= 7 -> 0.12
            else -> 0.05
        }
    }

    fun examples(
        state: CoordinatorEpisodeState,
        query: String,
        limit: Int = 4,
        sourceSessionHash: String? = null
    ): List<CoordinatorExecutionExample> {
        val queryTokens = tokenize(query)
        val candidates = (
            state.learnedExamples +
                deriveExamples(state.events)
            )
            .distinctBy { it.id }
            .filter { example ->
                sourceSessionHash == null ||
                    example.sourceSessionHash == sourceSessionHash
            }

        if (candidates.isEmpty()) return emptyList()

        return candidates
            .map { example ->
                val searchable = tokenize(
                    example.tools.joinToString(" ") + " " +
                        example.targets.joinToString(" ") + " " +
                        example.text
                )
                val overlap = searchable.count { it in queryTokens }
                Triple(example, overlap, searchable)
            }
            .filter { (_, overlap, _) -> queryTokens.isEmpty() || overlap > 0 }
            .sortedWith(
                compareByDescending<Triple<CoordinatorExecutionExample, Int, Set<String>>> {
                    it.second * 100 + (it.first.surprise * 20).toInt()
                }.thenByDescending { it.first.updatedAt }
            )
            .take(limit.coerceIn(1, 64))
            .map { it.first }
    }

    fun formatForPrompt(example: CoordinatorExecutionExample): String =
        example.text
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(900)

    private fun deriveExamples(
        events: List<CoordinatorEpisodeEvent>
    ): List<CoordinatorExecutionExample> {
        if (events.isEmpty()) return emptyList()
        val out = mutableListOf<CoordinatorExecutionExample>()
        events
            .groupBy { event ->
                event.sessionId to (event.taskId ?: event.sessionId)
            }
            .values
            .forEach { rawSession ->
                val session = rawSession.sortedBy { it.at }
                out += recoveryExamples(session)
                successfulSequence(session)?.let { out += it }
            }
        return out
    }

    private fun recoveryExamples(
        session: List<CoordinatorEpisodeEvent>
    ): List<CoordinatorExecutionExample> {
        val out = mutableListOf<CoordinatorExecutionExample>()
        for (i in session.indices) {
            val failed = session[i]
            if (failed.ok) continue

            val end = (i + MAX_SEQUENCE_STEPS).coerceAtMost(session.lastIndex)
            // A recovery example is only created when the originally failed
            // operation later succeeds for the same tool + target. Intermediate
            // discovery/repair steps are retained, but a random successful tool
            // must never be mistaken for resolution.
            val successIndex = (i + 1..end).firstOrNull { index ->
                val candidate = session[index]
                candidate.ok &&
                    candidate.tool == failed.tool &&
                    candidate.target == failed.target
            } ?: continue
            val segment = session.subList(i, successIndex + 1)

            out += buildExample(
                kind = CoordinatorExampleKind.RECOVERY,
                segment = segment,
                label = "RECOVERY EXAMPLE"
            )
        }
        return out
    }

    private fun successfulSequence(
        session: List<CoordinatorEpisodeEvent>
    ): CoordinatorExecutionExample? {
        val successful = session
            .takeLast(MAX_SEQUENCE_STEPS)
            .takeIf { it.size >= 2 && it.all(CoordinatorEpisodeEvent::ok) }
            ?: return null

        return buildExample(
            kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
            segment = successful,
            label = "VERIFIED EXECUTION SEQUENCE"
        )
    }

    private fun buildExample(
        kind: CoordinatorExampleKind,
        segment: List<CoordinatorEpisodeEvent>,
        label: String
    ): CoordinatorExecutionExample {
        val steps = segment.joinToString(" -> ") { event ->
            val outcome = if (event.ok) "ok" else "failed"
            "${event.tool}[$outcome]${event.target.takeIf(String::isNotBlank)?.let { " target=${sanitize(it, 120)}" }.orEmpty()}"
        }
        val evidenceIds = segment.mapNotNull { it.experienceId }.distinct().take(16)
        val first = segment.first()
        val sessionHash = hash(
            first.sessionId + "|" + (first.taskId ?: first.sessionId)
        ).take(16)
        val text = "$label (verified tool outcomes; not whole-goal proof; not permission) · " +
            "$steps · recheck current state before reuse"
        val id = hash(
            kind.name + "|" +
                segment.joinToString("|") { it.id }
        ).take(24)

        return CoordinatorExecutionExample(
            id = id,
            kind = kind,
            sourceSessionHash = sessionHash,
            tools = segment.map { it.tool },
            targets = segment.map { it.target },
            evidenceIds = evidenceIds,
            updatedAt = segment.maxOf { it.at },
            surprise = segment.maxOf { it.surprise },
            text = text
        )
    }

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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

    private fun sanitize(value: String, maxChars: Int): String = value
        .replace('\u0000', ' ')
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .take(maxChars)
}

/**
 * App-private, atomic event log projection for coordinator examples.
 * Raw tool stdout/stderr is deliberately not stored here.
 */
object CoordinatorExperienceStore {
    private const val FILE_NAME = "lumena_coordinator_experience.json"
    private val lock = Any()
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(CoordinatorEpisodeState::class.java)

    fun load(context: Context): CoordinatorEpisodeState = synchronized(lock) {
        val file = atomicFile(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return@synchronized CoordinatorEpisodeState()
        }

        val parsed = try {
            adapter.fromJson(
                file.openRead().bufferedReader().use { it.readText() }
            )
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Coordinator experience store is unreadable; refusing to replace verified history.",
                failure
            )
        }

        requireNotNull(parsed) {
            "Coordinator experience store is empty/corrupt; refusing to replace verified history."
        }
    }

    fun record(
        context: Context,
        sessionId: String,
        taskId: String?,
        request: ToolRequest,
        result: ToolResult,
        experienceId: String?,
        now: Long = System.currentTimeMillis()
    ): CoordinatorEpisodeEvent? = synchronized(lock) {
        if (result.outcomeUnknown) return@synchronized null

        val canonicalTool = ToolRegistry.canonicalize(request.tool)
        if (ToolRegistry.get(canonicalTool) == null) return@synchronized null

        val safeSessionId = stableId(sessionId)
        val safeTaskId = taskId?.takeIf { it.isNotBlank() }?.let(::stableId)
        val state = load(context)
        val target = targetOf(request)
        val surprise = CoordinatorExperiencePolicy.surprise(
            state = state,
            sessionId = safeSessionId,
            tool = canonicalTool,
            target = target,
            ok = result.ok
        )
        val requestIdentity = request.requestId?.takeIf { it.isNotBlank() }
            ?: CoordinatorExperiencePolicy.hash(
                canonicalTool + "|" +
                    request.args.toSortedMap().entries.joinToString("&") {
                        "${it.key}=${it.value}"
                    } + "|" + now
            )

        val event = CoordinatorEpisodeEvent(
            id = CoordinatorExperiencePolicy.hash(
                safeSessionId + "|" + safeTaskId.orEmpty() + "|" + requestIdentity
            ).take(24),
            sessionId = safeSessionId,
            taskId = safeTaskId,
            tool = canonicalTool,
            target = target,
            ok = result.ok,
            experienceId = experienceId?.take(160),
            at = now,
            surprise = surprise
        )
        val next = CoordinatorExperiencePolicy.record(state, event)
        save(context, next)
        event
    }

    fun relevant(
        context: Context,
        query: String,
        limit: Int = 4
    ): List<String> = synchronized(lock) {
        CoordinatorExperiencePolicy.examples(
            state = load(context),
            query = query,
            limit = limit
        ).map(CoordinatorExperiencePolicy::formatForPrompt)
    }

    fun examples(
        context: Context,
        query: String = "",
        limit: Int = 8
    ): List<CoordinatorExecutionExample> = synchronized(lock) {
        CoordinatorExperiencePolicy.examples(load(context), query, limit)
    }

    fun examplesForTask(
        context: Context,
        sessionId: String,
        taskId: String?,
        limit: Int = 32
    ): List<CoordinatorExecutionExample> = synchronized(lock) {
        val safeSessionId = stableId(sessionId)
        val safeTaskId = taskId
            ?.takeIf { it.isNotBlank() }
            ?.let(::stableId)
        val sourceHash = CoordinatorExperiencePolicy.hash(
            safeSessionId + "|" + (safeTaskId ?: safeSessionId)
        ).take(16)

        CoordinatorExperiencePolicy.examples(
            state = load(context),
            query = "",
            limit = limit.coerceIn(1, 64),
            sourceSessionHash = sourceHash
        )
    }

    fun clear(context: Context) = synchronized(lock) {
        val file = atomicFile(context).baseFile
        if (file.exists()) file.delete()
        File(file.path + ".bak").takeIf(File::exists)?.delete()
    }

    private fun save(
        context: Context,
        state: CoordinatorEpisodeState
    ) {
        val file = atomicFile(context)
        val out = file.startWrite()
        try {
            out.write(adapter.toJson(state).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (failure: Exception) {
            file.failWrite(out)
            throw failure
        }
    }

    private fun stableId(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) {
            trimmed
        } else {
            "id-" + CoordinatorExperiencePolicy.hash(trimmed).take(24)
        }
    }

    private fun targetOf(request: ToolRequest): String {
        val preferred = listOf(
            "path", "script", "cwd", "model", "url", "name", "query"
        )
        val pair = preferred.firstNotNullOfOrNull { key ->
            request.args[key]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { key to it }
        }
        return pair?.let { (key, value) ->
            "$key=${value.replace(Regex("[\\r\\n\\t]+"), " ").take(220)}"
        }.orEmpty()
    }

    private fun atomicFile(context: Context) = AtomicFile(
        File(context.applicationContext.filesDir, FILE_NAME)
    )
}
