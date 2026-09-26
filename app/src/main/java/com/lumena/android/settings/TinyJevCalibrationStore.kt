package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.ReflexOption
import com.lumena.android.agent.core.TinyJevDecision
import com.lumena.android.agent.core.ToolRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

data class TinyJevPendingPrediction(
    val id: String,
    val taskKey: String,
    val family: String,
    val attempt: Int,
    val modelVersion: String,
    val selectedOption: String,
    val probabilities: Map<String, Double>,
    val rawConfidence: Double,
    val decisionLatencyMs: Long,
    val predictedAt: Long
) {
    init {
        require(id.isNotBlank())
        require(taskKey.isNotBlank())
        require(family.isNotBlank())
        require(attempt >= 1)
        require(modelVersion.isNotBlank())
        require(selectedOption.isNotBlank())
        require(selectedOption in probabilities)
        require(probabilities.isNotEmpty())
        require(probabilities.values.all { it in 0.0..1.0 })
        require(rawConfidence in 0.0..1.0)
        require(decisionLatencyMs >= 0)
        require(predictedAt > 0)
    }
}

data class TinyJevCalibrationSample(
    val id: String,
    val predictionId: String,
    val taskKey: String,
    val family: String,
    val modelVersion: String,
    val selectedOption: String,
    val expectedOption: String,
    val probabilities: Map<String, Double>,
    val rawConfidence: Double,
    val correct: Boolean,
    val decisionLatencyMs: Long,
    val verifiedEvidenceIds: List<String>,
    val resolvedAt: Long
) {
    init {
        require(id.isNotBlank())
        require(predictionId.isNotBlank())
        require(taskKey.isNotBlank())
        require(family.isNotBlank())
        require(modelVersion.isNotBlank())
        require(selectedOption.isNotBlank())
        require(expectedOption.isNotBlank())
        require(expectedOption in probabilities)
        require(probabilities.values.all { it in 0.0..1.0 })
        require(rawConfidence in 0.0..1.0)
        require(decisionLatencyMs >= 0)
        require(verifiedEvidenceIds.isNotEmpty())
        require(resolvedAt > 0)
    }
}

data class TinyJevCalibrationState(
    val version: Int = 1,
    val pending: List<TinyJevPendingPrediction> = emptyList(),
    val resolved: List<TinyJevCalibrationSample> = emptyList()
)

data class TinyJevCalibrationMetrics(
    val samples: Int,
    val accuracy: Double,
    val brierScore: Double,
    val ece: Double,
    val selectiveThreshold: Double,
    val selectiveCoverage: Double,
    val selectiveAccuracy: Double?,
    val latencyP50Ms: Long?,
    val latencyP95Ms: Long?
)

data class TinyJevCalibrationEstimate(
    val confidence: Double,
    val calibrated: Boolean,
    val sampleCount: Int,
    val totalSamples: Int,
    val scope: String,
    val brierScore: Double,
    val ece: Double
) {
    init {
        require(confidence in 0.0..1.0)
        require(sampleCount >= 0)
        require(totalSamples >= 0)
    }
}

/**
 * Pure calibration policy for TinyJev.
 *
 * Ground truth is admitted only from Coordinator RECOVERY examples that carry
 * verified evidence IDs. ToolResult.ok by itself is never treated as a label.
 */
object TinyJevCalibrationPolicy {
    const val MAX_PENDING = 256
    const val MAX_RESOLVED = 1_024
    const val CONFIDENCE_BINS = 10
    const val DEFAULT_MIN_SAMPLES = 12
    const val DEFAULT_MIN_BIN_SAMPLES = 3
    const val DEFAULT_SELECTIVE_THRESHOLD = 0.75

    fun recordPrediction(
        state: TinyJevCalibrationState,
        taskKey: String,
        family: String,
        attempt: Int,
        decision: TinyJevDecision,
        selectedOption: ReflexOption,
        decisionLatencyMs: Long,
        now: Long
    ): TinyJevCalibrationState {
        require(taskKey.isNotBlank())
        require(family.isNotBlank())
        require(attempt >= 1)
        require(decisionLatencyMs >= 0)
        require(now > 0)

        val canonicalFamily = ToolRegistry.canonicalize(family)
        val probabilities = decision.scores
            .associate { score -> score.id to score.probability }
        val selected = selectedOption.name
        if (selected !in probabilities) return state

        val id = hash(
            taskKey + "|" +
                canonicalFamily + "|" +
                attempt + "|" +
                decision.modelVersion + "|" +
                selected
        ).take(24)

        if (state.pending.any { it.id == id } ||
            state.resolved.any { it.predictionId == id }) {
            return state
        }

        val prediction = TinyJevPendingPrediction(
            id = id,
            taskKey = taskKey,
            family = canonicalFamily,
            attempt = attempt,
            modelVersion = decision.modelVersion,
            selectedOption = selected,
            probabilities = probabilities,
            rawConfidence = decision.confidence,
            decisionLatencyMs = decisionLatencyMs,
            predictedAt = now
        )

        return state.copy(
            pending = (state.pending + prediction)
                .sortedBy { it.predictedAt }
                .takeLast(MAX_PENDING)
        )
    }

    fun resolveVerified(
        state: TinyJevCalibrationState,
        taskKey: String,
        examples: List<CoordinatorExecutionExample>,
        now: Long
    ): TinyJevCalibrationState {
        require(taskKey.isNotBlank())
        require(now > 0)

        val verifiedRecoveries = examples
            .asSequence()
            .filter { it.kind == CoordinatorExampleKind.RECOVERY }
            .filter { it.evidenceIds.isNotEmpty() }
            .filter { it.tools.isNotEmpty() }
            .toList()

        if (verifiedRecoveries.isEmpty()) return state

        val created = mutableListOf<TinyJevCalibrationSample>()
        val resolvedPredictionIds = mutableSetOf<String>()

        state.pending
            .asSequence()
            .filter { it.taskKey == taskKey }
            .forEach { prediction ->
                val match = verifiedRecoveries
                    .asSequence()
                    .filter { it.updatedAt >= prediction.predictedAt }
                    .filter { example ->
                        val first = example.tools.firstOrNull()
                            ?.let(ToolRegistry::canonicalize)
                        val last = example.tools.lastOrNull()
                            ?.let(ToolRegistry::canonicalize)
                        first == prediction.family &&
                            last == prediction.family
                    }
                    .minByOrNull { it.updatedAt }
                    ?: return@forEach

                val expected = expectedOption(match).name
                if (expected !in prediction.probabilities) {
                    return@forEach
                }

                val sampleId = hash(
                    prediction.id + "|" + match.id
                ).take(24)
                if (state.resolved.any { it.id == sampleId }) {
                    resolvedPredictionIds += prediction.id
                    return@forEach
                }

                created += TinyJevCalibrationSample(
                    id = sampleId,
                    predictionId = prediction.id,
                    taskKey = prediction.taskKey,
                    family = prediction.family,
                    modelVersion = prediction.modelVersion,
                    selectedOption = prediction.selectedOption,
                    expectedOption = expected,
                    probabilities = prediction.probabilities,
                    rawConfidence = prediction.rawConfidence,
                    correct = prediction.selectedOption == expected,
                    decisionLatencyMs = prediction.decisionLatencyMs,
                    verifiedEvidenceIds = match.evidenceIds
                        .distinct()
                        .take(16),
                    resolvedAt = maxOf(now, match.updatedAt)
                )
                resolvedPredictionIds += prediction.id
            }

        if (created.isEmpty() && resolvedPredictionIds.isEmpty()) return state

        return state.copy(
            pending = state.pending
                .filterNot { it.id in resolvedPredictionIds }
                .takeLast(MAX_PENDING),
            resolved = (state.resolved + created)
                .distinctBy { it.id }
                .sortedBy { it.resolvedAt }
                .takeLast(MAX_RESOLVED)
        )
    }

    fun metrics(
        state: TinyJevCalibrationState,
        family: String? = null,
        selectiveThreshold: Double = DEFAULT_SELECTIVE_THRESHOLD
    ): TinyJevCalibrationMetrics {
        require(selectiveThreshold in 0.0..1.0)
        val canonicalFamily = family
            ?.takeIf { it.isNotBlank() }
            ?.let(ToolRegistry::canonicalize)
        val samples = state.resolved.filter {
            canonicalFamily == null || it.family == canonicalFamily
        }

        if (samples.isEmpty()) {
            return TinyJevCalibrationMetrics(
                samples = 0,
                accuracy = 0.0,
                brierScore = 0.0,
                ece = 0.0,
                selectiveThreshold = selectiveThreshold,
                selectiveCoverage = 0.0,
                selectiveAccuracy = null,
                latencyP50Ms = null,
                latencyP95Ms = null
            )
        }

        val accuracy = samples.count { it.correct }.toDouble() / samples.size.toDouble()
        val brier = samples
            .map(::multiclassBrier)
            .average()
        val ece = expectedCalibrationError(samples)

        val selective = samples.filter { it.rawConfidence >= selectiveThreshold }
        val coverage = selective.size.toDouble() / samples.size.toDouble()
        val selectiveAccuracy = selective
            .takeIf { it.isNotEmpty() }
            ?.let { selected ->
                selected.count { it.correct }.toDouble() / selected.size.toDouble()
            }

        val latencies = samples
            .map { it.decisionLatencyMs }
            .sorted()

        return TinyJevCalibrationMetrics(
            samples = samples.size,
            accuracy = accuracy,
            brierScore = brier,
            ece = ece,
            selectiveThreshold = selectiveThreshold,
            selectiveCoverage = coverage,
            selectiveAccuracy = selectiveAccuracy,
            latencyP50Ms = percentile(latencies, 0.50),
            latencyP95Ms = percentile(latencies, 0.95)
        )
    }

    fun estimate(
        state: TinyJevCalibrationState,
        family: String,
        rawConfidence: Double,
        minSamples: Int = DEFAULT_MIN_SAMPLES,
        minBinSamples: Int = DEFAULT_MIN_BIN_SAMPLES
    ): TinyJevCalibrationEstimate {
        require(rawConfidence in 0.0..1.0)
        require(minSamples >= 1)
        require(minBinSamples >= 1)

        val canonicalFamily = ToolRegistry.canonicalize(family)
        val familySamples = state.resolved.filter {
            it.family == canonicalFamily
        }
        val (pool, scope) = when {
            familySamples.size >= minSamples -> familySamples to "family"
            state.resolved.size >= minSamples -> state.resolved to "global"
            else -> emptyList<TinyJevCalibrationSample>() to "insufficient"
        }

        if (pool.isEmpty()) {
            return TinyJevCalibrationEstimate(
                confidence = rawConfidence,
                calibrated = false,
                sampleCount = 0,
                totalSamples = state.resolved.size,
                scope = scope,
                brierScore = metrics(state, canonicalFamily).brierScore,
                ece = metrics(state, canonicalFamily).ece
            )
        }

        val targetBin = confidenceBin(rawConfidence)
        val binSamples = pool.filter {
            confidenceBin(it.rawConfidence) == targetBin
        }
        val poolState = TinyJevCalibrationState(resolved = pool)
        val poolMetrics = metrics(poolState)

        if (binSamples.size < minBinSamples) {
            return TinyJevCalibrationEstimate(
                confidence = rawConfidence,
                calibrated = false,
                sampleCount = binSamples.size,
                totalSamples = pool.size,
                scope = scope,
                brierScore = poolMetrics.brierScore,
                ece = poolMetrics.ece
            )
        }

        val correct = binSamples.count { it.correct }
        // Beta(1,1) smoothing prevents tiny bins from producing 0/1 certainty.
        val empirical = (correct + 1.0) / (binSamples.size + 2.0)
        val conservative = min(rawConfidence, empirical)
            .coerceIn(0.0, 1.0)

        return TinyJevCalibrationEstimate(
            confidence = conservative,
            calibrated = true,
            sampleCount = binSamples.size,
            totalSamples = pool.size,
            scope = scope,
            brierScore = poolMetrics.brierScore,
            ece = poolMetrics.ece
        )
    }

    private fun expectedOption(
        example: CoordinatorExecutionExample
    ): ReflexOption =
        if (example.tools.size <= 2) {
            ReflexOption.RETRY_VARIANT
        } else {
            ReflexOption.TRY_ALTERNATIVE
        }

    private fun multiclassBrier(
        sample: TinyJevCalibrationSample
    ): Double {
        val k = sample.probabilities.size.coerceAtLeast(1)
        val sum = sample.probabilities.entries.sumOf { (option, probability) ->
            val y = if (option == sample.expectedOption) 1.0 else 0.0
            val delta = probability - y
            delta * delta
        }
        return (sum / k.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun expectedCalibrationError(
        samples: List<TinyJevCalibrationSample>
    ): Double {
        if (samples.isEmpty()) return 0.0
        return (0 until CONFIDENCE_BINS).sumOf { bin ->
            val members = samples.filter {
                confidenceBin(it.rawConfidence) == bin
            }
            if (members.isEmpty()) {
                0.0
            } else {
                val meanConfidence = members
                    .map { it.rawConfidence }
                    .average()
                val meanAccuracy = members
                    .count { it.correct }
                    .toDouble() / members.size.toDouble()
                val weight = members.size.toDouble() / samples.size.toDouble()
                weight * kotlin.math.abs(meanAccuracy - meanConfidence)
            }
        }.coerceIn(0.0, 1.0)
    }

    private fun confidenceBin(confidence: Double): Int =
        floor(confidence.coerceIn(0.0, 1.0) * CONFIDENCE_BINS)
            .toInt()
            .coerceIn(0, CONFIDENCE_BINS - 1)

    private fun percentile(
        sorted: List<Long>,
        quantile: Double
    ): Long? {
        if (sorted.isEmpty()) return null
        val index = (
            ceil(quantile.coerceIn(0.0, 1.0) * sorted.size.toDouble())
                .toInt() - 1
            ).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    fun hash(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * App-private fail-closed calibration store.
 *
 * Only bounded probabilities, hashed task identity, model version, latency and
 * verified evidence IDs are persisted. Raw prompts/tool outputs are excluded.
 */
object TinyJevCalibrationStore {
    private const val FILE_NAME = "lumena_tinyjev_calibration.json"
    private val lock = Any()
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(TinyJevCalibrationState::class.java)

    fun load(context: Context): TinyJevCalibrationState = synchronized(lock) {
        val file = atomicFile(context)
        if (!file.baseFile.exists() &&
            !File(file.baseFile.path + ".bak").exists()) {
            return@synchronized TinyJevCalibrationState()
        }

        val parsed = try {
            adapter.fromJson(
                file.openRead()
                    .bufferedReader()
                    .use { it.readText() }
            )
        } catch (failure: Exception) {
            throw IllegalStateException(
                "TinyJev calibration store is unreadable; refusing to replace calibration history.",
                failure
            )
        }

        requireNotNull(parsed) {
            "TinyJev calibration store is empty/corrupt; refusing to replace calibration history."
        }
    }

    fun recordPrediction(
        context: Context,
        taskId: String,
        family: String,
        attempt: Int,
        decision: TinyJevDecision,
        selectedOption: ReflexOption,
        decisionLatencyMs: Long,
        now: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val next = TinyJevCalibrationPolicy.recordPrediction(
            state = load(context),
            taskKey = taskKey(taskId),
            family = family,
            attempt = attempt,
            decision = decision,
            selectedOption = selectedOption,
            decisionLatencyMs = decisionLatencyMs,
            now = now
        )
        save(context, next)
    }

    fun resolveFromVerifiedExamples(
        context: Context,
        taskId: String,
        examples: List<CoordinatorExecutionExample>,
        now: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val next = TinyJevCalibrationPolicy.resolveVerified(
            state = load(context),
            taskKey = taskKey(taskId),
            examples = examples,
            now = now
        )
        save(context, next)
    }

    fun estimate(
        context: Context,
        family: String,
        rawConfidence: Double
    ): TinyJevCalibrationEstimate = synchronized(lock) {
        TinyJevCalibrationPolicy.estimate(
            state = load(context),
            family = family,
            rawConfidence = rawConfidence
        )
    }

    fun metrics(
        context: Context,
        family: String? = null
    ): TinyJevCalibrationMetrics = synchronized(lock) {
        TinyJevCalibrationPolicy.metrics(
            state = load(context),
            family = family
        )
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
        state: TinyJevCalibrationState
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

    private fun taskKey(taskId: String): String =
        TinyJevCalibrationPolicy.hash(taskId.trim())
            .take(24)

    private fun atomicFile(context: Context) = AtomicFile(
        File(
            context.applicationContext.filesDir,
            FILE_NAME
        )
    )
}
