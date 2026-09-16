package com.lumena.android.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** GGUF files imported into Lumena's private model directory. */
data class EmbeddedModelFile(
    val name: String,
    val path: String,
    val sizeBytes: Long
)

class EmbeddedModelStore(private val context: Context) {
    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    suspend fun listModels(): List<EmbeddedModelFile> = withContext(Dispatchers.IO) {
        modelsDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { EmbeddedModelFile(it.name, it.absolutePath, it.length()) }
            .toList()
    }

    suspend fun importModel(uri: Uri): Result<EmbeddedModelFile> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri)
                ?.takeIf { it.isNotBlank() }
                ?: "model-${System.currentTimeMillis()}.gguf"
            require(displayName.lowercase().endsWith(".gguf")) {
                "Selected file is not a .gguf model"
            }

            val safeName = sanitizeFileName(displayName)
            val target = File(modelsDir, safeName)
            val temp = File(modelsDir, "$safeName.part")

            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open selected model" }
                temp.outputStream().buffered(1024 * 1024).use { output ->
                    input.copyTo(output, 1024 * 1024)
                }
            }

            if (target.exists() && !target.delete()) {
                error("Unable to replace existing model: ${target.name}")
            }
            require(temp.renameTo(target)) { "Unable to finalize imported model" }
            EmbeddedModelFile(target.name, target.absolutePath, target.length())
        }.onFailure {
            modelsDir.listFiles { file -> file.name.endsWith(".part") }
                ?.forEach { file -> file.delete() }
        }
    }

    suspend fun delete(model: EmbeddedModelFile): Boolean = withContext(Dispatchers.IO) {
        val file = File(model.path)
        file.parentFile?.canonicalFile == modelsDir.canonicalFile && file.delete()
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._() -]"), "_")
            .trim()
            .take(180)
        return sanitized.ifBlank { "model-${System.currentTimeMillis()}.gguf" }
    }
}

fun Long.humanFileSize(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}
