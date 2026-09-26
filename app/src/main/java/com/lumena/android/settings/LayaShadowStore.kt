package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.ReflexCandidateSet
import com.lumena.android.agent.core.ReflexOption
import com.lumena.android.agent.local.LayaSystem1Result
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlin.math.ceil

data class LayaShadowSample(
    val id: String,
    val taskKey: String,
    val family: String,
    val attempt: Int,
    val constitutionalAnchor: String,
    val referenceOption: String,
    val layaOption: String? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    val confidence: Double? = null,
    val latencyMs: Long,
    val ok: Boolean,
    val errorCode: String? = null,
    val observedAt: Long
) {
    init {
        require(id.isNotBlank())
        require(taskKey.isNotBlank())
        require(family.isNotBlank())
        require(attempt >= 1)
        require(constitutionalAnchor.isNotBlank())
        require(referenceOption.isNotBlank())
        require(latencyMs >= 0)
        require(observedAt > 0)
        confidence?.let {
            require(it.isFinite() && it in 0.0..1.0)
        }
        require(
            probabilities.values.all {
                it.isFinite() && it in 0.0..1.0
            }
        )
        if (ok) {
            require(!layaOption.isNullOrBlank())
            require(layaOption in probabilities)
        }
    }
}

data class LayaShadowState(
    val version: Int = 1,
    val samples: List<LayaShadowSample> = emptyList()
)

data class LayaShadowMetrics(
    val samples: Int,
    val successful: Int,
    val unavailable: Int,
    val agreementWithReference: Double?,
    val meanConfidence: Double?,
    val latencyP50Ms: Long?,
    val latencyP95Ms: Long?,
    val lastChoice: String?,
    val lastErrorCode: String?
)

object LayaShadowPolicy {
    const val MAX_SAMPLES = 512

    fun record(
        state: LayaShadowState,
        taskKey: String,
        family: String,
        attempt: Int,
        candidates: ReflexCandidateSet,
        referenceOption: ReflexOption,
        result: LayaSystem1Result,
        now: Long
    ): LayaShadowState {
        require(taskKey.isNotBlank())
        require(family.isNotBlank())
        require(attempt >= 1)
        require(referenceOption in candidates.allowed)
        require(now > 0)

        val decision = result.decision
        val probabilities = decision
            ?.probabilities
            ?.mapKeys { it.key.name }
            .orEmpty()

        val sample = LayaShadowSample(
            id = TinyJevCalibrationPolicy.hash(
                listOf(
                    taskKey,
                    family,
                    attempt.toString(),
                    now.toString(),
                    candidates.constitutionalAnchor.name,
                    referenceOption.name,
                    decision?.option?.name.orEmpty()
                ).joinToString("|")
            ).take(24),
            taskKey = taskKey,
            family = family,
            attempt = attempt,
            constitutionalAnchor =
                candidates.constitutionalAnchor.name,
            referenceOption = referenceOption.name,
            layaOption = decision?.option?.name,
            probabilities = probabilities,
            confidence = decision?.confidence,
            latencyMs = result.latencyMs,
            ok = result.ok && decision != null,
            errorCode = result.errorCode
                ?.takeIf { it.isNotBlank() }
                ?.take(120),
            observedAt = now
        )

        return state.copy(
            samples = (state.samples + sample)
                .sortedBy { it.observedAt }
                .takeLast(MAX_SAMPLES)
        )
    }

    fun metrics(
        state: LayaShadowState
    ): LayaShadowMetrics {
        val successful = state.samples.filter { it.ok }
        val unavailable = state.samples.size - successful.size

        val agreement = successful
            .takeIf { it.isNotEmpty() }
            ?.let { rows ->
                rows.count {
                    it.layaOption == it.referenceOption
                }.toDouble() / rows.size.toDouble()
            }

        val meanConfidence = successful
            .mapNotNull { it.confidence }
            .takeIf { it.isNotEmpty() }
            ?.average()

        val latencies = successful
            .map { it.latencyMs }
            .sorted()

        val last = state.samples
            .maxByOrNull { it.observedAt }

        return LayaShadowMetrics(
            samples = state.samples.size,
            successful = successful.size,
            unavailable = unavailable,
            agreementWithReference = agreement,
            meanConfidence = meanConfidence,
            latencyP50Ms = percentile(latencies, 0.50),
            latencyP95Ms = percentile(latencies, 0.95),
            lastChoice = last?.layaOption,
            lastErrorCode =
                last?.takeUnless { it.ok }?.errorCode
        )
    }

    private fun percentile(
        sorted: List<Long>,
        q: Double
    ): Long? {
        if (sorted.isEmpty()) return null
        val index = (
            ceil(q.coerceIn(0.0, 1.0) * sorted.size)
                .toInt() - 1
            ).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}

/**
 * App-private bounded shadow telemetry.
 *
 * No user prompt, tool stdout/stderr, secrets, or model-generated prose are
 * persisted here. This store is observational only and cannot affect
 * ConstitutionKernel, ToolGate, or execution.
 */
object LayaShadowStore {
    private const val FILE_NAME =
        "lumena_laya_shadow_v1.json"
    private val lock = Any()
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(LayaShadowState::class.java)

    fun load(
        context: Context
    ): LayaShadowState = synchronized(lock) {
        val file = atomicFile(context)
        if (
            !file.baseFile.exists() &&
            !File(file.baseFile.path + ".bak").exists()
        ) {
            return@synchronized LayaShadowState()
        }

        val parsed = try {
            adapter.fromJson(
                file.openRead()
                    .bufferedReader()
                    .use { it.readText() }
            )
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Laya shadow store is unreadable; refusing to replace history.",
                failure
            )
        }

        requireNotNull(parsed) {
            "Laya shadow store is empty/corrupt; refusing to replace history."
        }
    }

    fun record(
        context: Context,
        taskId: String,
        family: String,
        attempt: Int,
        candidates: ReflexCandidateSet,
        referenceOption: ReflexOption,
        result: LayaSystem1Result,
        now: Long = System.currentTimeMillis()
    ) = synchronized(lock) {
        val next = LayaShadowPolicy.record(
            state = load(context),
            taskKey = TinyJevCalibrationPolicy
                .hash(taskId.trim())
                .take(24),
            family = family.trim().take(160),
            attempt = attempt,
            candidates = candidates,
            referenceOption = referenceOption,
            result = result,
            now = now
        )
        save(context, next)
    }

    fun metrics(
        context: Context
    ): LayaShadowMetrics = synchronized(lock) {
        LayaShadowPolicy.metrics(load(context))
    }

    fun clear(
        context: Context
    ) = synchronized(lock) {
        val file = atomicFile(context).baseFile
        if (file.exists()) file.delete()
        File(file.path + ".bak")
            .takeIf(File::exists)
            ?.delete()
    }

    private fun save(
        context: Context,
        state: LayaShadowState
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

    private fun atomicFile(
        context: Context
    ) = AtomicFile(
        File(
            context.applicationContext.filesDir,
            FILE_NAME
        )
    )
}
