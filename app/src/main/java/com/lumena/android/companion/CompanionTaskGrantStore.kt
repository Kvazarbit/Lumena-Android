package com.lumena.android.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Device-local authority: excluded from Android backup and the portable memory vault. */
object CompanionTaskGrantStore {
    data class State(val grant: CompanionTaskGrant? = null, val handled: String? = null, val pending: String? = null) {
        fun recovered() = if (pending == null) this else copy(grant = grant?.copy(paused = true), handled = pending, pending = null)
    }
    private fun file(c: Context) = AtomicFile(File(c.noBackupFilesDir, "companion-consent.json"))
    private fun read(c: Context): State = runCatching {
        val j = JSONObject(file(c).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val g = j.optJSONObject("grant")
        State(g?.let { CompanionTaskGrant.restore(it.keys().asSequence().associateWith { k -> it.getString(k) }) },
            j.optString("handled").takeIf { it.isNotEmpty() }, j.optString("pending").takeIf { it.isNotEmpty() })
    }.getOrDefault(State())
    private fun write(c: Context, state: State) {
        val j = JSONObject()
        state.grant?.let { j.put("grant", JSONObject(it.snapshot())) }
        state.handled?.let { j.put("handled", it) }
        state.pending?.let { j.put("pending", it) }
        val f = file(c)
        val stream = f.startWrite()
        try { stream.write(j.toString().toByteArray(Charsets.UTF_8)); f.finishWrite(stream) }
        catch (e: Exception) { f.failWrite(stream); throw e }
    }
    @Synchronized fun recover(c: Context): State = read(c).recovered().also { write(c, it) }
    @Synchronized fun setGrant(c: Context, grant: CompanionTaskGrant?) = write(c, read(c).copy(grant = grant))
    @Synchronized fun begin(c: Context, fingerprint: String) = write(c, read(c).copy(pending = fingerprint))
    @Synchronized fun finish(c: Context, fingerprint: String, grant: CompanionTaskGrant?) =
        write(c, State(grant, fingerprint))
    @Synchronized fun clear(c: Context) = file(c).delete()
}
