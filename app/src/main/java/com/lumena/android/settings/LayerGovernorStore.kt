package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.AdvisoryLayer
import com.lumena.android.agent.core.LayerGovernorPolicy
import com.lumena.android.agent.core.LayerGovernorReport
import com.lumena.android.agent.core.LayerGovernorState
import com.lumena.android.agent.core.TaskIntentRouter
import com.lumena.android.agent.core.TaskState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * App-private persistence for the Layer Governor experiment.
 *
 * Stores only hashed task ids, layer keys, arms, task family and terminal
 * status. No prompts, advice text, tool output or secrets.
 *
 * Fail-open by design: if the governor cannot read or write its state, the
 * advisory layer is shown exactly as it was before the governor existed.
 */
object LayerGovernorStore {
    private const val FILE_NAME = "lumena_layer_governor_v1.json"
    private val lock = StateVaultLock.monitor
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(LayerGovernorState::class.java)

    fun load(context: Context): LayerGovernorState = synchronized(lock) {
        val file = atomicFile(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return@synchronized LayerGovernorState()
        }
        val parsed = try {
            adapter.fromJson(file.openRead().bufferedReader().use { it.readText() })
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Layer governor state is unreadable; refusing to replace experiment history.",
                failure
            )
        }
        requireNotNull(parsed) { "Layer governor state is empty or corrupt." }
    }

    /**
     * Returns whether this layer's advice may be shown in this task, and
     * records the decision in the task's configuration vector.
     */
    fun expose(
        context: Context,
        task: TaskState,
        layer: AdvisoryLayer,
        modelId: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = runCatching {
        synchronized(lock) {
            val state = load(context)
            val decision = LayerGovernorPolicy.decide(state, task.id, modelId, layer)
            val family = TaskIntentRouter.route(task.goal).intent.name
            val next = LayerGovernorPolicy.begin(state, task.id, modelId, family, decision, now)
            if (next != state) save(context, next)
            // A repeated call within one task must keep the first decision.
            val recorded = next.pending
                .firstOrNull { it.taskHash == LayerGovernorPolicy.taskHash(task.id) }
                ?.exposures
                ?.firstOrNull { it.layer == layer.key }
            recorded?.exposed ?: decision.exposed
        }
    }.getOrDefault(true)

    /** Attributes the terminal status and disables layers that clearly hurt. */
    fun resolve(
        context: Context,
        taskId: String,
        status: String,
        cost: com.lumena.android.agent.core.GovernorCost =
            com.lumena.android.agent.core.GovernorCost(),
        now: Long = System.currentTimeMillis()
    ): com.lumena.android.agent.core.GovernorTrial? =
        runCatching {
            synchronized(lock) {
                val state = load(context)
                val modelId = state.pending
                    .firstOrNull { it.taskHash == LayerGovernorPolicy.taskHash(taskId) }
                    ?.modelId
                    ?: return@synchronized null
                var next = LayerGovernorPolicy.resolve(state, taskId, status, now, cost)
                next = LayerGovernorPolicy.applyVerdicts(next, modelId, now)
                if (next != state) save(context, next)
                next.trials.lastOrNull { it.taskHash == LayerGovernorPolicy.taskHash(taskId) }
            }
        }.getOrNull()

    /** A task replaced or cancelled before a terminal status. */
    fun abandon(
        context: Context,
        taskId: String,
        now: Long = System.currentTimeMillis()
    ) {
        runCatching {
            synchronized(lock) {
                val state = load(context)
                val next = LayerGovernorPolicy.abandon(state, taskId, now)
                if (next != state) save(context, next)
            }
        }
    }

    /** Report for the model with the most recent finished task. */
    fun latestReport(context: Context): LayerGovernorReport? = synchronized(lock) {
        val state = load(context)
        val modelId = state.trials.lastOrNull()?.modelId ?: return@synchronized null
        LayerGovernorPolicy.report(state, modelId)
    }

    fun pendingCount(context: Context): Int = synchronized(lock) { load(context).pending.size }

    private fun save(context: Context, state: LayerGovernorState) {
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

    private fun atomicFile(context: Context) =
        AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
}
