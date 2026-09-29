package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.NervousEvent
import com.lumena.android.agent.core.NervousEventKind
import com.lumena.android.agent.core.NervousSystemPolicy
import com.lumena.android.agent.core.NervousSystemState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

data class NervousSystemStats(
    val totalEvents: Int,
    val incidents: Int,
    val reflexBlocks: Int,
    val promotionEligible: Int
)

object NervousSystemCodec {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(NervousSystemState::class.java)

    fun encode(state: NervousSystemState): String =
        adapter.toJson(state)

    fun decode(raw: String): NervousSystemState {
        val state = requireNotNull(adapter.fromJson(raw)) {
            "Nervous-system state is empty or invalid"
        }
        require(state.version == 1) {
            "Unsupported nervous-system schema " + state.version
        }
        require(state.events.size <= NervousSystemPolicy.MAX_EVENTS) {
            "Nervous-system state exceeds bounded retention"
        }
        return state
    }
}

/**
 * App-private, atomic store for Lumena's self-observation history.
 *
 * This store has no permission state and no direct Constitution write path.
 * Corrupt history fails closed instead of silently resetting self-observation.
 */
object NervousSystemStore {
    private const val FILE_NAME = "lumena_nervous_system_v1.json"
    private val lock = StateVaultLock.monitor

    fun load(context: Context): NervousSystemState = synchronized(lock) {
        val file = atomicFile(context.applicationContext)
        if (
            !file.baseFile.exists() &&
            !File(file.baseFile.path + ".bak").exists()
        ) {
            return@synchronized NervousSystemState()
        }

        try {
            NervousSystemCodec.decode(
                file.openRead().bufferedReader().use { it.readText() }
            )
        } catch (failure: Exception) {
            throw IllegalStateException(
                "Nervous-system history is unreadable; refusing to erase it.",
                failure
            )
        }
    }

    fun save(
        context: Context,
        state: NervousSystemState
    ) = synchronized(lock) {
        require(state.events.size <= NervousSystemPolicy.MAX_EVENTS)
        val file = atomicFile(context.applicationContext)
        val out = file.startWrite()
        try {
            out.write(NervousSystemCodec.encode(state).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (failure: Exception) {
            file.failWrite(out)
            throw failure
        }
    }

    fun record(
        context: Context,
        event: NervousEvent
    ): NervousSystemState = synchronized(lock) {
        val current = load(context.applicationContext)
        val next = NervousSystemPolicy.record(current, event)
        if (next != current) save(context.applicationContext, next)
        next
    }

    fun stats(context: Context): NervousSystemStats = synchronized(lock) {
        val state = load(context.applicationContext)
        NervousSystemStats(
            totalEvents = state.events.size,
            incidents = state.events.count {
                it.kind == NervousEventKind.INCIDENT
            },
            reflexBlocks = state.events.count {
                it.kind == NervousEventKind.REFLEX_BLOCK
            },
            promotionEligible = state.events.count(NervousEvent::promotionEligible)
        )
    }

    private fun atomicFile(context: Context): AtomicFile =
        AtomicFile(File(context.filesDir, FILE_NAME))
}
