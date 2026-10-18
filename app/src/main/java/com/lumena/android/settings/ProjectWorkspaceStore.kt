package com.lumena.android.settings

import android.content.Context
import android.util.AtomicFile
import com.lumena.android.agent.core.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

object ProjectWorkspaceCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(ProjectWorkspaceState::class.java)
    fun encode(state: ProjectWorkspaceState): String {
        ProjectWorkspacePolicy.validate(state)
        return adapter.toJson(state).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }
    fun decode(raw: String): ProjectWorkspaceState {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return requireNotNull(adapter.fromJson(raw)).also(ProjectWorkspacePolicy::validate)
    }
}

object ProjectWorkspaceStore {
    const val FILE_NAME = "lumena_projects_v1.json"
    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
    fun load(context: Context): ProjectWorkspaceState = synchronized(StateVaultLock.monitor) {
        val f = file(context)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return@synchronized ProjectWorkspaceState()
        val bytes = f.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= ProjectWorkspaceCodec.MAX_BYTES) { "Project archive exceeds storage budget" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        ProjectWorkspaceCodec.decode(bytes.toString(Charsets.UTF_8))
    }
    fun update(context: Context, transform: (ProjectWorkspaceState) -> ProjectWorkspaceState): ProjectWorkspaceState =
        synchronized(StateVaultLock.monitor) {
            val current = load(context)
            val next = transform(current)
            if (next != current) {
                val bytes = ProjectWorkspaceCodec.encode(next).toByteArray(Charsets.UTF_8)
                val f = file(context)
                val output = f.startWrite()
                try { output.write(bytes); f.finishWrite(output) }
                catch (failure: Exception) { f.failWrite(output); throw failure }
                StateVault.requestSave(context)
            }
            next
        }
    fun project(context: Context, id: String?): WorkspaceProject? = load(context).projects.firstOrNull { it.id == id }
    fun record(context: Context, task: TaskState, model: String) {
        if (task.projectRoot == null) return
        update(context) { state ->
            val p = state.projects.firstOrNull { it.id == task.projectId } ?: error("Project is missing")
            ProjectWorkspacePolicy.put(state, ProjectWorkspacePolicy.record(p, task, model, System.currentTimeMillis()))
        }
    }
    fun observeRepository(context: Context, task: TaskState, stdout: String) {
        if (task.projectRoot == null) return
        val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
        val data = requireNotNull(adapter.fromJson(stdout))
        require(data["schema"] == "lumena.project.v1" && data["cwd"] == task.projectRoot)
        val git = data["git"] as? Map<*, *>
        val observed = ProjectRepositoryObservation(
            head = git?.get("head") as? String, branch = git?.get("branch") as? String,
            dirty = git?.get("dirty") as? Boolean, sourceDigest = data["source_digest"] as? String ?: "",
            files = (data["files_count"] as? Number)?.toInt() ?: 0,
            truncated = data["truncated"] == true, at = System.currentTimeMillis())
        update(context) { state ->
            val p = state.projects.first { it.id == task.projectId }
            ProjectWorkspacePolicy.put(state, p.copy(repository = observed, updatedAt = observed.at))
        }
    }
}
