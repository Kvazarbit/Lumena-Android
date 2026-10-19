package com.lumena.android.ollama

import com.lumena.android.agent.core.*
import com.lumena.android.agent.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectFilePreconditionsTest {
    private val task = TaskState("t", "p", "Maintain demo", projectRoot = "demo")
    @Test fun onlyCompleteObservedReadsBindTheHashAndWritesInvalidateIt() {
        val read = ToolRequest("file.read", mapOf("path" to "demo/a.py"))
        val r = ToolResult(true, sourceSha256 = "a".repeat(64), sourceComplete = true)
        assertTrue(ProjectFilePreconditions.observe(emptyMap(), read, r.copy(sourceComplete = false)).isEmpty())
        val hashes = ProjectFilePreconditions.observe(emptyMap(), read, r)
        assertEquals("a".repeat(64), hashes["demo/a.py"])
        assertTrue(ProjectFilePreconditions.observe(hashes, ToolRequest("file.patch", read.args), r).isEmpty())
        assertTrue(ProjectFilePreconditions.observe(hashes, ToolRequest("python.tests"), r).isEmpty())
    }
    @Test fun modelHashIsReplacedAndUnknownExistingFileCannotBeBlindlyOverwritten() = runBlocking {
        val sent = mutableListOf<ToolRequest>()
        val delegate = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                sent += toolRequest
                return ToolResult(true, stdout = "features=file_cas_v1\n")
            }
        }
        val write = ToolRequest("file.write", mapOf("path" to "demo/a.py", "content" to "new", "expected_sha256" to "forged"))
        assertTrue(ProjectFilePreconditions.wrap(task, delegate, delegate).execute(write).ok)
        assertEquals("ABSENT", sent.last().args["expected_sha256"])
        val observed = task.copy(kernel = task.kernel.copy(observedFileHashes = mapOf("demo/a.py" to "a".repeat(64))))
        ProjectFilePreconditions.wrap(observed, delegate, delegate).execute(write)
        assertEquals("a".repeat(64), sent.last().args["expected_sha256"])
        val count = sent.size
        assertFalse(ProjectFilePreconditions.wrap(task, delegate, delegate).execute(ToolRequest("file.patch", write.args)).ok)
        assertEquals(count, sent.size)
    }
    @Test fun oldBridgeIsRejectedBeforeWriteAndUnscopedChatKeepsItsContract() = runBlocking {
        val sent = mutableListOf<String>()
        val delegate = object : ToolExecutor {
            override suspend fun execute(toolRequest: ToolRequest): ToolResult {
                sent += toolRequest.tool; return ToolResult(true, stdout = "features=old\n")
            }
        }
        val write = ToolRequest("file.write", mapOf("path" to "demo/new.py", "content" to "new"))
        assertFalse(ProjectFilePreconditions.wrap(task, delegate, delegate).execute(write).ok)
        assertEquals(listOf("health"), sent)
        assertTrue(ProjectFilePreconditions.wrap(task.copy(projectRoot = null), delegate, delegate).execute(write).ok)
        assertEquals("file.write", sent.last())
    }
}
