package com.lumena.android.settings
import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.ReflexAdviceSource
import com.lumena.android.agent.core.ReflexOption
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest
import java.util.UUID
enum class CognitiveInfluenceLayer {
    CONSTITUTION,
    REFLEX,
    TINYJEV,
    LAYA
}
data class CognitiveInfluenceCounters(
    val eligible: Long = 0,
    val generated: Long = 0,
    val admitted: Long = 0,
    val exposed: Long = 0,
    val wouldExpose: Long = 0,
    val agreed: Long = 0,
    val nextOk: Long = 0,
    val nextFail: Long = 0,
    val nextUnknown: Long = 0
)
data class PendingReflexInfluence(
    val taskHash: String,
    val family: String,
    val option: String,
    val source: String,
    val exposedAt: Long
)
data class CognitiveInfluenceState(
    val version: Int = 1,
    val epoch: String = "",
    val since: Long = 0,
    val versionCode: Long = 0,
    val constitution: CognitiveInfluenceCounters = CognitiveInfluenceCounters(),
    val reflex: CognitiveInfluenceCounters = CognitiveInfluenceCounters(),
    val tinyJev: CognitiveInfluenceCounters = CognitiveInfluenceCounters(),
    val laya: CognitiveInfluenceCounters = CognitiveInfluenceCounters(),
    val pendingReflex: List<PendingReflexInfluence> = emptyList(),
    val pendingArms: List<PendingAdvisoryArm> = emptyList(),
    val armOutcomes: Map<String, AdvisoryArmOutcome> = emptyMap(),
    val previousEpochs: List<CognitiveInfluenceEpochSummary> = emptyList()
)
/** Task-level ON/OFF assignment waiting for the task's terminal status. */
data class PendingAdvisoryArm(
    val taskHash: String,
    val layer: String,
    val arm: String,
    val assignedAt: Long
)
/** Terminal task outcomes per (layer, arm). Comparable only between arms. */
data class AdvisoryArmOutcome(
    val tasks: Long = 0,
    val done: Long = 0,
    val partial: Long = 0,
    val failed: Long = 0,
    val unresolved: Long = 0
)
/** Closed epoch kept so an APK update does not erase collected evidence. */
data class CognitiveInfluenceEpochSummary(
    val epoch: String,
    val since: Long,
    val until: Long,
    val versionCode: Long,
    val constitution: CognitiveInfluenceCounters = CognitiveInfluenceCounters(),
    val reflex: CognitiveInfluenceCounters = CognitiveInfluenceCounters(),
    val tinyJev: CognitiveInfluenceCounters = CognitiveInfluenceCounters(),
    val laya: CognitiveInfluenceCounters = CognitiveInfluenceCounters(),
    val armOutcomes: Map<String, AdvisoryArmOutcome> = emptyMap()
)
/**
 * Pure observational reducer. Nothing here can authorize, rank, gate or execute.
 * Counts represent reached stages, not causal effect.
 */
object CognitiveInfluencePolicy {
    const val MAX_PENDING = 32
    const val MAX_PENDING_ARMS = 64
    const val MAX_PREVIOUS_EPOCHS = 6

    fun armKey(layer: String, arm: String): String = "$layer:$arm"

    /**
     * Records one assignment per (task, layer). Called only when the layer
     * actually had advice to show, so both arms are eligible decisions.
     */
    fun recordArm(
        state: CognitiveInfluenceState,
        taskId: String,
        layer: String,
        arm: String,
        now: Long
    ): CognitiveInfluenceState {
        val hash = taskHash(taskId)
        if (state.pendingArms.any { it.taskHash == hash && it.layer == layer }) return state
        val pending = state.pendingArms + PendingAdvisoryArm(hash, layer, arm, now)
        val overflow = pending.size - MAX_PENDING_ARMS
        var outcomes = state.armOutcomes
        pending.take(overflow.coerceAtLeast(0)).forEach { dropped ->
            val key = armKey(dropped.layer, dropped.arm)
            val current = outcomes[key] ?: AdvisoryArmOutcome()
            outcomes = outcomes + (key to current.copy(unresolved = current.unresolved + 1))
        }
        return state.copy(
            pendingArms = pending.takeLast(MAX_PENDING_ARMS),
            armOutcomes = outcomes
        )
    }

    /** Attributes the terminal task status to every arm assigned to it. */
    fun resolveTaskOutcome(
        state: CognitiveInfluenceState,
        taskId: String,
        status: String
    ): CognitiveInfluenceState {
        val hash = taskHash(taskId)
        val assigned = state.pendingArms.filter { it.taskHash == hash }
        if (assigned.isEmpty()) return state
        var outcomes = state.armOutcomes
        assigned.forEach { pending ->
            val key = armKey(pending.layer, pending.arm)
            val c = outcomes[key] ?: AdvisoryArmOutcome()
            outcomes = outcomes + (key to c.copy(
                tasks = c.tasks + 1,
                done = c.done + if (status == "DONE") 1 else 0,
                partial = c.partial + if (status == "PARTIAL") 1 else 0,
                failed = c.failed + if (status == "FAILED") 1 else 0,
                unresolved = c.unresolved + if (status !in setOf("DONE", "PARTIAL", "FAILED")) 1 else 0
            ))
        }
        return state.copy(
            pendingArms = state.pendingArms.filterNot { it.taskHash == hash },
            armOutcomes = outcomes
        )
    }

    /** Opens a new epoch while keeping a bounded summary of the closed one. */
    fun rollEpoch(
        previous: CognitiveInfluenceState,
        fresh: CognitiveInfluenceState,
        now: Long
    ): CognitiveInfluenceState {
        if (previous.epoch.isBlank() || previous.since <= 0) return fresh
        val summary = CognitiveInfluenceEpochSummary(
            epoch = previous.epoch,
            since = previous.since,
            until = now,
            versionCode = previous.versionCode,
            constitution = previous.constitution,
            reflex = previous.reflex,
            tinyJev = previous.tinyJev,
            laya = previous.laya,
            armOutcomes = previous.armOutcomes
        )
        return fresh.copy(
            previousEpochs = (previous.previousEpochs + summary).takeLast(MAX_PREVIOUS_EPOCHS)
        )
    }
    fun recordLayer(
        state: CognitiveInfluenceState,
        layer: CognitiveInfluenceLayer,
        eligible: Boolean = false,
        generated: Boolean = false,
        admitted: Boolean = false,
        exposed: Boolean = false,
        wouldExpose: Boolean = false,
        agreed: Boolean = false,
        nextOk: Boolean = false,
        nextFail: Boolean = false,
        nextUnknown: Boolean = false
    ): CognitiveInfluenceState {
        fun bump(c: CognitiveInfluenceCounters) = c.copy(
            eligible = c.eligible + if (eligible) 1 else 0,
            generated = c.generated + if (generated) 1 else 0,
            admitted = c.admitted + if (admitted) 1 else 0,
            exposed = c.exposed + if (exposed) 1 else 0,
            wouldExpose = c.wouldExpose + if (wouldExpose) 1 else 0,
            agreed = c.agreed + if (agreed) 1 else 0,
            nextOk = c.nextOk + if (nextOk) 1 else 0,
            nextFail = c.nextFail + if (nextFail) 1 else 0,
            nextUnknown = c.nextUnknown + if (nextUnknown) 1 else 0
        )
        return when (layer) {
            CognitiveInfluenceLayer.CONSTITUTION -> state.copy(constitution = bump(state.constitution))
            CognitiveInfluenceLayer.REFLEX -> state.copy(reflex = bump(state.reflex))
            CognitiveInfluenceLayer.TINYJEV -> state.copy(tinyJev = bump(state.tinyJev))
            CognitiveInfluenceLayer.LAYA -> state.copy(laya = bump(state.laya))
        }
    }
    fun recordReflexExposure(
        state: CognitiveInfluenceState,
        taskId: String,
        family: String?,
        option: ReflexOption,
        source: ReflexAdviceSource,
        now: Long
    ): CognitiveInfluenceState {
        var next = recordLayer(
            state,
            CognitiveInfluenceLayer.REFLEX,
            exposed = true
        )
        if (source == ReflexAdviceSource.TINYJEV) {
            next = recordLayer(
                next,
                CognitiveInfluenceLayer.TINYJEV,
                exposed = true
            )
        }
        val canonicalFamily = family
            ?.takeIf { it.isNotBlank() }
            ?.let(ToolRegistry::canonicalize)
            ?: return next
        val pending = PendingReflexInfluence(
            taskHash = taskHash(taskId),
            family = canonicalFamily,
            option = option.name,
            source = source.name,
            exposedAt = now
        )
        return next.copy(
            pendingReflex = (
                next.pendingReflex.filterNot { it.taskHash == pending.taskHash } + pending
                ).takeLast(MAX_PENDING)
        )
    }
    fun resolveNextTool(
        state: CognitiveInfluenceState,
        taskId: String,
        request: ToolRequest,
        result: ToolResult
    ): CognitiveInfluenceState {
        val hash = taskHash(taskId)
        val pending = state.pendingReflex.lastOrNull { it.taskHash == hash }
            ?: return state
        val observed = if (
            ToolRegistry.canonicalize(request.tool) == pending.family
        ) {
            ReflexOption.RETRY_VARIANT
        } else {
            ReflexOption.TRY_ALTERNATIVE
        }
        val agreed = observed.name == pending.option
        val unknown = result.outcomeUnknown
        val ok = !unknown && result.ok
        val failed = !unknown && !result.ok
        var next = recordLayer(
            state,
            CognitiveInfluenceLayer.REFLEX,
            agreed = agreed,
            nextOk = ok,
            nextFail = failed,
            nextUnknown = unknown
        )
        if (pending.source == ReflexAdviceSource.TINYJEV.name) {
            next = recordLayer(
                next,
                CognitiveInfluenceLayer.TINYJEV,
                agreed = agreed,
                nextOk = ok,
                nextFail = failed,
                nextUnknown = unknown
            )
        }
        return next.copy(
            pendingReflex = next.pendingReflex.filterNot { it.taskHash == hash }
        )
    }
    fun resolvePartial(
        state: CognitiveInfluenceState,
        taskId: String
    ): CognitiveInfluenceState {
        val hash = taskHash(taskId)
        val pending = state.pendingReflex.lastOrNull { it.taskHash == hash }
            ?: return state
        val agreed = pending.option == ReflexOption.DEGRADE_PARTIAL.name
        var next = recordLayer(
            state,
            CognitiveInfluenceLayer.REFLEX,
            agreed = agreed
        )
        if (pending.source == ReflexAdviceSource.TINYJEV.name) {
            next = recordLayer(
                next,
                CognitiveInfluenceLayer.TINYJEV,
                agreed = agreed
            )
        }
        return next.copy(
            pendingReflex = next.pendingReflex.filterNot { it.taskHash == hash }
        )
    }
    fun taskHash(taskId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(taskId.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
}
/**
 * App-private bounded observational telemetry. No prompts, tool stdout/stderr,
 * arguments, paths or secrets are persisted.
 */
object CognitiveInfluenceStore {
    private const val FILE_NAME = "lumena_cognitive_influence_v1.json"
    private val lock = StateVaultLock.monitor
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(CognitiveInfluenceState::class.java)
    fun load(context: Context): CognitiveInfluenceState = synchronized(lock) {
        val file = atomicFile(context)
        val versionCode = currentVersionCode(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            val fresh = fresh(versionCode)
            save(context, fresh)
            return@synchronized fresh
        }
        val parsed = try {
            adapter.fromJson(
                file.openRead().bufferedReader().use { it.readText() }
            )
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Cognitive influence telemetry is unreadable; refusing to replace history.",
                failure
            )
        }
        val state = requireNotNull(parsed) {
            "Cognitive influence telemetry is empty/corrupt; refusing to replace history."
        }
        if (state.versionCode != versionCode || state.epoch.isBlank() || state.since <= 0) {
            val rolled = CognitiveInfluencePolicy.rollEpoch(
                previous = state,
                fresh = fresh(versionCode),
                now = System.currentTimeMillis().coerceAtLeast(1L)
            )
            save(context, rolled)
            rolled
        } else {
            state
        }
    }
    fun recordLayer(
        context: Context,
        layer: CognitiveInfluenceLayer,
        eligible: Boolean = false,
        generated: Boolean = false,
        admitted: Boolean = false,
        exposed: Boolean = false,
        wouldExpose: Boolean = false
    ) = mutate(context) { state ->
        CognitiveInfluencePolicy.recordLayer(
            state = state,
            layer = layer,
            eligible = eligible,
            generated = generated,
            admitted = admitted,
            exposed = exposed,
            wouldExpose = wouldExpose
        )
    }
    fun recordConstitutionProjection(
        context: Context,
        generated: Boolean,
        admitted: Boolean,
        exposed: Boolean,
        wouldExpose: Boolean
    ) = recordLayer(
        context = context,
        layer = CognitiveInfluenceLayer.CONSTITUTION,
        eligible = true,
        generated = generated,
        admitted = admitted,
        exposed = exposed,
        wouldExpose = wouldExpose
    )
    fun recordReflexAdmitted(
        context: Context,
        source: ReflexAdviceSource
    ) = mutate(context) { state ->
        var next = CognitiveInfluencePolicy.recordLayer(
            state,
            CognitiveInfluenceLayer.REFLEX,
            admitted = true
        )
        if (source == ReflexAdviceSource.TINYJEV) {
            next = CognitiveInfluencePolicy.recordLayer(
                next,
                CognitiveInfluenceLayer.TINYJEV,
                admitted = true
            )
        }
        next
    }
    fun recordReflexExposed(
        context: Context,
        taskId: String,
        family: String?,
        option: ReflexOption,
        source: ReflexAdviceSource,
        now: Long = System.currentTimeMillis()
    ) = mutate(context) { state ->
        CognitiveInfluencePolicy.recordReflexExposure(
            state, taskId, family, option, source, now
        )
    }
    fun resolveNextTool(
        context: Context,
        taskId: String,
        request: ToolRequest,
        result: ToolResult
    ) = mutate(context) { state ->
        CognitiveInfluencePolicy.resolveNextTool(
            state, taskId, request, result
        )
    }
    fun resolvePartial(
        context: Context,
        taskId: String
    ) = mutate(context) { state ->
        CognitiveInfluencePolicy.resolvePartial(state, taskId)
    }
    fun recordArm(
        context: Context,
        taskId: String,
        layer: String,
        arm: String,
        now: Long = System.currentTimeMillis()
    ) = mutate(context) { state ->
        CognitiveInfluencePolicy.recordArm(state, taskId, layer, arm, now)
    }
    fun resolveTaskOutcome(
        context: Context,
        taskId: String,
        status: String
    ) = mutate(context) { state ->
        CognitiveInfluencePolicy.resolveTaskOutcome(state, taskId, status)
    }
    private fun mutate(
        context: Context,
        transform: (CognitiveInfluenceState) -> CognitiveInfluenceState
    ): CognitiveInfluenceState = synchronized(lock) {
        val next = transform(load(context))
        save(context, next)
        next
    }
    private fun fresh(versionCode: Long): CognitiveInfluenceState {
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val epoch = CognitiveInfluencePolicy.taskHash(
            UUID.randomUUID().toString() + "|" + now
        ).take(16)
        return CognitiveInfluenceState(
            epoch = epoch,
            since = now,
            versionCode = versionCode
        )
    }
    private fun currentVersionCode(context: Context): Long =
        context.applicationContext.packageManager
            .getPackageInfo(context.applicationContext.packageName, 0)
            .longVersionCode
    private fun save(
        context: Context,
        state: CognitiveInfluenceState
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
    private fun atomicFile(context: Context) = AtomicFile(
        File(context.applicationContext.filesDir, FILE_NAME)
    )
}
