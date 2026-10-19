package com.lumena.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lumena.android.agent.core.*
import com.lumena.android.collaboration.*
import com.lumena.android.settings.ProjectWorkspaceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExperienceLoopPanel(project: WorkspaceProject, route: String, changed: (ProjectWorkspaceState) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var paths by remember(project.id, project.verificationPaths) { mutableStateOf(project.verificationPaths.joinToString("\n")) }
    var visible by remember(project.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember(project.id) { mutableStateOf("") }
    var report by remember(project.id) { mutableStateOf("") }
    fun action(work: suspend () -> String) { scope.launch {
        busy = true
        try { status = work() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { status = failure.message.orEmpty().take(300).ifBlank { "Дію не завершено; дані збережено." } }
        finally { busy = false }
    } }
    OutlinedButton(onClick = { visible = !visible }) { Text("Досвід проєкту й лабораторія") }
    if (!visible) return
    Text("Випадки виправлень збираються з журналу: однакова перевірка впала → правка файлу → пройшла. Це історичні приклади, а не правила чи дозволи.")
    Text("Потрібен bridge 0.33. Вкажи всі тести, допоміжні файли та дані перевірки — по одному шляху від кореня проєкту. Зміна цього списку починає нову версію мети.")
    OutlinedTextField(paths, { paths = it.take(4000) }, label = { Text("Наприклад: tests або tests/test_calc.py") },
        minLines = 2, modifier = Modifier.fillMaxWidth())
    OutlinedButton(enabled = !busy, onClick = { action {
        val updated = withContext(Dispatchers.IO) { ProjectWorkspaceStore.update(context) { state ->
            val p = state.projects.first { it.id == project.id }
            ProjectWorkspacePolicy.put(state, p.copy(verificationPaths = paths.lines().map { it.trim() }.filter { it.isNotEmpty() },
                updatedAt = System.currentTimeMillis()))
        } }
        changed(updated); "Межу перевірки збережено. Почни новий запит для зміненої мети."
    } }) { Text("Зберегти межу перевірки") }
    Text("За замовчуванням неперевірені поради не додаються до проєктного чату. Дослід випадково показує або приховує 1–2 кейси; модель, бібліотека й умови фіксуються. Для цього етапу потрібна підтверджена локальна Ollama з відбитком моделі. Вбудований чат працює без досліду.")
    OutlinedButton(enabled = !busy, onClick = { action {
        val current = withContext(Dispatchers.IO) { requireNotNull(ProjectWorkspaceStore.project(context, project.id)) }
        RepairLabStore.start(context, current, route)
        report = withContext(Dispatchers.IO) { RepairLabStore.report(context, project.id) }
        "Дослід увімкнено для цього проєкту."
    } }) { Text("Почати дослід 50/50") }
    Row {
        TextButton(enabled = !busy, onClick = { action {
            withContext(Dispatchers.IO) { RepairLabStore.stop(context, project.id) }
            "Дослід зупинено; його спостереження збережені."
        } }) { Text("Завершити дослід") }
        TextButton(enabled = !busy, onClick = { action {
            report = withContext(Dispatchers.IO) { RepairLabStore.report(context, project.id) }; ""
        } }) { Text("Оновити звіт") }
    }
    if (report.isNotBlank()) Text(report, style = MaterialTheme.typography.bodySmall)
    if (status.isNotBlank()) Text(status)
}

@Composable
fun ContextArchiveRestorePanel(changed: (ProjectWorkspaceState) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var infos by remember { mutableStateOf<List<ImportedContextInfo>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var inspect by remember { mutableStateOf<Pair<String, String>?>(null) }
    suspend fun refresh() { infos = withContext(Dispatchers.IO) { ImportedContextArchive.infos(context) } }
    fun action(work: suspend () -> Unit) { scope.launch {
        busy = true
        try { work(); refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { status = "Не завершено: " + failure.message.orEmpty().take(240) }
        finally { busy = false }
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { ImportedContextArchive.importArchive(context, it) } }
            visible = true; status = "Архів перевірено й збережено окремо. Переглянь склад і віднови потрібні проєкти."
        }
    }
    LaunchedEffect(Unit) { try { refresh() } catch (_: Exception) { status = "Імпортований архів недоступний; нові зміни файлів блокуються до перевірки." } }
    OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Відновлення з копії ZIP") }
    if (infos.isNotEmpty()) TextButton(onClick = { visible = !visible }) { Text("Імпортовані архіви: ${infos.size}") }
    if (status.isNotBlank()) Text(status)
    if (visible) infos.forEach { info ->
        Text("З архіву, не перевірено цим встановленням · ${info.id.take(12)}")
        Text("Проєкти: ${info.projects.projects.joinToString { it.name }} · квитанцій ${info.receipts}")
        info.projects.projects.forEach { p ->
            Text("${p.name} · каталог ${p.root}\nМета з архіву: ${p.goal}")
            p.constraints.forEach { Text("Обмеження з архіву: $it", style = MaterialTheme.typography.bodySmall) }
            Text("Рішень: ${p.decisions.size}; записів задач: ${p.tasks.size}. Межа тестів: ${p.verificationPaths.joinToString().ifBlank { "не задана" }}.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Копія містить історію й рішення. Файли проєкту, моделі, ключі та дозволи потрібно налаштувати окремо.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !busy, onClick = { action {
            changed(withContext(Dispatchers.IO) { ImportedContextArchive.restoreProjects(context, info.id) })
            status = "Історію проєктів відновлено з позначкою архіву. Перед роботою перевір фактичні файли."
        } }) { Text("Відновити історію проєктів") }
        info.unresolved.forEach { e ->
            Text("Архівний UNKNOWN: ${e.tool} · ${e.scope.projectId} · ${e.intentId.take(12)}. Спершу перевір фактичний стан читанням.")
            Text("Файл / каталог: ${e.targets.entries.joinToString { "${it.key}=${it.value}" }}", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = { inspect = info.id to e.intentId }) { Text("Підтвердити огляд наслідків…") }
        }
    }
    inspect?.let { (archive, intent) ->
        AlertDialog(onDismissRequest = { inspect = null }, title = { Text("Стан уже перевірено?") },
            text = { Text("Підтверди, що особисто перевірив файли й процеси після цієї невідомої дії. Це дозволить наступні зміни; старий результат залишиться UNKNOWN.") },
            confirmButton = { TextButton(onClick = {
                inspect = null; action {
                    withContext(Dispatchers.IO) { ImportedContextArchive.acknowledgeAfterInspection(context, archive, intent) }
                    status = "Огляд підтверджено. Архівні факти не змінено."
                }
            }) { Text("Стан перевірено") } },
            dismissButton = { TextButton(onClick = { inspect = null }) { Text("Повернутись") } })
    }
}
