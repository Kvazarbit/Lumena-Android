package com.lumena.android.settings

import android.content.Context
import androidx.work.*
import com.lumena.android.llama.LiteRtImportRefusal
import kotlinx.coroutines.CancellationException

/** Owner-started, bounded to 48 items. SQLite revisions are the resume checkpoint. */
class SemanticIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val NAME = "lumena-semantic-index-v1"
        fun enqueue(context: Context, ollamaUrl: String) {
            val config = SemanticMemorySettings.selection(context)
            val data = workDataOf("backend" to config.backend, "tag" to config.tag,
                "artifact" to config.artifact, "chat" to config.chatEnabled, "url" to ollamaUrl)
            val request = OneTimeWorkRequestBuilder<SemanticIndexWorker>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
        fun stop(context: Context) { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME) }
    }
    override suspend fun doWork(): Result {
        val config = SemanticModelSelection(inputData.getString("backend").orEmpty(),
            inputData.getString("tag").orEmpty(), inputData.getString("artifact"), inputData.getBoolean("chat", true))
        return try {
            val count = SemanticMemoryCoordinator(applicationContext,
                inputData.getString("url") ?: "http://127.0.0.1:11434").index(config) { done, total ->
                setProgress(workDataOf("done" to done, "total" to total))
            }
            Result.success(workDataOf("done" to count))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LiteRtImportRefusal) {
            Result.failure(workDataOf("reason" to failure.safeStatus()))
        } catch (_: LinkageError) {
            Result.failure(workDataOf("reason" to "RUNTIME_UNAVAILABLE"))
        } catch (_: Exception) {
            Result.failure(workDataOf("reason" to "INDEX_INTERRUPTED: перевір модель і продовж; записані пакети збережено."))
        }
    }
}
