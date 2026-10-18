package com.lumena.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.ollama.LocalEmbeddingClient
import com.lumena.android.ollama.LocalEmbeddingProvider
import com.lumena.android.llama.LiteRtLmEmbeddingProvider
import com.lumena.android.llama.SemanticLiteRtModelStore
import com.lumena.android.llama.LiteRtImportRefusal
import com.lumena.android.settings.FractalExperienceCanvasStore
import com.lumena.android.settings.ModuleSettings
import com.lumena.android.settings.SemanticDocument
import com.lumena.android.settings.SemanticHit
import com.lumena.android.settings.SemanticMemoryPolicy
import com.lumena.android.settings.SemanticMemoryStore
import com.lumena.android.settings.SemanticMemorySettings
import com.lumena.android.settings.SemanticMemoryCoordinator
import com.lumena.android.settings.SemanticIndexWorker
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit owner controls only. No unattended indexing, prompt injection,
 * ToolGate execution or model download. Inference uses loopback Ollama or in-process LiteRT.
 */
@Composable
fun SemanticMemoryPanel(ollamaUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf("semantic-memory" !in ModuleSettings.disabled(context)) }
    var tag by remember { mutableStateOf(SemanticMemorySettings.selection(context).tag) }
    var backend by remember { mutableStateOf(SemanticMemorySettings.selection(context).backend) }
    var chatMemory by remember { mutableStateOf(SemanticMemorySettings.selection(context).chatEnabled) }
    val coordinator = remember { SemanticMemoryCoordinator(context.applicationContext, ollamaUrl) }
    val workManager = remember { WorkManager.getInstance(context.applicationContext) }
    val indexJobs by remember { workManager.getWorkInfosForUniqueWorkFlow(SemanticIndexWorker.NAME) }
        .collectAsState(initial = emptyList())
    val indexWork = indexJobs.firstOrNull { !it.state.isFinished } ?: indexJobs.firstOrNull()
    val indexing = indexJobs.any { !it.state.isFinished }
    var job by remember { mutableStateOf<Job?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedScope by rememberSaveable { mutableStateOf<String?>(null) }
    var scopeMenu by remember { mutableStateOf(false) }
    var availableScopes by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Модель та її сумісність ще не перевірені на цьому телефоні.") }
    var hits by remember { mutableStateOf<List<SemanticHit>>(emptyList()) }
    var indexed by remember { mutableStateOf(0) }

    fun loadDocuments(): List<SemanticDocument> =
        SemanticMemoryPolicy.documents(
            FractalExperienceCanvasStore.cognitiveGenome(
                context, limit = com.lumena.android.settings.CognitiveGenomePolicy.MAX_ARCHIVED_DERIVED
            ),
            limit = com.lumena.android.settings.CognitiveGenomePolicy.MAX_ARCHIVED_DERIVED
        )

    // Read stored source scopes on entry, including after process recreation.
    // This performs no inference and does not depend on indexing being run again.
    LaunchedEffect(enabled) {
        if (enabled) {
            try {
                val docs = withContext(Dispatchers.IO) { loadDocuments() }
                availableScopes = docs.mapNotNull { it.scopeHash }.distinct().sorted()
                if (selectedScope != null && selectedScope !in availableScopes) selectedScope = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = "SOURCE_UNAVAILABLE: " + failure::class.java.simpleName
            }
        }
    }

    suspend fun <T> withEmbedding(block: suspend (LocalEmbeddingProvider) -> T): T {
        return coordinator.withProvider(SemanticMemorySettings.selection(context), block)
    }

    fun requestedModel(): String =
        if (backend == "litertlm") LiteRtLmEmbeddingProvider.MODEL_ID else tag.trim()

    fun runJob(action: suspend () -> Unit) {
        if (busy || !enabled || !ModuleRegistry.isEnabled("semantic-memory")) {
            status = "Увімкни SHADOW-модуль, перш ніж запускати локальну діагностику."
            return
        }
        busy = true
        job = scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                status = "CANCELLED: наступні пакети не запущено; попередні записи збережено."
                throw cancelled
            } catch (link: LinkageError) {
                status = "RUNTIME_UNAVAILABLE: нативна бібліотека LiteRT-LM недоступна."
            } catch (failure: LiteRtImportRefusal) {
                status = failure.safeStatus()
            } catch (failure: Exception) {
                // Do not log native inputs, stack traces, system paths or secrets.
                status = "NOT_READY: " + failure::class.java.simpleName +
                    " — локальний embedding не підтверджено."
            } finally {
                busy = false
                job = null
            }
        }
    }

    val liteRtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runJob {
                status = "IMPORTING: копіювання та перевірка метаданих; CPU ще НЕ READY."
                val model = SemanticLiteRtModelStore.importFromSaf(context, uri)
                status = "IMPORT_OK (ще НЕ READY): " +
                    (model.bytes / 1048576) + " MiB · sha256=" + model.sha256.take(12) +
                    " · метадані dim=" + model.dimension +
                    " · CPU metadata=" + (if (model.cpuSupported) "DECLARED" else "UNKNOWN") +
                    ". Для READY потрібен справжній CPU smoke."
            }
        }
    }

    HorizontalDivider()
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("EmbeddingGemma 2 · семантична пам'ять", style = MaterialTheme.typography.titleMedium)
            Text(
                "SHADOW: векторний пошук за структурованими принципами Cognitive Genome. " +
                    "Чатова модель та локальний embedding працюють разом. Пошук є підказкою, " +
                    "а виконання й успіх перевіряються окремо. Ваги завантажуються за потреби.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Дозволити модуль на цьому телефоні", modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    enabled = !busy,
                    onCheckedChange = { value ->
                        ModuleSettings.setEnabled(context, "semantic-memory", value)
                        enabled = value
                        if (!value) {
                            SemanticIndexWorker.stop(context)
                            hits = emptyList()
                            status = "Модуль вимкнено. Історію й кеш не видалено."
                        }
                    }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Використовувати локальну пам'ять у чаті", modifier = Modifier.weight(1f))
                Switch(checked = chatMemory, enabled = enabled && !busy && !indexing, onCheckedChange = {
                    SemanticMemorySettings.setChatEnabled(context, it)
                    chatMemory = it
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = enabled && !busy && !indexing, onClick = {
                    SemanticMemorySettings.setBackend(context, "ollama")
                    backend = "ollama"
                    hits = emptyList()
                    status = "Ollama: readiness потребує /api/embed, а не тільки /api/tags."
                }) { Text(if (backend == "ollama") "✓ Ollama" else "Ollama") }
                OutlinedButton(enabled = enabled && !busy && !indexing, onClick = {
                    SemanticMemorySettings.setBackend(context, "litertlm")
                    backend = "litertlm"
                    hits = emptyList()
                    status = "LiteRT-LM CPU 0.18.0: вибери .litertlm, далі виконай smoke."
                }) { Text(if (backend == "litertlm") "✓ LiteRT CPU" else "LiteRT CPU") }
            }
            if (backend == "ollama") {
                OutlinedTextField(
                    value = tag,
                    onValueChange = { if (!busy && !indexing) { tag = it.take(100); SemanticMemorySettings.setTag(context, tag) } },
                    label = { Text("Точний локальний Ollama tag") },
                    supportingText = {
                        Text("Модель має реально відповідати через /api/embed. Cloud заборонений.")
                    },
                    enabled = enabled && !busy && !indexing,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    "LiteRT-LM 0.18.0 · CPU, 2 потоки · Android без Ollama та Bridge. " +
                        "Вибери локальний пакет .litertlm з текстовим енкодером. " +
                        "Навіть якщо пакет мультимодальний, тут працює лише текстовий CPU.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(enabled = enabled && !busy && !indexing, onClick = {
                    liteRtPicker.launch(arrayOf("*/*"))
                }) { Text("Імпортувати файл .litertlm через Android") }
                val model = SemanticLiteRtModelStore.selected(context)
                Text(
                    if (model == null) "NOT_INSTALLED: ще немає вибраного LiteRT-файла."
                    else "Вибрано: " + SemanticLiteRtModelStore.displayName(context) + " · " +
                        (model.length() / 1048576) + " MiB. Приватна копія збережена; RAM завантажується за потреби.",
                    style = MaterialTheme.typography.labelSmall
                )
                SemanticLiteRtModelStore.sourceHint(context)?.takeIf { it.isNotBlank() }?.let {
                    Text("Джерело імпорту: $it", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "Ваги не входять до StateVault (ліміт 64 MiB). Копія імпортується " +
                        "тільки за твоїм вибором, попередні моделі не видаляються.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            OutlinedButton(enabled = enabled && !busy, onClick = {
                runJob {
                    withEmbedding { provider ->
                        val identity = provider.inspectModel(requestedModel())
                        val vector = provider.embed(
                            identity, listOf("Lumena local embedding readiness check.")
                        ).single()
                        val stats = withContext(Dispatchers.IO) { SemanticMemoryStore.stats(context) }
                        status = "READY: " + identity.name +
                            " · digest " + identity.digest.take(12) +
                            " · REAL dimension " + vector.size +
                            " · кеш " + stats.rows + " векторів. SHADOW only."
                    }
                }
            }) {
                Text(if (backend == "litertlm") "CPU inference smoke — REAL" else "Перевірити /api/embed — REAL")
            }
            Button(enabled = enabled && !busy && !indexing, onClick = {
                SemanticIndexWorker.enqueue(context, ollamaUrl)
                status = "Індексацію заплановано. Можна повернутися в чат; завершені пакети збережуться."
            }) {
                Text(if (indexing) "Індексація триває…" else "Індексувати наступні 48 принципів")
            }
            indexWork?.let { work ->
                Text("Індексація: ${work.state} · " +
                    work.progress.getInt("done", work.outputData.getInt("done", 0)) + "/" +
                    work.progress.getInt("total", 48) +
                    (work.outputData.getString("reason")?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (indexing) TextButton(onClick = { SemanticIndexWorker.stop(context) }) { Text("Зупинити індексацію") }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (busy) {
                TextButton(onClick = { job?.cancel() }) { Text("STOP після поточного native inference") }
                Text(
                    "STOP скасовує наступні пакети; уже розпочатий JNI виклик може спершу завершитися.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                "Область пошуку: " + (selectedScope?.take(20) ?: "лише GLOBAL") +
                    ". Принципи іншого проєкту автоматично не переносяться.",
                style = MaterialTheme.typography.labelSmall
            )
            if (availableScopes.isNotEmpty()) {
                TextButton(enabled = enabled && !busy, onClick = { scopeMenu = true }) {
                    Text("Вибрати область (" + availableScopes.size + " проєктів)")
                }
                DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
                    DropdownMenuItem(text = { Text("GLOBAL") }, onClick = {
                        selectedScope = null
                        hits = emptyList()
                        scopeMenu = false
                    })
                    availableScopes.forEach { option ->
                        DropdownMenuItem(text = { Text("Область " + option.take(20)) }, onClick = {
                            selectedScope = option
                            hits = emptyList()
                            scopeMenu = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(SemanticMemoryPolicy.MAX_QUERY) },
                label = { Text("Запит за змістом") },
                supportingText = { Text("Ручний локальний SHADOW-пошук; невідомі причини залишаються UNKNOWN.") },
                enabled = enabled && !busy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(enabled = enabled && !busy && query.isNotBlank(), onClick = {
                runJob {
                    withEmbedding { provider ->
                        val identity = provider.inspectModel(requestedModel())
                        val term = query.trim()
                        require(term.isNotEmpty() && term.length <= SemanticMemoryPolicy.MAX_QUERY)
                        val vector = provider.embed(identity, listOf(term)).single()
                        val docs = withContext(Dispatchers.IO) { loadDocuments() }
                        val result = withContext(Dispatchers.IO) {
                            SemanticMemoryStore.search(
                                context, identity, vector, docs, selectedScope, limit = 6
                            )
                        }
                        hits = result
                        status = if (result.isEmpty()) {
                            "Живих принципів у вибраній області немає. Не імітуємо embeddings."
                        } else {
                            "Знайдено " + result.size +
                                " структурованих принципів. Схожість не є доказом мети."
                        }
                    }
                }
            }) { Text("Семантичний пошук (тільки перегляд)") }
            hits.forEach { hit ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(hit.stage.name + " · " + hit.id, style = MaterialTheme.typography.labelMedium)
                    Text(
                        "cosine=" + String.format(java.util.Locale.US, "%.3f", hit.score) +
                            " · source evidence IDs=" + hit.evidenceCount,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(hit.text.take(380), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
