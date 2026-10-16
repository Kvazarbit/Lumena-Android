package com.lumena.android.agent.local

import com.lumena.android.agent.core.PythonRunApproval
import com.lumena.android.agent.core.PythonScriptPreview

/**
 * What the app does before any python.run reaches the bridge: check that the
 * bridge advertises the sha256 check, read the script through python.preview
 * and bind its hash into the request. Shared by WorkflowRunner and Companion.
 */
sealed interface PythonRunPreflight {
    /** [request] carries approved_sha256 of [preview]; nothing has run yet. */
    data class Bound(val request: ToolRequest, val preview: PythonScriptPreview) : PythonRunPreflight

    /** The script could not be read; python.run never started. */
    data class NotStarted(val result: ToolResult) : PythonRunPreflight

    /** The owner must act (update or reach the bridge); python.run is not sent. */
    data class Refused(val message: String) : PythonRunPreflight

    companion object {
        /** Null when bridge health lists the sha256 feature, else why python.run is blocked. */
        suspend fun bridgeRefusal(executor: ToolExecutor): String? {
            val health = executor.execute(ToolRequest("health"))
            return when {
                !health.ok ->
                    "python.run заблоковано: не вдалося перевірити Termux bridge (${health.error ?: "health failed"})."
                PythonRunApproval.bridgeSupports(health.stdout) -> null
                else -> PythonRunApproval.BRIDGE_UPDATE_MESSAGE
            }
        }

        suspend fun prepare(executor: ToolExecutor?, request: ToolRequest): PythonRunPreflight {
            val bridge = executor
                ?: return Refused("Bridge token is required for ${request.tool}")
            bridgeRefusal(bridge)?.let { return Refused(it) }
            val result = bridge.execute(
                ToolRequest(PythonRunApproval.PREVIEW_TOOL, PythonRunApproval.previewArgs(request.args))
            )
            if (!result.ok) {
                return NotStarted(
                    ToolResult(
                        ok = false,
                        tool = request.tool,
                        stderr = result.stderr,
                        error = "python.run не запускався: не вдалося прочитати скрипт для підтвердження: " +
                            (result.error ?: "python.preview failed"),
                        errorCode = result.errorCode,
                        failureClass = result.failureClass,
                        retryable = result.retryable,
                        dependency = result.dependency
                    )
                )
            }
            val preview = PythonRunApproval.parsePreview(result.stdout)
                ?: return Refused(
                    "python.run заблоковано: bridge повернув некоректний python.preview. " +
                        PythonRunApproval.BRIDGE_UPDATE_MESSAGE
                )
            return Bound(
                request.copy(args = PythonRunApproval.bind(request.args, preview)),
                preview
            )
        }
    }
}
