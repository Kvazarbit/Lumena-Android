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

        /**
         * Task grant with allowPython. A grant names scripts, not their
         * content: it runs without a click only a version the owner already
         * saw in the card and ran once in this task ([grantMayProceed]).
         * Any other version goes back to the Run once card, so ChatGPT
         * cannot rewrite a granted script and have it run unseen.
         */
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

    /**
     * Under a task grant: true when Companion may continue without a click.
     * A readable script must be a version already run once in this task,
     * by sha256, the same rule as the chat's task approval cache. An
     * unreadable script or a refused bridge never starts python, so only
     * that error goes back to ChatGPT.
     */
    fun grantMayProceed(check: PythonRunPreflight?, ledger: PythonApprovalLedger, taskKey: String): Boolean =
        when (check) {
            null -> false
            is PythonRunPreflight.Bound -> ledger.approved(taskKey, check.preview.script, check.preview.sha256)
            is PythonRunPreflight.NotStarted, is PythonRunPreflight.Refused -> true
        }

    fun cardText(check: PythonRunPreflight?, previous: PythonScriptPreview?): String = when (check) {
        null -> "Отримую вміст скрипту з bridge…"
        is PythonRunPreflight.Bound -> PythonRunApproval.render(check.preview, previous, TAINT)
        is PythonRunPreflight.NotStarted ->
            check.result.error.orEmpty() + "\nRun once поверне цю помилку в ChatGPT; python.run не запуститься."
        is PythonRunPreflight.Refused -> check.message
    }

    suspend fun execute(executor: ToolExecutor, request: ToolRequest, mode: Mode): ToolResult {
        val check = when (mode) {
            is Mode.Manual -> mode.check
            is Mode.Grant -> mode.check.takeIf { grantMayProceed(it, mode.ledger, mode.taskKey) }
                ?: return notStarted(
                    request,
                    "цю версію скрипту ти ще не запускав через Run once у цій задачі; переглянь її в картці Companion",
                    "PYTHON_RUN_NEEDS_OWNER"
                )
        }
        val bound = when (check) {
            null -> return notStarted(request, "вміст скрипту ще не показано в картці Companion", "PYTHON_RUN_NOT_SHOWN")
            is PythonRunPreflight.NotStarted -> return check.result
            is PythonRunPreflight.Refused -> return refused(request, check.message)
            is PythonRunPreflight.Bound -> {
                // The bridge may have been replaced since the script was read.
                PythonRunPreflight.bridgeRefusal(executor)?.let { return refused(request, it) }
                request.copy(args = PythonRunApproval.bind(request.args, check.preview))
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

    private fun notStarted(request: ToolRequest, why: String, code: String) = ToolResult(
        ok = false,
        tool = request.tool,
        error = "python.run не запускався: $why",
        errorCode = code,
        failureClass = "AUTH_OR_CONFIG",
        retryable = false
    )
}
