package com.lumena.android.agent.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * App-level lifetime for one active local-agent run.
 *
 * It survives Local/Tools/Companion tab switches and invalidates stale callbacks when
 * the user presses STOP or starts a new task.
 */
class AgentRunCoordinator {
    private val generation = AtomicLong(0)
    private var job: Job? = null

    var taskId by mutableStateOf<String?>(null)
        private set
    var active by mutableStateOf(false)
        private set
    var stage by mutableStateOf("Idle")
        private set
    var startedAtMs by mutableLongStateOf(0L)
        private set
    var liveModelText by mutableStateOf("")
        private set
    var modelTurn by mutableLongStateOf(0L)
        private set
    var liveToolTelemetry by mutableStateOf("")
        private set

    val progress = mutableStateListOf<String>()

    fun launch(
        scope: CoroutineScope,
        taskId: String,
        resetProgress: Boolean = true,
        block: suspend (runToken: Long) -> Unit
    ): Long {
        job?.cancel(CancellationException("Superseded by a new Lumena run"))
        val token = generation.incrementAndGet()
        this.taskId = taskId
        active = true
        startedAtMs = System.currentTimeMillis()
        stage = "Starting…"
        if (resetProgress) progress.clear()
        liveModelText = ""
        modelTurn = 0L
        liveToolTelemetry = ""
        addProgress(token, "Starting task")

        job = scope.launch {
            try {
                block(token)
            } finally {
                if (isCurrent(token, taskId)) {
                    active = false
                    job = null
                }
            }
        }
        return token
    }

    fun isCurrent(runToken: Long, expectedTaskId: String? = taskId): Boolean =
        generation.get() == runToken && taskId == expectedTaskId

    fun addProgress(runToken: Long, message: String) {
        if (!isCurrent(runToken) || message.isBlank()) return
        stage = message.lineSequence().firstOrNull().orEmpty().ifBlank { stage }
        if (progress.lastOrNull() != message) {
            progress += message
            while (progress.size > 40) progress.removeAt(0)
        }
    }

    fun beginModelTurn(runToken: Long) {
        if (!isCurrent(runToken)) return
        modelTurn += 1
        liveModelText = ""
    }

    fun updateModelText(runToken: Long, text: String) {
        if (!isCurrent(runToken)) return
        liveModelText = if (text.length <= 16_000) text else text.takeLast(16_000)
    }

    fun updateToolTelemetry(runToken: Long, text: String) {
        if (!isCurrent(runToken)) return
        liveToolTelemetry = if (text.length <= 8_000) text else text.takeLast(8_000)
    }

    fun pauseForApproval(runToken: Long) {
        if (!isCurrent(runToken)) return
        active = false
        stage = "Waiting for approval"
        job = null
    }

    fun finish(runToken: Long, message: String = "Finished") {
        if (!isCurrent(runToken)) return
        active = false
        stage = message
        job = null
    }

    /** Invalidates callbacks before cancelling the coroutine. */
    fun cancel(reason: String = "Stopped by user"): Boolean {
        val hadWork = active || job?.isActive == true || taskId != null
        generation.incrementAndGet()
        active = false
        stage = reason
        progress += "STOP · $reason"
        while (progress.size > 40) progress.removeAt(0)
        job?.cancel(CancellationException(reason))
        job = null
        taskId = null
        return hadWork
    }

    fun clearFinished() {
        if (active) return
        taskId = null
        stage = "Idle"
        startedAtMs = 0L
        liveModelText = ""
        modelTurn = 0L
        liveToolTelemetry = ""
        progress.clear()
    }
}
