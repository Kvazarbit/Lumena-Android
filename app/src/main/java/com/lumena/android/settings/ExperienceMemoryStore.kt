package com.lumena.android.settings

import android.content.Context
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

enum class ExperienceValence {
    POSITIVE,
    NEGATIVE
}

data class ExperienceAnchor(
    val id: String,
    val signature: String,
    val tool: String,
    val target: String,
    val valence: ExperienceValence,
    val summary: String,
    val occurrences: Int = 1,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val resolvedAt: Long? = null
)

data class ExperienceMemoryState(
    val anchors: List<ExperienceAnchor> = emptyList()
)

object ExperienceMemoryIndex {
    private const val MAX_ANCHORS = 128

    fun record(
        state: ExperienceMemoryState,
        request: ToolRequest,
        result: ToolResult,
        now: Long
    ): ExperienceMemoryState {
        val tool = request.tool.trim().lowercase()
        val target = targetOf(request)
        val signature = sha256("$tool|$target")
        val valence = if (result.ok) ExperienceValence.POSITIVE else ExperienceValence.NEGATIVE
        val summary = summarize(result)

        val updated = state.anchors.map { anchor ->
            if (
                result.ok &&
                anchor.signature == signature &&
                anchor.valence == ExperienceValence.NEGATIVE &&
                anchor.resolvedAt == null
            ) {
                anchor.copy(resolvedAt = now, lastSeenAt = max(anchor.lastSeenAt, now))
            } else {
                anchor
            }
        }.toMutableList()

        val existingIndex = updated.indexOfFirst {
            it.signature == signature &&
                it.valence == valence &&
                it.summary == summary &&
                (valence == ExperienceValence.POSITIVE || it.resolvedAt == null)
        }

        if (existingIndex >= 0) {
            val old = updated[existingIndex]
            updated[existingIndex] = old.copy(
                occurrences = old.occurrences + 1,
                lastSeenAt = now,
                resolvedAt = if (valence == ExperienceValence.POSITIVE) null else old.resolvedAt
            )
        } else {
            updated += ExperienceAnchor(
                id = sha256("$signature|$valence|$summary|$now").take(24),
                signature = signature,
                tool = tool,
                target = target,
                valence = valence,
                summary = summary,
                firstSeenAt = now,
                lastSeenAt = now
            )
        }

        val retained = updated
            .sortedWith(
                compareByDescending<ExperienceAnchor> { it.resolvedAt == null }
                    .thenByDescending { it.lastSeenAt }
                    .thenByDescending { it.occurrences }
            )
            .take(MAX_ANCHORS)

        return ExperienceMemoryState(retained)
    }

    fun relevant(
        state: ExperienceMemoryState,
        query: String,
        limit: Int = 8
    ): List<String> {
        val tokens = tokenize(query)
        return state.anchors
            .map { anchor -> anchor to score(anchor, tokens) }
            .sortedWith(
                compareByDescending<Pair<ExperienceAnchor, Int>> { it.second }
                    .thenByDescending { it.first.lastSeenAt }
                    .thenByDescending { it.first.occurrences }
            )
            .take(limit.coerceIn(1, 16))
            .map { (anchor, _) -> format(anchor) }
    }

    private fun score(anchor: ExperienceAnchor, queryTokens: Set<String>): Int {
        val searchable = tokenize("${anchor.tool} ${anchor.target} ${anchor.summary}")
        val overlap = searchable.count { it in queryTokens }
        val unresolvedBoost =
            if (anchor.valence == ExperienceValence.NEGATIVE && anchor.resolvedAt == null) 8 else 0
        val positiveBoost = if (anchor.valence == ExperienceValence.POSITIVE) 4 else 0
        val resolvedPenalty = if (anchor.resolvedAt != null) -3 else 0
        return overlap * 6 + unresolvedBoost + positiveBoost + resolvedPenalty +
            anchor.occurrences.coerceAtMost(5)
    }

    private fun format(anchor: ExperienceAnchor): String {
        val state = when {
            anchor.valence == ExperienceValence.POSITIVE -> "POSITIVE verified"
            anchor.resolvedAt == null -> "NEGATIVE unresolved"
            else -> "NEGATIVE resolved"
        }
        val target = anchor.target.ifBlank { "(general)" }
        return "$state · ${anchor.tool} · target=$target · ${anchor.summary} · seen=${anchor.occurrences}x"
    }

    private fun targetOf(request: ToolRequest): String {
        val preferred = listOf("path", "script", "cwd", "model", "url", "name")
        val pair = preferred.firstNotNullOfOrNull { key ->
            request.args[key]?.trim()?.takeIf { it.isNotBlank() }?.let { key to it }
        }
        return pair?.let { (key, value) -> "$key=${sanitize(value, 220)}" }.orEmpty()
    }

    private fun summarize(result: ToolResult): String {
        if (result.ok) {
            val first = result.stdout.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
            return first?.let { "success: ${sanitize(it, 320)}" }
                ?: "success${result.exitCode?.let { " exit=$it" }.orEmpty()}"
        }

        val detail = sequenceOf(result.error, result.stderr, result.stdout)
            .filterNotNull()
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?: "tool failed"
        return "failure: ${sanitize(detail, 420)}"
    }

    private fun sanitize(value: String, maxChars: Int): String = value
        .replace('\u0000', ' ')
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .take(maxChars)

    private fun tokenize(value: String): Set<String> = value
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}._:@/-]+"))
        .filter { it.length >= 2 }
        .toSet()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

object ExperienceMemoryStore {
    private const val FILE_NAME = "lumena_experience_memory.json"
    private val lock = Any()
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(ExperienceMemoryState::class.java)

    fun load(context: Context): ExperienceMemoryState = synchronized(lock) {
        val file = file(context.applicationContext)
        if (!file.exists()) return@synchronized ExperienceMemoryState()
        runCatching { adapter.fromJson(file.readText()) }
            .getOrNull()
            ?: ExperienceMemoryState()
    }

    fun record(
        context: Context,
        request: ToolRequest,
        result: ToolResult,
        now: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val app = context.applicationContext
        val next = ExperienceMemoryIndex.record(load(app), request, result, now)
        save(app, next)
    }

    fun relevant(
        context: Context,
        query: String,
        limit: Int = 8
    ): List<String> = synchronized(lock) {
        ExperienceMemoryIndex.relevant(load(context.applicationContext), query, limit)
    }

    private fun save(context: Context, state: ExperienceMemoryState) {
        val file = file(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(adapter.toJson(state))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
