package com.lumena.android.llama

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.BackendType
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Owner-only SAF import of a LiteRT-LM text embedding bundle.
 *
 * Never reads Google AI Edge Gallery's sandbox or converts existing Ollama
 * GGUF/safetensors. Large weights live outside StateVault's 64 MiB archive.
 * Module opt-in is separately stored and restores disabled after StateVault.
 */
object SemanticLiteRtModelStore {
    const val SDK_VERSION = "0.18.0"
    const val MAX_FILE_BYTES = 1_200_000_000L
    const val MIN_FILE_BYTES = 1_000_000L
    private const val PREFS = "lumena_litert_semantic_model"
    private const val CURRENT_DIGEST = "sha256"
    private const val MODEL_DIR = "semantic-litert-models"

    data class Imported(
        val path: String,
        val sha256: String,
        val bytes: Long,
        val dimension: Int,
        /** True only if CPU was declared; an empty metadata list is UNKNOWN. */
        val cpuSupported: Boolean
    )

    /** This is NOT a user-approved permission or a readiness/inference claim. */
    fun selected(context: Context): File? {
        val digest = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CURRENT_DIGEST, null) ?: return null
        if (!digest.matches(Regex("[0-9a-f]{64}"))) return null
        val root = File(context.applicationContext.filesDir, MODEL_DIR).canonicalFile
        val file = File(root, "embedding-$digest.litertlm").canonicalFile
        if (file.parentFile != root || !file.isFile ||
            file.length() !in MIN_FILE_BYTES..MAX_FILE_BYTES) return null
        return file
    }

    fun sha256(file: File): String {
        require(file.isFile && file.length() in MIN_FILE_BYTES..MAX_FILE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Uses the real pinned Google SDK ModelInfo metadata API. Neither a file
     * extension nor an installed tag can prove CPU inference will work.
     */
    fun inspect(path: String): Pair<Int, Boolean> = try {
        ModelInfo.from(path).use { model ->
            if (model !is ModelInfo.Embedding) {
                throw LiteRtImportRefusal(LiteRtImportCode.NOT_EMBEDDING)
            }
            val inputs = model.inputModalities()
            val backends = model.supportedBackends(Modality.TEXT)
            val checked = LiteRtTextImportPolicy.validate(
                LiteRtTextImportPolicy.Capabilities(
                    isEmbedding = true,
                    text = inputs.text, vision = inputs.vision,
                    audio = inputs.audio, video = inputs.video,
                    dimension = model.dimension(),
                    cpuDeclared = if (backends.isEmpty()) null else BackendType.CPU in backends
                )
            )
            checked.dimension to checked.cpuDeclared
        }
    } catch (failure: LiteRtImportRefusal) {
        throw failure
    } catch (failure: Exception) {
        // Native errors may include app-private paths; the UI only sees a fixed code.
        throw LiteRtImportRefusal(LiteRtImportCode.METADATA_UNAVAILABLE, failure)
    }

    suspend fun importFromSaf(context: Context, uri: Uri): Imported =
        withContext(Dispatchers.IO) {
            if (uri.scheme != "content") throw LiteRtImportRefusal(LiteRtImportCode.BAD_URI)
            val app = context.applicationContext
            val resolver = app.contentResolver
            val displayName = try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
                    if (rows.moveToFirst()) rows.getString(0) else null
                }
            } catch (failure: Exception) {
                throw LiteRtImportRefusal(LiteRtImportCode.PICKER_FAILURE, failure)
            } ?: throw LiteRtImportRefusal(LiteRtImportCode.NO_FILENAME)
            if (!displayName.lowercase(java.util.Locale.ROOT).endsWith(".litertlm")) {
                throw LiteRtImportRefusal(LiteRtImportCode.BAD_EXTENSION)
            }
            val root = File(app.filesDir, MODEL_DIR)
            if (!root.isDirectory && !root.mkdirs()) {
                throw LiteRtImportRefusal(LiteRtImportCode.STORAGE_FAILURE)
            }
            val staging = try {
                File.createTempFile("embedding-import-", ".litertlm", root)
            } catch (failure: java.io.IOException) {
                throw LiteRtImportRefusal(LiteRtImportCode.STORAGE_FAILURE, failure)
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        staging.outputStream().buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                copied += n
                                if (copied > MAX_FILE_BYTES) {
                                    throw LiteRtImportRefusal(LiteRtImportCode.TOO_LARGE)
                                }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                            output.flush()
                        }
                    } ?: throw LiteRtImportRefusal(LiteRtImportCode.SOURCE_READ_FAILURE)
                } catch (refusal: LiteRtImportRefusal) {
                    throw refusal
                } catch (failure: Exception) {
                    throw LiteRtImportRefusal(LiteRtImportCode.SOURCE_READ_FAILURE, failure)
                }
                if (copied < MIN_FILE_BYTES) throw LiteRtImportRefusal(LiteRtImportCode.TOO_SMALL)
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                // Native metadata proof only; CPU readiness still requires a real vector.
                val (dim, cpuDeclared) = inspect(staging.absolutePath)
                val destination = File(root, "embedding-$sha.litertlm")
                if (destination.exists()) {
                    if (destination.length() != copied || sha256(destination) != sha) {
                        throw LiteRtImportRefusal(LiteRtImportCode.CONFLICTING_DESTINATION)
                    }
                } else if (!staging.renameTo(destination)) {
                    throw LiteRtImportRefusal(LiteRtImportCode.COMMIT_FAILED)
                }
                if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(CURRENT_DIGEST, sha).commit()) {
                    throw LiteRtImportRefusal(LiteRtImportCode.COMMIT_FAILED)
                }
                Imported(destination.absolutePath, sha, copied, dim, cpuDeclared)
            } finally {
                // Keep both source and previous owner-selected models unchanged on error.
                staging.delete()
            }
        }
}
