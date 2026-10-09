package com.lumena.android

import android.app.Application
import com.lumena.android.settings.FractalExperienceCanvasStore
import com.lumena.android.settings.ModuleSettings
import com.lumena.android.settings.StateVault

class LumenaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Owner's module switches apply before any service or task can route.
        ModuleSettings.apply(this)
        StateVault.restoreOnStartup(this)
        if (
            StateVault.startupError == null &&
            !StateVault.restoring
        ) {
            // Advisory-only, one-shot migration. A failure leaves the marker
            // unset so the next process start can retry; it never blocks app use.
            runCatching {
                FractalExperienceCanvasStore
                    .backfillFromCoordinator(this)
            }
        }
        if (StateVault.startupError == null) StateVault.start(this)
    }
}
