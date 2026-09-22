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

data class ExperienceMemoryStats(
    val positive: Int,
    val negative: Int,
    val unresolvedNegative: Int,
    val total: Int
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
                (
                    valence == ExperienceValence.POSITIVE ||
                        (it.summary == summary && it.resolvedAt == null)
                )
        }

        if (existingIndex >= 0) {
            val old = updated[existingIndex]
            updated[existingIndex] = old.copy(
                summary = if (valence == ExperienceValence.POSITIVE) summary else old.summary,
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
            .map { anchor ->
                val searchable = tokenize("${anchor.tool} ${anchor.target} ${anchor.summary}")
                val overlap = searchable.count { it in tokens }
                Triple(anchor, score(anchor, tokens), overlap)
            }
            .filter { (_, _, overlap) -> tokens.isEmpty() || overlap > 0 }
            .sortedWith(
                compareByDescending<Triple<ExperienceAnchor, Int, Int>> { it.second }
                    .thenByDescending { it.first.lastSeenAt }
                    .thenByDescending { it.first.occurrences }
            )
            .take(limit.coerceIn(1, 16))
            .map { (anchor, _, _) -> format(anchor) }
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
        val preferred = listOf("path", "script", "cwd", "model", "url", "name", "query")
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

object ExperienceMemoryFileCodec {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(ExperienceMemoryState::class.java)

    fun load(file: File): ExperienceMemoryState {
        if (!file.exists()) return ExperienceMemoryState()
        return runCatching { adapter.fromJson(file.readText()) }
            .getOrNull()
            ?: ExperienceMemoryState()
    }

    fun save(file: File, state: ExperienceMemoryState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(adapter.toJson(state))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

object ExperienceMemoryStore {
    private const val FILE_NAME = "lumena_experience_memory.json"
    private val lock = Any()

    fun load(context: Context): ExperienceMemoryState = synchronized(lock) {
        val app = context.applicationContext
        ContextGenomeStore.loadProjection(app)?.let { return@synchronized it }

        val legacy = ExperienceMemoryFileCodec.load(file(app))
        if (legacy.anchors.isNotEmpty()) {
            ContextGenomeStore.importLegacyProjection(app, legacy)
        }
        legacy
    }

    fun record(
        context: Context,
        request: ToolRequest,
        result: ToolResult,
        now: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val app = context.applicationContext
        val next = ExperienceMemoryIndex.record(load(app), request, result, now)
        val eventId = ContextGenomeStore.record(
            context = app,
            request = request,
            result = result,
            projection = next,
            now = now
        )
        // SQLite is authoritative after the first verified event. Keep no diverging
        // writable JSON mirror; the legacy file is only an import source.
        val legacy = file(app)
        if (legacy.exists()) legacy.delete()
        eventId
    }

    fun relevant(
        context: Context,
        query: String,
        limit: Int = 8
    ): List<String> = synchronized(lock) {
        val app = context.applicationContext
        // Ensure legacy JSON, if any, is projected into SQLite before expression.
        val state = load(app)
        val packet = ContextGenomeStore.express(
            context = app,
            query = query,
            maxChars = 3_200,
            maxUnits = limit
        )
        val local = if (packet.lines.isNotEmpty()) {
            packet.lines
        } else {
            ExperienceMemoryIndex.relevant(state, query, limit)
        }

        // Imported portable experience is source-device evidence only. It is
        // deliberately appended after locally verified memory and is always
        // labelled as requiring local revalidation. It cannot activate rules,
        // grant permissions, or replace TOOL_RESULT evidence on this device.
        val remaining = (limit.coerceIn(1, 16) - local.size).coerceAtLeast(0)
        val portable = if (remaining > 0) {
            PortableKernelStore.advice(app, query, remaining)
        } else {
            emptyList()
        }

        (local + portable)
            .distinct()
            .take(limit.coerceIn(1, 16))
    }

    fun stats(context: Context): ExperienceMemoryStats = synchronized(lock) {
        val anchors = load(context.applicationContext).anchors
        ExperienceMemoryStats(
            positive = anchors.count { it.valence == ExperienceValence.POSITIVE },
            negative = anchors.count { it.valence == ExperienceValence.NEGATIVE },
            unresolvedNegative = anchors.count {
                it.valence == ExperienceValence.NEGATIVE && it.resolvedAt == null
            },
            total = anchors.size
        )
    }

    fun clear(context: Context) = synchronized(lock) {
        val app = context.applicationContext
        ContextGenomeStore.clear(app)
        ExperienceLandscapeStore.clear(app)
        val legacy = file(app)
        if (legacy.exists()) legacy.delete()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
