package com.lumena.android.settings

enum class GenomeLayer {
    ANCHOR,
    SIGNATURE_CAPSULE,
    TOPIC_CAPSULE
}

data class GenomeMemoryUnit(
    val id: String,
    val layer: GenomeLayer,
    val topicKey: String,
    val text: String,
    val importance: Double,
    val updatedAt: Long,
    val evidenceIds: List<String> = emptyList()
)

data class GenomeExpressionPacket(
    val lines: List<String>,
    val selectedIds: List<String>,
    val evidenceIds: List<String>,
    val omittedUnits: Int,
    val usedChars: Int
)

object ContextGenomePolicy {
    fun express(
        units: List<GenomeMemoryUnit>,
        query: String,
        maxChars: Int,
        maxUnits: Int = 8
    ): GenomeExpressionPacket {
        if (units.isEmpty() || maxChars <= 0 || maxUnits <= 0) {
            return GenomeExpressionPacket(
                lines = emptyList(),
                selectedIds = emptyList(),
                evidenceIds = emptyList(),
                omittedUnits = units.size,
                usedChars = 0
            )
        }

        val queryTokens = tokenize(query)
        val broadQuery = queryTokens.size <= 3
        val newest = units.maxOfOrNull { it.updatedAt } ?: 0L

        val candidates = units
            .map { unit ->
                val unitTokens = tokenize(
                    unit.topicKey + " " + unit.text
                )
                val overlap = unitTokens.count { it in queryTokens }
                Candidate(
                    unit = unit,
                    tokens = unitTokens,
                    overlap = overlap,
                    relevant = queryTokens.isEmpty() || overlap > 0,
                    baseScore = baseScore(
                        unit = unit,
                        overlap = overlap,
                        broadQuery = broadQuery,
                        newest = newest
                    )
                )
            }
            .filter { it.relevant }
            .toMutableList()

        val selected = mutableListOf<Candidate>()
        val lines = mutableListOf<String>()
        var used = 0

        while (
            candidates.isNotEmpty() &&
            selected.size < maxUnits
        ) {
            val next = candidates.maxByOrNull { candidate ->
                candidate.baseScore - redundancyPenalty(
                    candidate,
                    selected
                )
            } ?: break

            candidates.remove(next)
            val line = format(next.unit)
            val additional = line.length + if (lines.isEmpty()) 0 else 1
            if (used + additional > maxChars) {
                continue
            }

            selected += next
            lines += line
            used += additional
        }

        return GenomeExpressionPacket(
            lines = lines,
            selectedIds = selected.map { it.unit.id },
            evidenceIds = selected
                .flatMap { it.unit.evidenceIds }
                .distinct()
                .take(64),
            omittedUnits = (units.size - selected.size).coerceAtLeast(0),
            usedChars = used
        )
    }

    private data class Candidate(
        val unit: GenomeMemoryUnit,
        val tokens: Set<String>,
        val overlap: Int,
        val relevant: Boolean,
        val baseScore: Double
    )

    private fun baseScore(
        unit: GenomeMemoryUnit,
        overlap: Int,
        broadQuery: Boolean,
        newest: Long
    ): Double {
        val relevance = overlap * 18.0
        val unresolved = if (
            unit.text.contains("NEGATIVE unresolved", ignoreCase = true) ||
            (
                unit.text.contains("unresolved=", ignoreCase = true) &&
                    !unit.text.contains("unresolved=0", ignoreCase = true)
            )
        ) 12.0 else 0.0

        val layerBias = when (unit.layer) {
            GenomeLayer.ANCHOR -> if (broadQuery) 2.0 else 7.0
            GenomeLayer.SIGNATURE_CAPSULE -> 6.0
            GenomeLayer.TOPIC_CAPSULE -> if (broadQuery) 9.0 else 3.0
        }

        val recency = if (newest <= 0L) {
            0.0
        } else {
            val age = (newest - unit.updatedAt).coerceAtLeast(0L)
            when {
                age == 0L -> 4.0
                age < 60_000L -> 3.0
                age < 3_600_000L -> 2.0
                else -> 0.0
            }
        }

        val resolvedPenalty =
            if (unit.text.contains("NEGATIVE resolved", ignoreCase = true)) -3.0 else 0.0

        return relevance +
            unit.importance.coerceIn(0.0, 40.0) +
            unresolved +
            layerBias +
            recency +
            resolvedPenalty
    }

    private fun redundancyPenalty(
        candidate: Candidate,
        selected: List<Candidate>
    ): Double {
        if (selected.isEmpty()) return 0.0
        val maxJaccard = selected.maxOf { other ->
            jaccard(candidate.tokens, other.tokens)
        }
        val sameTopic = selected.any {
            it.unit.topicKey.isNotBlank() &&
                it.unit.topicKey == candidate.unit.topicKey
        }
        return maxJaccard * 18.0 + if (sameTopic) 2.0 else 0.0
    }

    private fun jaccard(
        left: Set<String>,
        right: Set<String>
    ): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.count { it in right }
        val union = left.size + right.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    private fun format(unit: GenomeMemoryUnit): String {
        val prefix = when (unit.layer) {
            GenomeLayer.ANCHOR -> "A"
            GenomeLayer.SIGNATURE_CAPSULE -> "L1"
            GenomeLayer.TOPIC_CAPSULE -> "L2"
        }
        return "[$prefix] ${unit.text}"
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(900)
    }

    private fun tokenize(value: String): Set<String> = value
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}._:@/-]+"))
        .filter { it.length >= 2 }
        .toSet()
}
