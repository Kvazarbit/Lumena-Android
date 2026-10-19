package com.lumena.android.diagnostics

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.lumena.android.agent.core.*
import com.lumena.android.settings.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProjectWorkspaceHistoryTest {
    @Test fun changedGoalCannotRelabelAnAlreadyApprovedTaskOrSendItsTool() = kotlinx.coroutines.runBlocking {
        val c = RuntimeEnvironment.getApplication()
        val p = WorkspaceProject("epoch", "Demo", "demo", "First owner goal", updatedAt = 1)
        ProjectWorkspaceStore.update(c) { ProjectWorkspaceState(projects = listOf(p)) }
        val task = TaskState("old", "epoch", "Approved old step", projectRoot = "demo", projectGoalEpoch = 1)
        ProjectWorkspaceStore.update(c) { it.copy(projects = it.projects.map { project -> project.copy(goal = "New owner goal") }) }
        var calls = 0
        val delegate = object : com.lumena.android.agent.local.ToolExecutor {
            override suspend fun execute(toolRequest: com.lumena.android.agent.local.ToolRequest): com.lumena.android.agent.local.ToolResult {
                calls++; return com.lumena.android.agent.local.ToolResult(true)
            }
        }
        val bound = com.lumena.android.collaboration.CollaborationRuntime.forTask(c, task, "fixture", "", "", delegate)
        assertFalse(bound.execute(com.lumena.android.agent.local.ToolRequest("file.read", mapOf("path" to "demo/a.py"))).ok)
        assertEquals(0, calls)
        assertEquals(2L, ProjectWorkspaceStore.project(c, "epoch")!!.goalEpoch)
    }
    @Test fun importedProjectFlagCannotGrantRemoteContextPermission() {
        val c = RuntimeEnvironment.getApplication()
        val p = WorkspaceProject("privacy", "Private", "demo", "Owner goal", updatedAt = 1, allowRemoteContext = true)
        val restored = ProjectWorkspaceCodec.decode(ProjectWorkspaceCodec.encode(ProjectWorkspaceState(projects = listOf(p)))).projects.single()
        assertFalse(com.lumena.android.collaboration.CollaborationRuntime.remoteAllowed(c, restored))
        com.lumena.android.collaboration.CollaborationRuntime.allowRemoteFromOwner(c, restored, true)
        assertTrue(com.lumena.android.collaboration.CollaborationRuntime.remoteAllowed(c, restored))
        assertFalse(com.lumena.android.collaboration.CollaborationRuntime.remoteAllowed(c, restored.copy(goal = "Changed goal")))
        com.lumena.android.collaboration.CollaborationRuntime.allowRemoteFromOwner(c, restored, false)
        assertFalse(com.lumena.android.collaboration.CollaborationRuntime.remoteAllowed(c, restored))
    }
    @Test fun completedWorkEvictedFromTheCapsuleIsArchivedBeforeActiveCommit() {
        val c = RuntimeEnvironment.getApplication()
        val p = WorkspaceProject("p", "Demo", "demo", "Maintain demo", tasks = (1..128).map {
            ProjectWorkRecord("t$it", "Historical goal $it", TaskStatus.DONE, "old-model", at = it.toLong())
        }, updatedAt = 1)
        ProjectWorkspaceStore.update(c) { ProjectWorkspaceState(projects = listOf(p)) }
        ProjectWorkspaceStore.record(c, TaskState("next", "p", "New goal", projectRoot = "demo", status = TaskStatus.DONE), "new-model")
        assertEquals(128, ProjectWorkspaceStore.project(c, "p")!!.tasks.size)
        val file = requireNotNull(ProjectWorkspaceHistoryArchive.validatedFile(c))
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try { db.rawQuery("SELECT body FROM records WHERE project_id='p'", null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.getString(0).contains("Historical goal 1")); assertFalse(it.moveToNext())
        } } finally { db.close() }
    }
    @Test fun archivePrepareIsIdempotentAndCannotChangeCurrentOwnerGoal() {
        val c = RuntimeEnvironment.getApplication()
        val decision = ProjectDecisionRecord("d", "problem", "choice", "reason", at = 1)
        val old = ProjectWorkspaceState(projects = listOf(WorkspaceProject("p", "Demo", "demo", "Owner goal",
            decisions = listOf(decision), updatedAt = 1)))
        val next = old.copy(projects = old.projects.map { it.copy(decisions = emptyList()) })
        repeat(2) { ProjectWorkspaceHistoryArchive.preserveEvictions(c, old, next) }
        val file = requireNotNull(ProjectWorkspaceHistoryArchive.validatedFile(c))
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try { db.rawQuery("SELECT COUNT(*) FROM records", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) } }
        finally { db.close() }
        assertEquals("Owner goal", old.projects.single().goal)
        assertEquals(1, old.projects.single().decisions.size)
    }
}
