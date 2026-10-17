package com.lumena.android.settings

import android.content.Context
import com.lumena.android.agent.core.BridgeCompatibility
import com.lumena.android.agent.core.ModuleRegistry

/**
 * Owner's module switches. A missing preference fails closed for sensitive
 * notification-reading modules. The user can explicitly enable them.
 * Not part of StateVault: restoring an archive cannot silently enable access.
 */
object ModuleSettings {
    private const val PREFS = "lumena_modules"
    private const val KEY_DISABLED = "disabled"
    private const val KEY_BRIDGE_VERSION = "bridge_version"
    val DEFAULT_DISABLED = setOf("listing-attention", "semantic-memory")
    private const val KEY_SEMANTIC_OPT_IN = "semantic_memory_owner_opt_in"

    fun disabled(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_DISABLED, DEFAULT_DISABLED).orEmpty().toSet()
        // Existing installations already persisted their module toggles before
        // this new module existed. Never auto-enable on APK upgrade or restore.
        return effectiveDisabled(stored, prefs.getBoolean(KEY_SEMANTIC_OPT_IN, false))
    }

    internal fun effectiveDisabled(stored: Set<String>, optedIn: Boolean): Set<String> =
        if (optedIn) stored else stored + "semantic-memory"

    /** Owner switches persist; an old bridge version is NOT execution authority. */
    fun apply(context: Context) {
        ModuleRegistry.setDisabled(disabled(context))
        // Even when the last process saw v0.29, an out-of-band Termux update
        // may have downgraded the bridge. Wait for a fresh health observation.
        BridgeCompatibility.invalidateObservation()
    }

    /** Called with the version from each successful bridge `health`. */
    fun rememberBridgeVersion(context: Context, version: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BRIDGE_VERSION, version)
            .apply()
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val next = if (enabled) disabled(context) - id else disabled(context) + id
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putStringSet(KEY_DISABLED, next)
        if (id == "semantic-memory") editor.putBoolean(KEY_SEMANTIC_OPT_IN, enabled)
        editor.apply()
        ModuleRegistry.setDisabled(next)
    }
}
