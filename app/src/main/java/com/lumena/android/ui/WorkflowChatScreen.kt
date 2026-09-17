package com.lumena.android.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumena.android.agent.runtime.LocalAgentViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

@Suppress("UNUSED_PARAMETER")
@Composable
fun WorkflowChatScreen(agentWorkScope: CoroutineScope? = null) {
    val vm: LocalAgentViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val session = ui.session
    val scrolling = rememberLazyListState()
    var settings by rememberSaveable { mutableStateOf(false) }
    var stopDialog by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var manualCheck by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val active = ui.busy || ui.stopping
    val locked = active || session.pending != null || session.inFlight != null
    LaunchedEffect(Unit) { vm.onVisible() }
    LaunchedEffect(active) { while (active) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    LaunchedEffect(session.chat.size) {
        if (session.chat.isNotEmpty()) scrolling.animateScrollToItem(session.chat.lastIndex)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("Lumena · 0.8.1", style = MaterialTheme.typography.titleLarge)
                Text(ui.connection.selectedModel.ifBlank { "Модель не вибрано" }, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(enabled = !locked, onClick = vm::newChat) { Text("New") }
            TextButton(onClick = { settings = !settings }) { Text("Model") }
        }
        if (settings) {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ui.modelStatus, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(ui.connection.ollamaUrl, { vm.setSetting("ollama", it) }, enabled = !locked,
                    label = { Text("Ollama URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ui.connection.selectedModel, { vm.setSetting("model", it) }, enabled = !locked,
                    label = { Text("Модель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = vm::refreshModels) { Text("Refresh models") }
                    TextButton(enabled = !locked && ui.models.isNotEmpty(), onClick = {
                        val index = ui.models.indexOf(ui.connection.selectedModel)
                        vm.setSetting("model", ui.models[(index + 1).mod(ui.models.size)])
                    }) { Text("Next model") }
                }
                OutlinedTextField(ui.connection.bridgeUrl, { vm.setSetting("bridge", it) }, enabled = !locked,
                    label = { Text("Bridge URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ui.connection.bridgeToken, { vm.setSetting("token", it) }, enabled = !locked,
                    label = { Text("Bridge token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        }
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui.stage, style = MaterialTheme.typography.titleSmall)
                session.task?.let { Text("Виконано дій: ${it.step} / ліміт ${it.maxSteps}", style = MaterialTheme.typography.bodySmall) }
                if (active) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Минуло: ${((now - ui.startedAt).coerceAtLeast(0) / 1000)} с · без нового сигналу: ${((now - ui.lastSignalAt).coerceAtLeast(0) / 1000)} с", style = MaterialTheme.typography.bodySmall)
                    if (ui.pulse.chunks > 0) Text("Отримано фрагментів моделі: ${ui.pulse.chunks}; символів відповіді: ${ui.pulse.contentChars}", style = MaterialTheme.typography.bodySmall)
                    if (now - ui.lastSignalAt > 30_000) Text("Нових даних поки немає. Це не доводить зависання; можна зачекати або зупинити.", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active || session.pending != null || session.inFlight != null)
                        Button(enabled = !ui.stopping, onClick = { stopDialog = true }) { Text("Стоп") }
                    if (session.inFlight != null && !active)
                        TextButton(onClick = vm::checkStoppedTool) { Text("Перевірити стан") }
                    TextButton(onClick = { details = true }) { Text("Етапи / вивід") }
                }
            }
        }
        LazyColumn(state = scrolling, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(session.chat) { message ->
                Card(colors = CardDefaults.cardColors(containerColor = when (message.role) {
                    "user" -> MaterialTheme.colorScheme.primaryContainer
                    "error" -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }), modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(message.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(session.inputDraft, vm::setDraft, modifier = Modifier.weight(1f), maxLines = 4, label = { Text("Запит або повідомлення") })
            Button(enabled = !locked && session.inputDraft.isNotBlank(), onClick = vm::send) { Text("Send") }
        }
    }
    if (stopDialog) AlertDialog(onDismissRequest = { stopDialog = false }, title = { Text("Зупинити задачу?") },
        text = { Text("Після кроку — дочекається поточної відповіді/дії і не запустить наступну.\n\nНегайно — закриє запит моделі та запросить зупинку поточного процесу через bridge. Записані файли й коміти не відкочуються. Для процесів потрібен оновлений bridge.") },
        confirmButton = { TextButton(onClick = { stopDialog = false; vm.stopNow() }) { Text("Зупинити зараз") } },
        dismissButton = { Row {
            if (ui.busy || session.pending != null) TextButton(onClick = { stopDialog = false; vm.stopAfterStep() }) { Text("Після кроку") }
            TextButton(onClick = { stopDialog = false }) { Text("Назад") }
        } })
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("План та фактичний вивід") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(session.control?.plan?.mapIndexed { i, s -> "${i + 1}. $s" }?.joinToString("\n").orEmpty().ifBlank { "Модель ще не надала план." })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SelectionContainer { Text(ui.toolOutput.ifBlank { "Проміжного stdout/stderr поки немає. Завершені результати — у чаті." }) }
            if (session.inFlight != null && !active) TextButton(onClick = { manualCheck = true }) { Text("Я перевірив процес вручну") }
        } }, confirmButton = { TextButton(onClick = { details = false }) { Text("Закрити") } })
    if (manualCheck) AlertDialog(onDismissRequest = { manualCheck = false }, title = { Text("Ручна перевірка") },
        text = { Text("Підтверджуйте лише після перевірки Termux, що стара дія більше не працює. Lumena не має автоматичного підтвердження її результату.") },
        confirmButton = { TextButton(onClick = { manualCheck = false; vm.acknowledgeManualCheck() }) { Text("Перевірено") } },
        dismissButton = { TextButton(onClick = { manualCheck = false }) { Text("Назад") } })
    session.pending?.let { p ->
        if (!active) AlertDialog(onDismissRequest = {}, title = { Text("Дозволити дію?") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("${p.tool}\n${p.reason}\n\n${p.args}\n\nPython виконується з правами Termux; це не ізольована пісочниця.")
            } },
            confirmButton = { TextButton(onClick = vm::approve) { Text("Allow once") } },
            dismissButton = { TextButton(onClick = vm::reject) { Text("Відхилити / Стоп") } })
    }
}
