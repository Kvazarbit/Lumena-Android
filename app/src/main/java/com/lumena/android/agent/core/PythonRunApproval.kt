package com.lumena.android.agent.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/** What python.preview returned: the script bytes the owner approves, named by sha256. */
data class PythonScriptPreview(
    /** Workspace-relative path as the bridge resolved it. */
    val script: String,
    /** sha256 over the raw file bytes, lowercase hex. */
    val sha256: String,
    val sizeBytes: Long,
    /** The start of the file as UTF-8 text; the rest is cut when [truncated]. */
    val content: String,
    val truncated: Boolean
)

/**
 * Script versions the owner already approved, per task and script, so the
 * next dialog for the same script can show what changed. Held in memory by
 * the chat screen and bounded; losing it only means a full view instead of
 * a diff.
 */
data class PythonApprovalLedger(
    private val approved: Map<String, PythonScriptPreview> = emptyMap(),
    private val hashes: Map<String, List<String>> = emptyMap()
) {
    /** The version approved last, for a diff. */
    fun previous(taskId: String, script: String): PythonScriptPreview? =
        approved[key(taskId, script)]

    /** True when the owner already saw and approved exactly these bytes of [script] in this task. */
    fun approved(taskId: String, script: String, sha256: String): Boolean =
        sha256 in hashes[key(taskId, script)].orEmpty()

    fun record(taskId: String, preview: PythonScriptPreview): PythonApprovalLedger {
        val key = key(taskId, preview.script)
        val kept = ((approved - key) + (key to preview)).entries.toList()
            .takeLast(MAX_ENTRIES)
            .associate { it.key to it.value }
        val seen = (hashes[key].orEmpty() - preview.sha256 + preview.sha256).takeLast(MAX_HASHES_PER_SCRIPT)
        return PythonApprovalLedger(kept, (hashes + (key to seen)).filterKeys { it in kept })
    }

    private fun key(taskId: String, script: String): String = "$taskId\u0000$script"

    companion object {
        const val MAX_ENTRIES = 24
        const val MAX_HASHES_PER_SCRIPT = 16
    }
}

/**
 * python.run approval is bound to the script bytes, not to its path. Before
 * the dialog, the app asks the bridge for python.preview and puts its sha256
 * into the request as approved_sha256; the bridge refuses to start python
 * when the file no longer has that hash, and the task approval cache keys on
 * the same args. A bridge that does not advertise [BRIDGE_FEATURE] would
 * ignore the hash, so python.run is not sent to it at all.
 *
 * Not covered: modules the script imports are not hashed, and the bridge's
 * check is not atomic with the start of python. Termux gives no process
 * isolation; this is not a sandbox.
 */
object PythonRunApproval {
    const val TOOL = "python.run"
    const val PREVIEW_TOOL = "python.preview"
    const val HASH_ARG = "approved_sha256"
    const val BRIDGE_FEATURE = "python_run_sha256_v1"
    const val TAINT_LABEL = "Недовірений вміст у задачі"
    const val MAX_CONTENT_CHARS = 6_000
    const val MAX_DIFF_LINES = 160
    private const val DIFF_CONTEXT = 2
    /** LCS table cells; a larger pair of versions is shown in full instead. */
    private const val MAX_DIFF_CELLS = 1_000_000

    const val BRIDGE_UPDATE_MESSAGE =
        "python.run заблоковано: Termux bridge на телефоні не підтримує $BRIDGE_FEATURE " +
            "(запуск лише того вмісту скрипту, який ти бачив у підтвердженні). " +
            "Онови bridge (termux/install_bridge.sh) і перезапусти його."
    const val MISSING_PREVIEW_MESSAGE =
        "Вмісту цього скрипту немає (наприклад, після перезапуску застосунку), тому дозволити його наосліп не можна. " +
            "Скасуй і повтори задачу — Lumena покаже скрипт заново."

    private val sha256Hex = Regex("[0-9a-f]{64}")
    private val featuresLine = Regex("""(?m)^features=(.*)$""")
    private val previewAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun applies(tool: String): Boolean = ToolRegistry.canonicalize(tool) == TOOL

    /** True only when bridge `health` lists the exact feature token. */
    fun bridgeSupports(healthStdout: String): Boolean =
        featuresLine.findAll(healthStdout).any { line ->
            line.groupValues[1].split(',').any { it.trim() == BRIDGE_FEATURE }
        }

    fun previewArgs(args: Map<String, String>): Map<String, String> =
        mapOf("script" to args["script"].orEmpty())

    fun parsePreview(stdout: String): PythonScriptPreview? {
        val fields = runCatching { previewAdapter.fromJson(stdout) }.getOrNull() ?: return null
        val sha256 = (fields["sha256"] as? String)?.takeIf(sha256Hex::matches) ?: return null
        val script = (fields["script"] as? String)?.takeIf(String::isNotBlank) ?: return null
        val size = (fields["size"] as? Number)?.toLong()?.takeIf { it >= 0 } ?: return null
        val content = fields["content"] as? String ?: return null
        val truncated = fields["truncated"] as? Boolean ?: return null
        return PythonScriptPreview(script, sha256, size, content, truncated)
    }

    /** The preview's hash replaces any approved_sha256 the model wrote itself. */
    fun bind(args: Map<String, String>, preview: PythonScriptPreview): Map<String, String> =
        args + (HASH_ARG to preview.sha256)

    fun canApprove(tool: String, preview: PythonScriptPreview?): Boolean =
        !applies(tool) || preview != null

    fun render(
        preview: PythonScriptPreview,
        previous: PythonScriptPreview?,
        taint: TaskTaint
    ): String = buildString {
        appendLine("PYTHON · що саме буде виконано")
        appendLine("Скрипт: ${preview.script}")
        appendLine("Розмір: ${preview.sizeBytes} байт · sha256 ${preview.sha256.take(16)}")
        if (taint.tainted) {
            appendLine("$TAINT_LABEL: ${taint.sources.joinToString(" | ")}")
        }
        appendLine(
            "Дозвіл діє лише для цього вмісту: якщо файл зміниться, bridge відмовить, і Lumena спитає знову. " +
                "Модулі, які скрипт імпортує, не перевіряються."
        )
        val changedFrom = previous?.takeIf {
            it.script == preview.script && it.sha256 != preview.sha256
        }
        val diff = changedFrom?.let { diffBlock(it, preview) }
        if (diff != null) {
            append(diff)
        } else {
            if (previous?.sha256 == preview.sha256) {
                appendLine("Цю саму версію ти вже дозволяв у цій задачі.")
            }
            append(contentBlock(preview))
        }
    }.trimEnd()

    /** Full line diff: "  " unchanged, "- " only in [old], "+ " only in [new]. */
    fun lineDiff(old: String, new: String): List<String> {
        val a = lines(old)
        val b = lines(new)
        if (a.size.toLong() * b.size > MAX_DIFF_CELLS) {
            return a.map { "- $it" } + b.map { "+ $it" }
        }
        val width = b.size + 1
        // lcs[i * width + j] = LCS length of a[i:] and b[j:].
        val lcs = IntArray((a.size + 1) * width)
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i * width + j] =
                    if (a[i] == b[j]) lcs[(i + 1) * width + j + 1] + 1
                    else maxOf(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
            }
        }
        val out = mutableListOf<String>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += "  ${a[i]}"; i++; j++ }
                lcs[(i + 1) * width + j] >= lcs[i * width + j + 1] -> { out += "- ${a[i]}"; i++ }
                else -> { out += "+ ${b[j]}"; j++ }
            }
        }
        while (i < a.size) out += "- ${a[i++]}"
        while (j < b.size) out += "+ ${b[j++]}"
        return out
    }

    private fun contentBlock(preview: PythonScriptPreview): String = buildString {
        val text = visible(preview.content)
        val shown = text.take(MAX_CONTENT_CHARS)
        if (text != preview.content.replace("\r\n", "\n")) appendLine(HIDDEN_CHARS_NOTE)
        appendLine("--- вміст ---")
        appendLine(shown.trimEnd('\n'))
        if (preview.truncated || shown.length < text.length) {
            appendLine(
                "--- обрізано: показано ${shown.length} символів із ${preview.sizeBytes} байт. " +
                    "Дозвіл стосується всього файлу (sha256 вище); решту переглянь через file.read. ---"
            )
        } else {
            appendLine("--- кінець ---")
        }
    }

    private fun diffBlock(previous: PythonScriptPreview, current: PythonScriptPreview): String? {
        val oldText = visible(previous.content)
        val newText = visible(current.content)
        if (lines(oldText).size.toLong() * lines(newText).size > MAX_DIFF_CELLS) return null
        val diff = lineDiff(oldText, newText)
        val changed = diff.indices.filter { !diff[it].startsWith("  ") }
        val keep = changed.flatMap { (it - DIFF_CONTEXT)..(it + DIFF_CONTEXT) }
            .filter { it in diff.indices }
            .toSortedSet()
        return buildString {
            appendLine("Зміни відносно версії, яку ти вже дозволив у цій задачі (sha256 ${previous.sha256.take(16)}):")
            if (previous.truncated || current.truncated) {
                appendLine("Увага: файл довший за попередній перегляд, тож порівнюються лише показані початки версій.")
            }
            if (oldText != previous.content.replace("\r\n", "\n") || newText != current.content.replace("\r\n", "\n")) {
                appendLine(HIDDEN_CHARS_NOTE)
            }
            if (changed.isEmpty()) {
                appendLine(
                    "У показаній частині рядки не змінилися: різниця в кінцях рядків (CRLF/LF) " +
                        "або за межею попереднього перегляду. sha256 файлу інший."
                )
            }
            var shown = 0
            var last = -1
            for (index in keep) {
                if (shown == MAX_DIFF_LINES) {
                    appendLine("… ще ${keep.size - shown} рядків diff не показано")
                    break
                }
                if (last >= 0 && index > last + 1) appendLine("  …")
                appendLine(diff[index])
                last = index
                shown++
            }
        }
    }

    private const val HIDDEN_CHARS_NOTE =
        "Увага: у скрипті є невидимі або керуючі символи; вони показані як \\uXXXX."

    private fun lines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

    /**
     * Control, bidi and other invisible format characters are shown as
     * \uXXXX so the dialog cannot display different code than the file has.
     */
    private fun visible(text: String): String = buildString {
        val normalized = text.replace("\r\n", "\n")
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            val hidden = codePoint != '\n'.code && codePoint != '\t'.code && (
                Character.isISOControl(codePoint) ||
                    Character.getType(codePoint) == Character.FORMAT.toInt() ||
                    Character.getType(codePoint) == Character.LINE_SEPARATOR.toInt() ||
                    Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR.toInt()
                )
            if (hidden) append("\\u%04X".format(codePoint)) else appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
    }
}
