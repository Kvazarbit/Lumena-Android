package com.lumena.android.collaboration

import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.agent.local.ToolExecutor
import com.lumena.android.agent.local.ToolResult

object CollaborationStateCard {
    const val MAX_CHARS = 1_500
    fun observedSource(result: ToolResult?, root: String): SourceObservation = SourceObservation.UNKNOWN
    suspend fun observe(bridge: ToolExecutor, root: String): SourceObservation = SourceObservation.UNKNOWN
    fun render(entries: List<CollaborationEntry>, project: WorkspaceProject, appSource: String, current: SourceObservation): String? = null
    suspend fun forLocalModel(backend: String, url: String, model: String, ownerAttestsLocal: Boolean, task: TaskState,
                              project: suspend () -> WorkspaceProject?, appSource: String,
                              entries: suspend () -> List<CollaborationEntry>, observe: suspend (String) -> SourceObservation): String? = null
}
