package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.LeastActionOutcome
import com.lumena.android.agent.core.LeastActionPolicy
import com.lumena.android.agent.core.LeastActionReport
import com.lumena.android.agent.core.LeastActionState
import com.lumena.android.agent.core.TaskState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * App-private persistence for the least-action ledger.
 *
 * Stores only model id, tool name, ok flag, latency and the model's stated
 * probability. No prompts, arguments, tool output or secrets.
 *
 * Shadow only: nothing here changes which tool runs. A storage failure is
 * swallowed so the ledger can never break a task.
 */
object LeastActionStore {
    private const val FILE_NAME = "lumena_least_action_v1.json"
    private val lock = StateVaultLock.monitor
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(LeastActionState::class.java)

    fun load(context: Context): LeastActionState = synchronized(lock) {
        val file = atomicFile(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return@synchronized LeastActionState()
        }
        val parsed = try {
            adapter.fromJson(file.openRead().bufferedReader().use { it.readText() })
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Least-action ledger is unreadable; refusing to replace prediction history.",
                failure
            )
        }
        requireNotNull(parsed) { "Least-action ledger is empty or corrupt." }
    }

    /** Records one executed action and, once U and E exist, its shadow J. */
    fun record(
        context: Context,
        task: TaskState,
        modelId: String,
        tool: String,
        ok: Boolean,
        elapsedMs: Long,
        predictedOk: Double?,
        now: Long = System.currentTimeMillis()
    ) {
        runCatching {
            synchronized(lock) {
                val before = load(context)
                var next = LeastActionPolicy.record(
                    before,
                    LeastActionOutcome(modelId, tool, ok, elapsedMs, predictedOk, now)
                )
                LeastActionPolicy.shadow(next, modelId, tool, task, ok, now)
                    ?.let { next = LeastActionPolicy.recordShadow(next, it) }
                save(context, next)
            }
        }
    }

    /** Report for the model with the most recent recorded action. */
    fun latestReport(context: Context): LeastActionReport? = synchronized(lock) {
        val state = load(context)
        val modelId = state.outcomes.lastOrNull()?.modelId ?: return@synchronized null
        LeastActionPolicy.report(state, modelId)
    }

    private fun save(context: Context, state: LeastActionState) {
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
