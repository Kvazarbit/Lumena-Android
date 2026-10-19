package com.lumena.android.collaboration

import android.content.Context
import android.os.Build
import com.lumena.android.BuildConfig
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.WorkspaceProject
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Measurement metadata only; experience remains a derived view of the existing receipt journal. */
object RepairLabStore {
    private val monitor = Any()
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(RepairLabState::class.java).failOnUnknown()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS).build()
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("lumena_collaboration", Context.MODE_PRIVATE)
    private fun key(project: String) = "repair_lab_$project"
    fun load(c: Context, project: String): RepairLabState? = synchronized(monitor) {
        prefs(c).getString(key(project), null)?.also { require(it.length <= 256_000) }?.let(adapter::fromJson)?.also {
            require(it.rows.size <= 256 && it.config.library.size <= RepairCasePolicy.MAX_CASES)
            require(it.config.modelDigest.matches(Regex("[a-f0-9]{64}")))
        }
    }
    private fun save(c: Context, state: RepairLabState) {
        val json = adapter.toJson(state); require(json.length <= 256_000)
        check(prefs(c).edit().putString(key(state.config.projectId), json).commit())
    }
    fun stop(c: Context, project: String) = synchronized(monitor) {
        load(c, project)?.let { save(c, it.copy(config = it.config.copy(active = false), rows = it.rows.map { r -> r.copy(ended = true) })) }
    }
    /** No owner text is sent. Digest is fetched from the attested loopback model service. */
    private fun modelDigest(c: Context, route: String): String? {
        val parts = route.split('|', limit = 3)
        if (parts.size != 3 || parts[0] != "ollama" || !CollaborationReview.localRoute(parts[0], parts[1], parts[2],
                CollaborationRuntime.localAttested(c, route))) return null
        val request = Request.Builder().url(parts[1].trimEnd('/') + "/api/tags").get().build()
        return http.newCall(request).execute().use { response ->
            require(response.isSuccessful)
            val text = requireNotNull(response.body).byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 1_048_576); output.write(buffer, 0, n) }
                output.toString("UTF-8")
            }
            val models = JSONObject(text).getJSONArray("models")
            (0 until models.length()).map { models.getJSONObject(it) }
                .firstOrNull { it.optString("name") in setOf(parts[2], parts[2] + ":latest") }
                ?.optString("digest")?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        }
    }
    suspend fun start(c: Context, project: WorkspaceProject, route: String) = withContext(Dispatchers.IO) {
        require(CollaborationRuntime.enabled(c)) { "Спершу ввімкни локальний журнал" }
        require(project.verificationPaths.isNotEmpty()) { "Спершу задай межу тестів і отримай перевірений випадок виправлення" }
        val digest = requireNotNull(modelDigest(c, route)) { "Лабораторія потребує підтвердженої локальної Ollama та її реального digest" }
        val scope = RepairCasePolicy.scope(project)
        val library = CollaborationLedger(c).repairSnapshot().cases.filter { it.scope == scope }.takeLast(RepairCasePolicy.MAX_CASES)
        require(library.isNotEmpty()) { "Немає кейсів з незмінною перевіркою. Спершу виконай FAIL → правка → PASS у проєкті" }
        synchronized(monitor) {
            val old = load(c, project.id)
            require(old?.config?.active != true) { "Спершу заверши поточний дослід" }
            if (old != null) {
                require(old.config.id.matches(Regex("[a-f0-9-]{36}")))
                val dir = c.noBackupFilesDir.resolve("repair-lab-history"); check(dir.isDirectory || dir.mkdirs())
                require((dir.listFiles()?.size ?: 0) < 16) { "Ліміт історії дослідів: спершу збережи копію" }
                val file = dir.resolve(old.config.id + ".json")
                val json = adapter.toJson(old)
                if (file.exists()) require(file.readText() == json) else {
                    val atomic = android.util.AtomicFile(file); val output = atomic.startWrite()
                    try { output.write(json.toByteArray()); atomic.finishWrite(output) }
                    catch (failure: Exception) { atomic.failWrite(output); throw failure }
                }
            }
            save(c, RepairLabState(RepairLabConfig(UUID.randomUUID().toString(), scope, project.id, route, digest,
                BuildConfig.SOURCE_COMMIT, CollaborationProtocol.hash(Build.FINGERPRINT), library)))
        }
    }
    suspend fun advice(c: Context, task: TaskState, route: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val id = task.projectId ?: return@withContext emptyList()
            val state = load(c, id) ?: return@withContext emptyList()
            if (!state.config.active) return@withContext emptyList()
            fun invalid(): List<String> {
                val closed = runCatching { CollaborationLedger(c).repairSnapshot(state.rows.map { it.episodeId }.toSet()).episodes.filter { it.status != "OPEN" }.map { it.id }.toSet() }
                    .getOrDefault(emptySet())
                synchronized(monitor) { load(c, id)?.let { latest ->
                    save(c, latest.copy(config = latest.config.copy(active = false),
                        rows = latest.rows.map { if (!it.ended) it.copy(invalidConditions = it.invalidConditions || it.episodeId !in closed, ended = true) else it }))
                } }
                return emptyList()
            }
            if (state.config.route != route || state.config.appSource != BuildConfig.SOURCE_COMMIT ||
                state.config.device != CollaborationProtocol.hash(Build.FINGERPRINT)) return@withContext invalid()
            val project = com.lumena.android.settings.ProjectWorkspaceStore.project(c, id) ?: return@withContext emptyList()
            if (RepairCasePolicy.scope(project) != state.config.scope || modelDigest(c, route) != state.config.modelDigest)
                return@withContext invalid()
            val snapshot = CollaborationLedger(c).repairSnapshot()
            val episode = snapshot.episodes.lastOrNull { it.status == "OPEN" && it.scope == state.config.scope && it.taskId == task.id }
                ?: return@withContext emptyList()
            val row = synchronized(monitor) {
                val latest = load(c, id) ?: return@synchronized null
                if (latest.config.id != state.config.id || !latest.config.active) return@synchronized null
                val next = RepairLabPolicy.assign(latest, episode)
                if (next != latest) save(c, next)
                next.rows.firstOrNull { it.episodeId == episode.id }
            } ?: return@withContext emptyList()
            if (row.invalidConditions) return@withContext emptyList()
            RepairCasePolicy.advice(state.config.library.filter { it.id in row.cases }, episode, row.exposed)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }
    fun doneProposal(c: Context, task: TaskState, accepted: Boolean) = synchronized(monitor) {
        val state = task.projectId?.let { load(c, it) } ?: return@synchronized
        if (!state.config.active) return@synchronized
        val row = state.rows.lastOrNull { it.taskId == task.id } ?: return@synchronized
        save(c, state.copy(rows = state.rows.map { if (it != row) it else it.copy(
            rejectedDone = (it.rejectedDone + if (accepted) 0 else 1).coerceAtMost(100_000),
            acceptedDone = (it.acceptedDone + if (accepted) 1 else 0).coerceAtMost(100_000)) }))
    }
    fun modelRequest(c: Context, task: TaskState, text: String) = synchronized(monitor) {
        val state = task.projectId?.let { load(c, it) } ?: return@synchronized
        val next = RepairLabPolicy.request(state, task.id, text)
        if (next != state) save(c, next)
    }
    fun terminal(c: Context, task: TaskState) = synchronized(monitor) {
        val state = task.projectId?.let { load(c, it) } ?: return@synchronized
        if (!state.config.active) return@synchronized
        save(c, state.copy(rows = state.rows.map { if (it.taskId == task.id) it.copy(ended = true) else it }))
    }
    fun snapshots(c: Context): Map<String, String> = synchronized(monitor) {
        val current = prefs(c).all.filterKeys { it.startsWith("repair_lab_") }.values.filterIsInstance<String>()
            .mapNotNull { adapter.fromJson(it) }.associate { "lab/current-${it.config.id}.json" to adapter.toJson(it) }
        val past = c.noBackupFilesDir.resolve("repair-lab-history").listFiles().orEmpty().filter { it.extension == "json" }
            .associate { "lab/archive-${it.name}" to it.readText().also { body -> require(body.length <= 256_000) } }
        current + past
    }
    fun report(c: Context, project: String): String {
        val state = load(c, project) ?: return "Лабораторний дослід ще не починався."
        return RepairLabPolicy.report(state, CollaborationLedger(c).repairSnapshot(state.rows.map { it.episodeId }.toSet()))
    }
}
