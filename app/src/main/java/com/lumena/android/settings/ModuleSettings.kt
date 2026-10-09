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
    val DEFAULT_DISABLED = setOf("listing-attention")

    fun disabled(context: Context): Set<String> =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DISABLED, DEFAULT_DISABLED)
            .orEmpty()
            .toSet()

    /** Applies the stored switches and the last seen bridge version to the running kernel. */
    fun apply(context: Context) {
        ModuleRegistry.setDisabled(disabled(context))
        BridgeCompatibility.observe(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BRIDGE_VERSION, null)
        )
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
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DISABLED, next)
            .apply()
        ModuleRegistry.setDisabled(next)
    }
}
