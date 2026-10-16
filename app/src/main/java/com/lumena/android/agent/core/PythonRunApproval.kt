package com.lumena.android.agent.core

/** Red-test stub: content-bound python.run approval lands in the next commit. */
data class PythonScriptPreview(
    val script: String,
    val sha256: String,
    val sizeBytes: Long,
    val content: String,
    val truncated: Boolean
)

data class PythonApprovalLedger(
    private val approved: Map<String, PythonScriptPreview> = emptyMap()
) {
    fun previous(taskId: String, script: String): PythonScriptPreview? = null

    fun record(taskId: String, preview: PythonScriptPreview): PythonApprovalLedger = this
}

object PythonRunApproval {
    const val TOOL = "python.run"
    const val PREVIEW_TOOL = "python.preview"
    const val HASH_ARG = "approved_sha256"
    const val BRIDGE_FEATURE = "python_run_sha256_v1"
    const val TAINT_LABEL = "Недовірений вміст у задачі"
    const val MAX_CONTENT_CHARS = 6_000
    const val MAX_DIFF_LINES = 160

    val BRIDGE_UPDATE_MESSAGE = ""
    val MISSING_PREVIEW_MESSAGE = ""

    fun applies(tool: String): Boolean = false

    fun bridgeSupports(healthStdout: String): Boolean = true

    fun previewArgs(args: Map<String, String>): Map<String, String> = args

    fun parsePreview(stdout: String): PythonScriptPreview? = null

    fun bind(args: Map<String, String>, preview: PythonScriptPreview): Map<String, String> = args

    fun canApprove(tool: String, preview: PythonScriptPreview?): Boolean = true

    fun render(
        preview: PythonScriptPreview,
        previous: PythonScriptPreview?,
        taint: TaskTaint
    ): String = ""

    fun lineDiff(old: String, new: String): List<String> = emptyList()
}
