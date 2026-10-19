package com.lumena.android.ui

import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lumena.android.BuildConfig
import com.lumena.android.agent.core.WorkspaceProject
import com.lumena.android.collaboration.*
import com.lumena.android.settings.ProjectWorkspaceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CollaborationPanel(project: WorkspaceProject, review: (suspend (String, Boolean) -> String)?, reviewIdentity: String = "") {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(CollaborationRuntime.enabled(context)) }
    var entries by remember(project.id) { mutableStateOf<List<CollaborationEntry>>(emptyList()) }
    var status by remember(project.id) { mutableStateOf("") }
    var visible by remember(project.id) { mutableStateOf(false) }
    var answer by remember(project.id) { mutableStateOf("") }
    var busy by remember(project.id) { mutableStateOf(false) }
    var localAttestation by remember(project.id, reviewIdentity) { mutableStateOf(CollaborationRuntime.localAttested(context, reviewIdentity)) }
    var acknowledge by remember(project.id) { mutableStateOf<String?>(null) }
    var archived by remember { mutableStateOf(0) }
    var claimText by remember(project.id) { mutableStateOf("") }
    var claimReport by remember(project.id) { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { CollaborationLedger(context).exportArchive(it) }
                }
                status = "Приватний архів збережено. Він містить історію результатів; бережи його як дані проєктів."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Експорт не завершено. Локальна історія збережена." }
            finally { busy = false }
        }
    }
    fun refresh() { scope.launch {
        try {
            val state = withContext(Dispatchers.IO) { CollaborationLedger(context).let { it.entries() to it.archivedSegments() } }
            entries = state.first; archived = state.second; status = ""
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "Журнал недоступний або пошкоджений. Дані збережено; нові дії журналу блокуються." }
    } }
    LaunchedEffect(project.id) { refresh() }
    Text("Спільний журнал моделей", style = MaterialTheme.typography.titleMedium)
    Row {
        Switch(enabled, { CollaborationRuntime.enable(context, it); enabled = it })
        Text("Зберігати нові виклики локально")
    }
    Text("Старі результати не переатестовуються. Успіх інструмента не підтверджує всю мету. Локальна модель отримує коротку картку; зовнішня передача потребує дозволу в параметрах проєкту.", style = MaterialTheme.typography.bodySmall)
    if (review != null && reviewIdentity.startsWith("ollama|")) {
        Row {
            Checkbox(localAttestation, { CollaborationRuntime.attestLocal(context, reviewIdentity, it); localAttestation = it })
            Text("Підтверджую локальну Ollama без хмарного проксі для роботи з приватним контекстом.")
        }
    }
    Text("Архівних сегментів: $archived. Панель показує поточні й останні архівні квитанції; повна історія є в експорті.", style = MaterialTheme.typography.bodySmall)
    Row {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { withContext(Dispatchers.IO) { CollaborationLedger(context).archiveClosedSegment() }; refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status = "Архівування не виконано: заверши активні дії та перевір UNKNOWN. Дані збережено." }
                finally { busy = false }
            }
        }) { Text("Архівувати завершене") }
        OutlinedButton(enabled = !busy, onClick = { export.launch("Lumena-private-projects.zip") }) { Text("Копія всіх проєктів") }
    }
    OutlinedButton(onClick = { visible = !visible; refresh() }) { Text(if (visible) "Згорнути квитанції" else "Переглянути квитанції") }
    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
    if (visible && status.isBlank()) {
        val scoped = entries.filter { it.scope.projectId == project.id && it.scope.root == project.root }
        val handoff = CollaborationProtocol.handoff(scoped, project, BuildConfig.SOURCE_COMMIT)
        Text(handoff, style = MaterialTheme.typography.bodySmall)
        CollaborationProtocol.unresolved(entries).forEach { intent ->
            Text("Невідомий наслідок у цій інсталяції: ${intent.tool} · проєкт ${intent.scope.projectId} · ${intent.id}. Спершу перевір стан файлів/процесу через читання.")
            OutlinedButton(onClick = { acknowledge = intent.id }) { Text("Після перевірки дозволити наступні дії…") }
        }
        if (review != null) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; answer = ""
                scope.launch {
                    try { answer = review(handoff, localAttestation).take(12_000) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { answer = "Огляд недоступний. Обери вбудовану модель або підтверджену локальну Ollama. Хмарні моделі не отримують цей журнал." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Модель читає…" else "Дати локальній моделі прочитати") }
            if (answer.isNotBlank()) Text("Порада моделі — не квитанція й не дозвіл:\n$answer")
        }
    }
    Text("Перевірити заяву моделі", style = MaterialTheme.typography.titleSmall)
    Text("Встав відповідь ChatGPT або іншої моделі. Lumena звірить заяви про запуск, тести й зміну файлів із квитанціями цього проєкту — без моделі й без мережі.",
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = claimText, onValueChange = { claimText = it.take(ClaimCheck.MAX_INPUT) },
        placeholder = { Text("Наприклад: «8 тестів пройшли, я виправив brain.py»") },
        minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
    OutlinedButton(enabled = claimText.isNotBlank(), onClick = {
        val text = claimText
        scope.launch {
            claimReport = try {
                val all = withContext(Dispatchers.IO) { CollaborationLedger(context).entries() }
                entries = all
                ClaimCheck.report(ClaimCheck.check(text, all, project, BuildConfig.SOURCE_COMMIT))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Журнал недоступний: заяви не перевірено. Це не означає, що вони правдиві." }
        }
    }) { Text("Перевірити заяву") }
    if (claimReport.isNotBlank()) Text(claimReport, style = MaterialTheme.typography.bodySmall)
    acknowledge?.let { id ->
        AlertDialog(onDismissRequest = { acknowledge = null }, title = { Text("Невідомий результат лишиться невідомим") },
            text = { Text("Підтвердь, що перевірив наслідки та береш під контроль наступні дії. Це зніме блокування нових дій; старий виклик не повториться й не стане успішним. Активний виклик розблокувати не можна.") },
            confirmButton = { TextButton(onClick = {
                acknowledge = null
                scope.launch {
                    try { withContext(Dispatchers.IO) { CollaborationLedger(context).acknowledgeUnknownFromOwner(id) }; refresh() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "Блокування збережено: виклик активний або журнал недоступний." }
                }
            }) { Text("Перевірив, дозволяю наступні дії") } },
            dismissButton = { TextButton(onClick = { acknowledge = null }) { Text("Залишити блокування") } })
    }
}

@Composable
fun CollaborationProjectPicker(busy: Boolean) {
    val context = LocalContext.current.applicationContext
    val projects = remember { runCatching { ProjectWorkspaceStore.load(context).projects }.getOrDefault(emptyList()) }
    var selected by remember { mutableStateOf(CollaborationRuntime.companionProject(context)) }
    var show by remember { mutableStateOf(false) }
    TextButton(enabled = !busy, onClick = { show = !show }) {
        Text("Проєкт журналу: " + (projects.firstOrNull { it.id == selected }?.name ?: "не прив’язано"))
    }
    if (show) {
        Text("Сесію ChatGPT не вважаємо проєктом. Обери каталог, якого стосується поточна робота.")
        (listOf<WorkspaceProject?>(null) + projects).forEach { project ->
            TextButton(enabled = !busy, onClick = {
                CollaborationRuntime.selectCompanionProject(context, project?.id); selected = project?.id; show = false
            }) { Text(project?.name ?: "Без прив’язки") }
        }
    }
}
