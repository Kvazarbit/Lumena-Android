package com.lumena.android.agent.local

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Starts the already-installed Lumena Python bridge inside Termux on demand.
 *
 * Security properties:
 * - only the fixed ~/.lumena/bridge.py path can be started
 * - bridge itself stays loopback-only
 * - no arbitrary shell command is exposed through this launcher
 */
object TermuxBridgeAutoStarter {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val PYTHON = "$TERMUX_PREFIX/bin/python"
    private const val BRIDGE_SCRIPT = "$TERMUX_HOME/.lumena/bridge.py"

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(450, TimeUnit.MILLISECONDS)
        .readTimeout(700, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
        .build()

    suspend fun ensureRunning(context: Context, base: HttpUrl): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "::1")) {
                "Bridge auto-start is allowed only for loopback URLs"
            }

            if (isAlive(base)) return@runCatching

            val app = context.applicationContext
            val installed = runCatching {
                app.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            }.isSuccess
            check(installed) { "Termux is not installed" }

            if (app.checkSelfPermission(RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                withContext(Dispatchers.Main) {
                    (context as? Activity)?.requestPermissions(
                        arrayOf(RUN_COMMAND_PERMISSION),
                        12012
                    )
                }
                throw IllegalStateException(
                    "Allow Lumena the 'Run commands in Termux' permission, then retry."
                )
            }

            val intent = Intent(RUN_COMMAND_ACTION).apply {
                component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_PATH, PYTHON)
                putExtra(EXTRA_ARGUMENTS, arrayOf(BRIDGE_SCRIPT))
                putExtra(EXTRA_WORKDIR, TERMUX_HOME)
                putExtra(EXTRA_BACKGROUND, true)
            }

            val started = runCatching { app.startService(intent) }.getOrNull()
            check(started != null) {
                "Termux refused RUN_COMMAND. In Termux enable allow-external-apps=true and reload settings."
            }

            repeat(24) {
                delay(250)
                if (isAlive(base)) return@runCatching
            }
            error("Lumena bridge did not become ready. Run termux/install_bridge.sh once in Termux.")
        }
    }

    private fun isAlive(base: HttpUrl): Boolean {
        val request = Request.Builder().url(base).get().build()
        return runCatching {
            probeClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)
    }
}
