package com.lumena.android.settings

import android.content.Context

/**
 * Separate, opt-in diagnostic capture for OLX notification parsing.
 * Never restored by StateVault; loss of preferences turns capture OFF.
 * The core listing scorer can run without storing original push text.
 */
object ListingCaptureSettings {
    private const val PREFS = "lumena_listing_privacy"
    private const val RAW_CAPTURE = "raw_capture"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(RAW_CAPTURE, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RAW_CAPTURE, enabled)
            .apply()
    }
}
