package com.lumena.android.agent.local

import com.lumena.android.agent.core.TaskState
import kotlinx.coroutines.CancellationException

/** Adds app-owned preconditions after approval; never expands the approved operation. */
object ProjectFilePreconditions {
    fun observe(hashes: Map<String, String>, request: ToolRequest, result: ToolResult): Map<String, String> {
        val path = request.args["path"] ?: ""
        return when {
            request.tool in setOf("python.run", "python.tests") -> emptyMap()
            request.tool in setOf("file.write", "file.patch") -> hashes - path
            request.tool == "file.read" && result.ok &&
                result.sourceSha256?.matches(Regex("[a-f0-9]{64}")) == true ->
                (hashes - path).entries.toList().takeLast(47).associate { it.key to it.value } + (path to requireNotNull(result.sourceSha256))
            else -> hashes
        }
    }
    fun completeReads(paths: Set<String>, request: ToolRequest, result: ToolResult): Set<String> {
        val path = request.args["path"].orEmpty()
        return when (request.tool) {
            "python.run", "python.tests" -> emptySet()
            "file.write", "file.patch" -> paths - path
            "file.read" -> if (result.ok && result.sourceComplete && result.sourceSha256?.matches(Regex("[a-f0-9]{64}")) == true)
                ((paths - path).toList().takeLast(47) + path).toSet() else paths - path
            else -> paths
        }
    }
    fun wrap(task: TaskState, journal: ToolExecutor, transport: ToolExecutor): ToolExecutor {
        if (task.projectRoot == null) return journal
        return object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                if (toolRequest.tool !in setOf("file.write", "file.patch")) return journal.execute(toolRequest)
                val path = toolRequest.args["path"].orEmpty()
                val expected = task.kernel.observedFileHashes[path] ?: "ABSENT"
                if (toolRequest.tool == "file.patch" && expected == "ABSENT") return hold("Спочатку прочитай потрібну частину файла для patch.")
                if (toolRequest.tool == "file.write" && expected != "ABSENT" && path !in task.kernel.completeFileReads)
                    return hold("Для повного перезапису прочитай файл повністю або використай точковий patch.")
                val health = try { transport.execute(ToolRequest("health")) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { return hold("Не вдалося перевірити підтримку безпечного запису bridge.") }
                val features = health.stdout.lineSequence().firstOrNull { it.startsWith("features=") }
                    ?.removePrefix("features=")?.split(',').orEmpty()
                if (!health.ok || "file_cas_v1" !in features) return hold("Онови міст Termux: потрібна підтримка file_cas_v1.")
                return journal.execute(toolRequest.copy(args = toolRequest.args + ("expected_sha256" to expected)))
            }
        }
    }
    private fun hold(reason: String) = ToolResult(false, error = reason,
        errorCode = "PROJECT_FILE_PRECONDITION", retryable = false)
}
