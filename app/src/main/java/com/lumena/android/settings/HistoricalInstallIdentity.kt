package com.lumena.android.settings

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * Stable per-install provenance token for historical execution records.
 *
 * Stored under noBackupFilesDir so a StateVault/Android restore does not make
 * imported receipts look like they originated from the current installation.
 * The token carries no user/device identifier.
 */
object HistoricalInstallIdentity {
    private const val FILE_NAME =
        "historical-install-ref-v1"
    private val hex64 =
        Regex("[0-9a-f]{64}")

    fun ref(context: Context): String =
        synchronized(
            StateVaultLock.monitor
        ) {
            val file =
                File(
                    context.applicationContext
                        .noBackupFilesDir,
                    FILE_NAME
                )
            val existing =
                runCatching {
                    file.takeIf {
                        it.isFile
                    }
                        ?.readText()
                        ?.trim()
                }.getOrNull()

            if (
                existing != null &&
                hex64.matches(existing)
            ) {
                return@synchronized existing
            }

            val bytes =
                ByteArray(32).also {
                    SecureRandom()
                        .nextBytes(it)
                }
            val fresh =
                bytes.joinToString("") {
                    "%02x".format(
                        it.toInt() and 0xff
                    )
                }

            file.parentFile?.mkdirs()
            val tmp =
                File(
                    file.parentFile,
                    file.name + ".tmp"
                )
            tmp.writeText(fresh)
            if (!tmp.renameTo(file)) {
                file.writeText(fresh)
                tmp.delete()
            }
            fresh
        }
}
