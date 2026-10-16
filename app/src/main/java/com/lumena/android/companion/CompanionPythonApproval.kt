package com.lumena.android.companion

import com.lumena.android.agent.core.PythonApprovalLedger
import com.lumena.android.agent.core.PythonRunApproval
import com.lumena.android.agent.core.PythonScriptPreview
import com.lumena.android.agent.core.TaskTaint
import com.lumena.android.agent.local.PythonRunPreflight
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult

/**
 * python.run from Companion. The command text was written by ChatGPT, so the
 * app reads the script itself (python.preview) and binds that hash; an
 * approved_sha256 inside the incoming command is always overwritten.
 */
object CompanionPythonApproval {
    const val TAINT_SOURCE = "Companion · запит python.run написав ChatGPT, а не ти"
    val TAINT = TaskTaint(sources = listOf(TAINT_SOURCE))

    sealed interface Mode {
        /** Run once: only the version shown in the card ([check] from [PythonRunPreflight.prepare]). */
        data class Manual(val check: PythonRunPreflight?) : Mode

        /** Red-test stub: still runs the current file; the seen-version rule lands next. */
        data class Grant(
            val check: PythonRunPreflight?,
            val ledger: PythonApprovalLedger,
            val taskKey: String
        ) : Mode
    }

    /** Ledger key for one Companion task (session + task), or for a single command. */
    fun taskKey(command: CompanionCommand): String =
        if (command.taskId.isNullOrBlank()) "command:${command.fingerprint}"
        else "task:${command.sessionId.orEmpty()}\u0000${command.taskId}"

    /** Red-test stub. */
    fun grantMayProceed(check: PythonRunPreflight?, ledger: PythonApprovalLedger, taskKey: String): Boolean =
        check != null

    fun cardText(check: PythonRunPreflight?, previous: PythonScriptPreview?): String = when (check) {
        null -> "Отримую вміст скрипту з bridge…"
        is PythonRunPreflight.Bound -> PythonRunApproval.render(check.preview, previous, TAINT)
        is PythonRunPreflight.NotStarted ->
            check.result.error.orEmpty() + "\nRun once поверне цю помилку в ChatGPT; python.run не запуститься."
        is PythonRunPreflight.Refused -> check.message
    }

    suspend fun execute(executor: ToolExecutor, request: ToolRequest, mode: Mode): ToolResult {
        val bound = when (mode) {
            is Mode.Manual -> when (val check = mode.check) {
                null -> return notStarted(request, "вміст скрипту ще не показано в картці Companion")
                is PythonRunPreflight.NotStarted -> return check.result
                is PythonRunPreflight.Refused -> return refused(request, check.message)
                is PythonRunPreflight.Bound -> {
                    // The bridge may have been replaced since the card was shown.
                    PythonRunPreflight.bridgeRefusal(executor)?.let { return refused(request, it) }
                    request.copy(args = PythonRunApproval.bind(request.args, check.preview))
                }
            }
            is Mode.Grant -> when (val prepared = PythonRunPreflight.prepare(executor, request)) {
                is PythonRunPreflight.Bound -> prepared.request
                is PythonRunPreflight.NotStarted -> return prepared.result
                is PythonRunPreflight.Refused -> return refused(request, prepared.message)
            }
        }
        return executor.execute(bound)
    }

    private fun refused(request: ToolRequest, message: String) = ToolResult(
        ok = false,
        tool = request.tool,
        error = message,
        errorCode = "PYTHON_RUN_REFUSED",
        failureClass = "AUTH_OR_CONFIG",
        retryable = false,
        dependency = "termux_bridge"
    )

    private fun notStarted(request: ToolRequest, why: String) = ToolResult(
        ok = false,
        tool = request.tool,
        error = "python.run не запускався: $why",
        errorCode = "PYTHON_RUN_NOT_SHOWN",
        failureClass = "AUTH_OR_CONFIG",
        retryable = false
    )
}
