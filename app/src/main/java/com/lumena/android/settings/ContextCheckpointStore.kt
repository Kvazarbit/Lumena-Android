package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.AgentControlState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/** One atomic app-private checkpoint; never auto-replays external effects. */
object ContextCheckpointStore {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(AgentControlState::class.java)
    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, "context_checkpoint.json"))

    @Synchronized fun save(context: Context, state: AgentControlState) {
        val target = file(context)
        val out = target.startWrite()
        try {
            out.write(adapter.toJson(state).toByteArray(Charsets.UTF_8))
            target.finishWrite(out)
        } catch (failure: Exception) {
            target.failWrite(out)
            throw failure
        }
    }

    @Synchronized fun load(context: Context): AgentControlState? {
        val target = file(context)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        return requireNotNull(adapter.fromJson(target.openRead().bufferedReader().use { it.readText() }))
    }
}
