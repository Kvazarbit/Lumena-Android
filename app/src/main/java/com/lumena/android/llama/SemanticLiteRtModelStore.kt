package com.lumena.android.llama

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.BackendType
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.lumena.android.settings.SemanticMemoryPolicy
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
    fun inspect(path: String): Pair<Int, Boolean> =
        ModelInfo.from(path).use { model ->
            require(model is ModelInfo.Embedding) {
                "The selected LiteRT-LM file is not an embedding model"
            }
            val inputs = model.inputModalities()
            require(inputs.text && !inputs.vision && !inputs.audio && !inputs.video) {
                "Select the text-only EmbeddingGemma 2 270M LiteRT-LM artifact"
            }
            val dim = model.dimension()
                ?: throw IllegalStateException("Embedding dimension is missing from model metadata")
            require(dim in 2..SemanticMemoryPolicy.MAX_DIM) { "Invalid embedding dimension" }
            val backends = model.supportedBackends(Modality.TEXT)
            require(backends.isEmpty() || BackendType.CPU in backends) {
                "The model does not declare a CPU text backend"
            }
            dim to (backends.isEmpty() || BackendType.CPU in backends)
        }

    suspend fun importFromSaf(context: Context, uri: Uri): Imported =
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content") { "Select a file through Android's document picker" }
            val app = context.applicationContext
            val resolver = app.contentResolver
            val displayName = resolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { rows ->
                if (rows.moveToFirst()) rows.getString(0) else null
            } ?: throw IllegalArgumentException("Cannot determine the selected filename")
            require(displayName.lowercase().endsWith(".litertlm")) {
                "Only a LiteRT-LM .litertlm model bundle is accepted"
            }
            val root = File(app.filesDir, MODEL_DIR)
            check(root.isDirectory || root.mkdirs()) { "Cannot create model directory" }
            val staging = File.createTempFile("embedding-import-", ".litertlm", root)
            var committed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                resolver.openInputStream(uri)?.use { input ->
                    staging.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            require(copied <= MAX_FILE_BYTES) { "Model exceeds 1.2 GB import limit" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                        output.flush()
                    }
                } ?: throw IllegalArgumentException("The selected document cannot be opened")
                require(copied >= MIN_FILE_BYTES) { "Model bundle is too small" }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                // Real native ModelInfo validation BEFORE changing selected model.
                val (dim, cpu) = inspect(staging.absolutePath)
                val destination = File(root, "embedding-$sha.litertlm")
                if (destination.exists()) {
                    require(destination.length() == copied && sha256(destination) == sha) {
                        "Existing model digest collision or changed bytes"
                    }
                } else {
                    require(staging.renameTo(destination)) { "Cannot commit imported model atomically" }
                }
                require(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(CURRENT_DIGEST, sha).commit()) {
                    "Cannot save current model selection"
                }
                committed = true
                Imported(destination.absolutePath, sha, copied, dim, cpu)
            } finally {
                // Never delete an earlier valid model or touch the original SAF file.
                staging.delete()
                if (!committed) { /* keep the previous selected file intact */ }
            }
        }
}
