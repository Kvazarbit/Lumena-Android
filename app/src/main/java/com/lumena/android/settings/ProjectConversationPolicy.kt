package com.lumena.android.settings

import com.lumena.android.agent.core.TaskStatus

/** History restores conversational facts; approval and execution always get a new epoch. */
object ProjectConversationPolicy {
    fun restore(snapshot: LocalSessionSnapshot): LocalSessionSnapshot = snapshot.copy(pending = null,
        task = snapshot.task?.let { task ->
            if (task.status in setOf(TaskStatus.DONE, TaskStatus.PARTIAL, TaskStatus.FAILED, TaskStatus.CANCELLED)) task
            else task.copy(status = TaskStatus.PARTIAL)
        })

    fun retain(branches: List<HistoryBranch>, activeId: String, limit: Int): List<HistoryBranch> {
        require(branches.any { it.id == activeId })
        if (branches.size <= limit) return branches
        val byId = branches.associateBy { it.id }
        val keep = linkedSetOf(activeId)
        branches.filter { it.session.workspaceProjectId != null }.groupBy { it.session.workspaceProjectId }.values
            .forEach { group -> keep += group.maxByOrNull { it.updatedAt }!!.id }
        require(keep.size <= limit) { "Project conversations exceed history capacity; nothing discarded" }
        var cursor = byId[activeId]?.parentBranchId
        while (cursor != null && keep.size < limit) {
            if (!keep.add(cursor)) break
            cursor = byId[cursor]?.parentBranchId
        }
        branches.sortedByDescending { it.updatedAt }.forEach { if (keep.size < limit) keep += it.id }
        return branches.filter { it.id in keep }
    }
}
