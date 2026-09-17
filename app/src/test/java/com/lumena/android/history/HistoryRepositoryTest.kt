package com.lumena.android.history

import android.content.Context
import com.lumena.android.agent.core.*
import com.lumena.android.ollama.OllamaMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HistoryRepositoryTest {
    @Test fun historyMigrationAndTaskIsolationWorkInSqlite() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("lumena_local_session", Context.MODE_PRIVATE).edit()
            .putString("snapshot_json", """{"chat":[{"role":"user","text":"Legacy goal"}],"history":[{"role":"user","content":"Legacy goal"}],"inputDraft":"old draft"}""").commit()
        val repository = HistoryRepository(context)
        repository.initialize()
        val original = repository.load(repository.catalog().selectedId!!)
        assertEquals("Legacy goal", original.payload.chat.single().text)
        assertEquals("old draft", original.payload.draft)
        assertTrue(context.getSharedPreferences("lumena_local_session", Context.MODE_PRIVATE).contains("snapshot_json"))

        val topic = repository.createTopic("Python")
        val other = repository.newConversation(topic.id, "another-model")
        assertTrue(other.payload.history.isEmpty())
        assertEquals("another-model", other.meta.model)
        assertEquals("old draft", repository.load(original.meta.id).payload.draft)

        val task = TaskState("taskA", null, "Check A", TaskStatus.WAITING_MODEL)
        repository.updatePayload(original.meta.id) { it.copy(control = AgentControlState(task)) }
        repository.select(other.meta.id)
        repository.updatePayload(original.meta.id, "taskA") { it.copy(chat = it.chat + ChatEntry("assistant", "Only A result")) }
        assertTrue(repository.load(original.meta.id).payload.chat.any { it.text == "Only A result" })
        assertTrue(repository.load(other.meta.id).payload.chat.isEmpty())
        assertEquals(other.meta.id, repository.catalog().selectedId)
        assertNull(repository.updatePayload(original.meta.id, "wrong-task") { it.copy(draft = "stale callback") })
        assertEquals("old draft", repository.load(original.meta.id).payload.draft)

        repository.updatePayload(other.meta.id) { it.copy(draft = "B draft") }
        repository.rename(other.meta.id, "My Python topic chat")
        assertEquals("B draft", repository.load(other.meta.id).payload.draft)
        assertEquals("My Python topic chat", repository.load(other.meta.id).meta.title)

        repository.updatePayload(original.meta.id, "taskA") { it.copy(control = it.control!!.copy(task = task.copy(status = TaskStatus.EXECUTING)), executionUncertain = true) }
        repository.initialize()
        assertEquals(2, repository.catalog().conversations.size)
        assertEquals(TaskStatus.FAILED, repository.load(original.meta.id).payload.control!!.task.status)
        assertTrue(repository.load(original.meta.id).payload.executionUncertain)
        assertEquals("B draft", repository.load(other.meta.id).payload.draft)
    }

    @Test fun persistedForkHasIndependentSnapshotAndMoveKeepsChildrenTogether() = runBlocking {
        val repository = HistoryRepository(RuntimeEnvironment.getApplication())
        repository.initialize()
        val source = repository.load(repository.catalog().selectedId!!)
        val messages = listOf(ChatEntry("user", "first", "u"), ChatEntry("assistant", "answer", "a"), ChatEntry("user", "later", "u2"))
        repository.updatePayload(source.meta.id) { it.copy(chat = messages,
            history = listOf(OllamaMessage("user", "first"), OllamaMessage("assistant", "answer"), OllamaMessage("user", "later")),
            tasks = listOf(TaskMark("checkpoint", "first", TaskStatus.DONE, "a", chatEnd = 2, historyEnd = 2))) }
        val branch = repository.fork(source.meta.id, "checkpoint")
        assertEquals(2, repository.load(branch.meta.id).payload.history.size)
        assertNull(branch.payload.pending)
        assertNull(branch.payload.control)
        repository.updatePayload(branch.meta.id) { it.copy(draft = "branch draft") }
        assertEquals("", repository.load(source.meta.id).payload.draft)
        val topic = repository.createTopic("Moved")
        repository.move(source.meta.id, topic.id)
        assertEquals(topic.id, repository.load(branch.meta.id).meta.topicId)
        assertEquals(source.meta.id, repository.load(branch.meta.id).meta.parentId)
        assertEquals(3, repository.load(source.meta.id).payload.history.size)
    }
}
