package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class EvidenceCandidateDirective(
    val claimKey: String,
    val statement: String,
    val sourceIds: List<String>
)

data class EvidenceCandidateIngestReport(
    val accepted: Int,
    val rejected: Int
) {
    init {
        require(accepted >= 0)
        require(rejected >= 0)
    }
}

/**
 * Read-only parser for optional semantic-evidence metadata emitted alongside
 * normal agent JSON.
 *
 * This parser never creates a verified claim and never executes a tool. It
 * accepts directives only from one strict JSON envelope (or one strict JSON
 * code fence) and bounds every field. Quoted/prose JSON is ignored.
 */
object EvidenceModelDirectiveParser {
    private const val MAX_DIRECTIVES = 4
    private const val MAX_CLAIM_KEY_CHARS = 500
    private const val MAX_STATEMENT_CHARS = 1_600
    private const val MAX_SOURCE_IDS = 8

    private val sourceIdPattern =
        Regex("^[0-9a-f]{24}$")

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Suppress("UNCHECKED_CAST")
    private val mapAdapter =
        moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Any::class.java
            )
        )

    fun parse(
        raw: String
    ): List<EvidenceCandidateDirective> {
        val json = strictJsonEnvelope(raw)
            ?: return emptyList()
        val root = runCatching {
            mapAdapter.fromJson(json)
        }.getOrNull() ?: return emptyList()

        val rawCandidates =
            root["evidence_candidates"] as? List<*>
                ?: return emptyList()

        return rawCandidates
            .asSequence()
            .mapNotNull(::parseDirective)
            .distinctBy {
                it.claimKey + "|" +
                    it.statement + "|" +
                    it.sourceIds.joinToString(",")
            }
            .take(MAX_DIRECTIVES)
            .toList()
    }

    private fun parseDirective(
        raw: Any?
    ): EvidenceCandidateDirective? {
        val map = raw as? Map<*, *>
            ?: return null

        val claimKey = map["claim_key"]
            ?.toString()
            ?.sanitize(MAX_CLAIM_KEY_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val statement = map["statement"]
            ?.toString()
            ?.sanitize(MAX_STATEMENT_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val sourceIds = (map["source_ids"] as? List<*>)
            .orEmpty()
            .mapNotNull {
                it?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf(sourceIdPattern::matches)
            }
            .distinct()
            .take(MAX_SOURCE_IDS)

        if (sourceIds.isEmpty()) return null

        return EvidenceCandidateDirective(
            claimKey = claimKey,
            statement = statement,
            sourceIds = sourceIds
        )
    }

    private fun strictJsonEnvelope(
        raw: String
    ): String? {
        val text = raw.trim()
        if (
            text.startsWith("{") &&
            text.endsWith("}")
        ) {
            return text
        }

        val fences = listOf(
            "```json",
            "```JSON",
            "```"
        )

        for (fence in fences) {
            if (!text.startsWith(fence)) continue
            val start = fence.length
            val end = text.indexOf("```", start)
            if (end <= start) continue
            if (text.substring(end + 3).trim().isNotEmpty()) {
                continue
            }

            val body = text
                .substring(start, end)
                .trim()

            if (
                body.startsWith("{") &&
                body.endsWith("}")
            ) {
                return body
            }
        }

        return null
    }

    private fun String.sanitize(
        maxChars: Int
    ): String =
        replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(maxChars)
}
