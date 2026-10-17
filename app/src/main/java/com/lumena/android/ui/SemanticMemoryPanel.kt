package com.lumena.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.lumena.android.settings.FractalExperienceCanvasStore
import com.lumena.android.settings.ModuleSettings
import com.lumena.android.settings.SemanticDocument
import com.lumena.android.settings.SemanticHit
import com.lumena.android.settings.SemanticMemoryPolicy
import com.lumena.android.settings.SemanticMemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit owner controls only. No unattended indexing, prompt injection,
 * ToolGate execution or model download. All inference is loopback /api/embed.
 */
@Composable
fun SemanticMemoryPanel(ollamaUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf("semantic-memory" !in ModuleSettings.disabled(context)) }
    var tag by remember { mutableStateOf("embeddinggemma-2") }
    var query by remember { mutableStateOf("") }
    var selectedScope by remember { mutableStateOf<String?>(null) }
    var availableScopes by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Модель та її сумісність ще не перевірені на цьому телефоні.") }
    var hits by remember { mutableStateOf<List<SemanticHit>>(emptyList()) }
    var indexed by remember { mutableStateOf(0) }

    fun loadDocuments(limit: Int): List<SemanticDocument> =
        SemanticMemoryPolicy.documents(
            FractalExperienceCanvasStore.cognitiveGenome(context, limit = 256),
            limit = limit
        )

    fun runJob(action: suspend () -> Unit) {
        if (busy || !enabled || !ModuleRegistry.isEnabled("semantic-memory")) {
            status = "Увімкни SHADOW-модуль, перш ніж запускати локальну діагностику."
            return
        }
        busy = true
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                status = "Індексацію перервано; попередні індексні записи збережено."
                throw cancelled
            } catch (failure: Exception) {
                // Never echo raw Ollama responses or private inputs in errors.
                status = "Недоступно: " + (failure.message ?: "помилка локальної моделі").take(180)
            } finally {
                busy = false
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
                    "Це НЕ перевірка мети, не нові права і не навчання ваг. Текст лише на 127.0.0.1.",
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
                            hits = emptyList()
                            status = "Модуль вимкнено. Історію й кеш не видалено."
                        }
                    }
                )
            }
            OutlinedTextField(
                value = tag,
                onValueChange = { if (!busy) tag = it.take(100) },
                label = { Text("Точний локальний Ollama tag") },
                supportingText = {
                    Text("«embeddinggemma-2» — приклад назви, а не підтвердження встановлення. " +
                        "Моделі :cloud, автозавантаження й зовнішні URL заборонені.")
                },
                enabled = enabled && !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(enabled = enabled && !busy, onClick = {
                runJob {
                    val identity = LocalEmbeddingClient(ollamaUrl).inspectModel(tag)
                    val stats = withContext(Dispatchers.IO) { SemanticMemoryStore.stats(context) }
                    status = "LOCAL MODEL: " + identity.name +
                        " · digest " + identity.digest.take(12) +
                        " · кеш: " + stats.rows + " векторів, " +
                        stats.modelVersions + " версій моделей."
                }
            }) {
                Text("Перевірити /api/tags (лише локально)")
            }
            Button(enabled = enabled && !busy, onClick = {
                runJob {
                    val provider = LocalEmbeddingClient(ollamaUrl)
                    val identity = provider.inspectModel(tag)
                    val docs = withContext(Dispatchers.IO) {
                        loadDocuments(SemanticMemoryPolicy.MAX_SESSION)
                    }
                    require(docs.isNotEmpty()) { "Немає перевіреної Fractal історії для індексації" }
                    indexed = 0
                    availableScopes = docs.mapNotNull { it.scopeHash }.distinct().take(8)
                    for (batch in docs.chunked(SemanticMemoryPolicy.MAX_BATCH)) {
                        check(ModuleRegistry.isEnabled("semantic-memory")) { "Модуль було вимкнено" }
                        val vectors = provider.embed(identity, batch.map { it.text })
                        withContext(Dispatchers.IO) {
                            SemanticMemoryStore.upsert(context, identity, batch, vectors)
                        }
                        indexed += batch.size
                        status = "Індексовано " + indexed + "/" + docs.size +
                            " принципів · SHADOW, джерело Canvas незмінене"
                    }
                    status = "Готово: " + indexed + " локальних embeddings для " +
                        identity.name + " (digest " + identity.digest.take(12) + "). " +
                        "Індекс лише для пошуку, не для виконання."
                }
            }) {
                Text(if (busy) "Обробка…" else "Індексувати до 48 принципів вручну")
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(
                "Область пошуку: " + (selectedScope?.take(20) ?: "лише GLOBAL") +
                    ". Принципи іншого проєкту автоматично не переносяться.",
                style = MaterialTheme.typography.labelSmall
            )
            if (availableScopes.isNotEmpty()) {
                TextButton(enabled = enabled && !busy, onClick = { selectedScope = null }) {
                    Text("Пошук лише у глобальних правилах")
                }
                availableScopes.take(6).forEach { option ->
                    TextButton(enabled = enabled && !busy, onClick = { selectedScope = option }) {
                        Text("Область " + option.take(20))
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(SemanticMemoryPolicy.MAX_QUERY) },
                label = { Text("Запит за змістом") },
                supportingText = { Text("Не вводь секрети: це ручний SHADOW-запит до локальної Ollama.") },
                enabled = enabled && !busy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(enabled = enabled && !busy && query.isNotBlank(), onClick = {
                runJob {
                    val provider = LocalEmbeddingClient(ollamaUrl)
                    val identity = provider.inspectModel(tag)
                    val term = query.trim()
                    require(term.isNotEmpty() && term.length <= SemanticMemoryPolicy.MAX_QUERY)
                    val vector = provider.embed(identity, listOf(term)).single()
                    val docs = withContext(Dispatchers.IO) { loadDocuments(256) }
                    val result = withContext(Dispatchers.IO) {
                        SemanticMemoryStore.search(
                            context, identity, vector, docs, selectedScope, limit = 6
                        )
                    }
                    hits = result
                    status = if (result.isEmpty()) {
                        "Немає відповідних живих принципів у цій області / моделі. " +
                            "Не підміняємо embeddings випадковими відповідями."
                    } else {
                        "Знайдено " + result.size +
                            " структурованих принципів. Схожість не є доказом."
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
