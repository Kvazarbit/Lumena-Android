package com.lumena.android.companion

import android.content.Context

/**
 * Persists only bounded consent metadata. Bridge secrets are never copied here:
 * the grant stores only a SHA-256 connection fingerprint.
 */
object CompanionTaskGrantStore {
    private const val FILE = "lumena_companion_task_grant"
    private const val VERSION = 1

    fun load(
        context: Context,
        bridgeUrl: String,
        bridgeToken: String,
        now: Long
    ): CompanionTaskGrant? {
        val prefs = prefs(context)
        if (prefs.getInt("version", 0) != VERSION) return null
        val grant = CompanionTaskGrant.restore(
            sessionId = prefs.getString("session_id", "").orEmpty(),
            taskId = prefs.getString("task_id", "").orEmpty(),
            modelId = prefs.getString("model_id", "").orEmpty(),
            bridgeFingerprint = prefs.getString("connection_key", "").orEmpty(),
            kindName = prefs.getString("kind", "").orEmpty(),
            patterns = prefs.getString("patterns", "").orEmpty()
                .lines().map(String::trim).filter(String::isNotEmpty),
            expiresAt = prefs.getLong("expires_at", 0L),
            remaining = prefs.getInt("remaining", 0)
        )
        if (grant == null || !grant.isLiveForConnection(bridgeUrl, bridgeToken, now)) {
            clear(context)
            return null
        }
        return grant
    }

    fun save(context: Context, grant: CompanionTaskGrant?) {
        if (grant == null || grant.remaining <= 0) {
            clear(context)
            return
        }
        prefs(context).edit()
            .clear()
            .putInt("version", VERSION)
            .putString("session_id", grant.sessionId)
            .putString("task_id", grant.taskId)
            .putString("model_id", grant.modelId)
            .putString("connection_key", grant.bridgeFingerprint)
            .putString("kind", grant.kind.name)
            .putString("patterns", grant.patterns.joinToString("\n"))
            .putLong("expires_at", grant.expiresAt)
            .putInt("remaining", grant.remaining)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
