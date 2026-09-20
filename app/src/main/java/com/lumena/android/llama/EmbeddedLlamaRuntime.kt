package com.lumena.android.llama

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One process-wide embedded runtime.
 *
 * The GGUF stays loaded between chat turns. Switching modelRef unloads the old model
 * exactly once and loads the newly selected model.
 */
object EmbeddedLlamaRuntime {
    private val gate = Mutex()

    @Volatile private var handle: Long = 0
    @Volatile private var loadedModelRef: String = ""

    suspend fun generate(
        context: Context,
        modelRef: String,
        prompt: String,
        profile: LlamaRuntimeProfile,
        temperature: Float
    ): String = gate.withLock {
        withContext(Dispatchers.IO) {
            ensureLoaded(context.applicationContext, modelRef)
            LlamaNative.nativeGenerate(
                handle = handle,
                prompt = prompt,
                contextSize = profile.contextSize,
                maxTokens = profile.maxTokens,
                temperature = temperature,
                threads = profile.threads,
                batchSize = profile.batchSize
            )
        }
    }

    fun cancelActiveGeneration() {
        LlamaNative.nativeCancel()
    }

    suspend fun unload() = gate.withLock {
        withContext(Dispatchers.IO) {
            val current = handle
            handle = 0
            loadedModelRef = ""
            if (current != 0L) LlamaNative.nativeFreeModel(current)
        }
    }

    private fun ensureLoaded(context: Context, modelRef: String) {
        require(modelRef.isNotBlank()) { "Choose a GGUF model first" }
        if (handle != 0L && loadedModelRef == modelRef) return

        val old = handle
        handle = 0
        loadedModelRef = ""
        if (old != 0L) LlamaNative.nativeFreeModel(old)

        val loaded = if (modelRef.startsWith("content://")) {
            loadContentUri(context, Uri.parse(modelRef))
        } else {
            require(File(modelRef).isFile) { "GGUF model not found: $modelRef" }
            LlamaNative.nativeLoadModel(modelRef, 0)
        }

        check(loaded != 0L) {
            "llama.cpp could not load this GGUF model. Re-select the file or choose a smaller compatible GGUF."
        }
        handle = loaded
        loadedModelRef = modelRef
    }

    private fun loadContentUri(context: Context, uri: Uri): Long {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Android could not open the selected GGUF file")
        return descriptor.use { pfd ->
            check(pfd.fd >= 0) { "Android returned an invalid file descriptor for the GGUF model" }
            LlamaNative.nativeLoadModel("/proc/self/fd/${pfd.fd}", 0)
        }
    }
}
