package com.lumena.android

import android.app.Application
import com.lumena.android.settings.StateVault

class LumenaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StateVault.restoreOnStartup(this)
        if (StateVault.startupError == null) StateVault.start(this)
    }
}
