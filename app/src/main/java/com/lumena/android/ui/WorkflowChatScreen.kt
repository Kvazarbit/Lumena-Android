package com.lumena.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.history.*
import com.lumena.android.ollama.ToolMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class EditHistoryTitle(val id: String?, val title: String, val topic: Boolean)
private data class ForkPoint(val sessionId: String, val taskId: String, val title: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowChatScreen(
    @Suppress("UNUSED_PARAMETER") agentWorkScope: CoroutineScope? = null,
    model: HistoryViewModel = viewModel()
) {
    val ui by model.ui.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var setup by rememberSaveable { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf<EditHistoryTitle?>(null) }
    var moveId by remember { mutableStateOf<String?>(null) }
    var fork by remember { mutableStateOf<ForkPoint?>(null) }
    val current = ui.selected
    val drawerWidth = (LocalConfiguration.current.screenWidthDp * 0.9f).coerceAtMost(380f).dp
    LaunchedEffect(Unit) { model.visible() }
    LaunchedEffect(current?.meta?.id) {
        review = false
        if (ui.scrollMessageId == null && current?.payload?.chat?.isNotEmpty() == true)
            list.scrollToItem(current.payload.chat.lastIndex)
    }
    LaunchedEffect(ui.scrollMessageId, current?.meta?.id) {
        val id = ui.scrollMessageId ?: return@LaunchedEffect
        val index = current?.payload?.chat?.indexOfFirst { it.id == id } ?: -1
        if (index >= 0) list.scrollToItem(index)
        model.consumedScroll()
    }
    LaunchedEffect(current?.payload?.chat?.size) {
        val size = current?.payload?.chat?.size ?: 0
        val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (size > 0 && ui.scrollMessageId == null && lastVisible >= size - 3) list.animateScrollToItem(size - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(drawerWidth).fillMaxHeight()) {
                HistoryDrawer(ui, onSelect = { id, anchor ->
                    model.select(id, anchor); scope.launch { drawer.close() }
                }, onNew = { model.newConversation(); scope.launch { drawer.close() } },
                    onTopic = { editTitle = EditHistoryTitle(null, "", true) },
                    onRename = { id, title, topic -> editTitle = EditHistoryTitle(id, title, topic) },
                    onMove = { moveId = it })
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.semantics { contentDescription = "Відкрити історію" }) { Text("☰") }
                Column(Modifier.weight(1f)) {
                    Text(current?.meta?.title ?: "Lumena", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(current?.meta?.model?.ifBlank { "Модель не вибрано" } ?: "Історія задач",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { model.newConversation() }, enabled = !ui.loading) { Text("Новий") }
                TextButton(onClick = { setup = true }, enabled = current != null) { Text("Модель") }
            }
            if (current != null) {
                val topic = ui.catalog.topics.firstOrNull { it.id == current.meta.topicId }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(topic?.title.orEmpty(), style = MaterialTheme.typography.labelSmall)
                    HistoryLogic.breadcrumbs(ui.catalog, current.meta.id).forEach { ancestor ->
                        Text(" › ", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { model.select(ancestor.id) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(ancestor.title.take(32), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            ui.runningSessionId?.let { running ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (running == current?.meta?.id) "Агент працює в цій розмові" else "Агент працює в іншій розмові",
                            style = MaterialTheme.typography.labelMedium)
                        if (running != current?.meta?.id) TextButton(onClick = { model.select(running) }) { Text("Відкрити задачу") }
                    }
                    TextButton(onClick = model::stop) { Text("Стоп") }
                }
            }
            current?.payload?.recoveryNotice?.let {
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (ui.loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current?.payload?.chat.isNullOrEmpty()) item {
                        Text("Почніть розмову. Кнопка ☰ відкриває теми, історію задач та відгалуження контексту.",
                            Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(current?.payload?.chat.orEmpty(), key = { it.id }) { message ->
                        val mark = current?.payload?.tasks?.firstOrNull { it.anchorMessageId == message.id && it.chatEnd != null }
                        HistoryMessage(message, mark != null, onFork = {
                            if (current != null && mark != null) fork = ForkPoint(current.meta.id, mark.id, mark.goal)
                        })
                    }
                }
            }
            current?.payload?.pending?.let { pending ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Очікує дозволу\n${pending.plan.request.tool}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { review = true }, enabled = ui.runningTaskId == null) { Text("Переглянути") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = current?.payload?.draft.orEmpty(), onValueChange = model::draft,
                    label = { Text("Повідомлення") }, minLines = 1, maxLines = 4,
                    enabled = current != null && ui.runningSessionId != current.meta.id,
                    modifier = Modifier.weight(1f))
                Button(onClick = model::send, enabled = current != null && current.payload.draft.isNotBlank() &&
                    current.payload.pending == null && ui.runningTaskId == null) { Text("Надіслати") }
            }
        }
    }

    if (setup && current != null) AlertDialog(onDismissRequest = { setup = false },
        title = { Text("Модель і з’єднання") },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui.modelStatus, style = MaterialTheme.typography.bodySmall)
                Text("Модель і протокол зберігаються окремо для кожної розмови. URL та ключ мосту — спільні.", style = MaterialTheme.typography.bodySmall)
                val frozen = ui.runningSessionId == current.meta.id || current.payload.pending != null
                OutlinedTextField(current.meta.model, { model.model(it, current.meta.toolMode) }, enabled = !frozen,
                    label = { Text("Назва моделі") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                ui.models.forEach { name -> TextButton(onClick = { model.model(name, current.meta.toolMode) }, enabled = !frozen) {
                    Text((if (name == current.meta.model) "✓ " else "") + name, maxLines = 2)
                } }
                Row { ToolMode.entries.forEach { mode ->
                    TextButton(onClick = { model.model(current.meta.model, mode) }, enabled = !frozen) {
                        Text((if (mode == current.meta.toolMode) "✓ " else "") + mode.name)
                    }
                } }
                Text("AUTO перевіряє capabilities моделі. NATIVE використовує tool_calls. JSON — сумісний резервний режим. Хмарна модель залишається хмарною, навіть через localhost.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(ui.settings.ollamaUrl, model::ollamaUrl, label = { Text("Ollama URL") }, singleLine = true)
                TextButton(onClick = model::refreshModels) { Text("Оновити список") }
                HorizontalDivider()
                OutlinedTextField(ui.settings.bridgeUrl, model::bridgeUrl, label = { Text("Bridge URL") }, singleLine = true)
                OutlinedTextField(ui.settings.bridgeToken, model::bridgeToken, label = { Text("Bridge token") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        }, confirmButton = { TextButton(onClick = { setup = false }) { Text("Готово") } })

    editTitle?.let { edit ->
        var value by remember(edit) { mutableStateOf(edit.title) }
        AlertDialog(onDismissRequest = { editTitle = null }, title = { Text(if (edit.id == null) "Нова тема" else "Перейменувати") },
            text = { OutlinedTextField(value, { value = it }, label = { Text("Назва") }, singleLine = true) },
            confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = {
                when { edit.id == null -> model.createTopic(value); edit.topic -> model.renameTopic(edit.id, value); else -> model.rename(edit.id, value) }
                editTitle = null
            }) { Text("Зберегти") } }, dismissButton = { TextButton(onClick = { editTitle = null }) { Text("Скасувати") } })
    }
    moveId?.let { id -> AlertDialog(onDismissRequest = { moveId = null }, title = { Text("Перемістити до теми") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Розмова переміщується разом із дочірніми гілками.", style = MaterialTheme.typography.bodySmall)
            ui.catalog.topics.forEach { topic -> TextButton(onClick = { model.move(id, topic.id); moveId = null }) { Text(topic.title) } }
        } }, confirmButton = { TextButton(onClick = { moveId = null }) { Text("Закрити") } }) }
    fork?.let { point -> AlertDialog(onDismissRequest = { fork = null }, title = { Text("Відгалузити контекст?") },
        text = { Text("${point.title}\n\nНова гілка успадкує історію лише до цієї завершеної точки. Пізніші повідомлення не потраплять у неї.\n\nФайли workspace не копіюються і не відкочуються. Дозволи та незавершені команди не успадковуються.") },
        confirmButton = { TextButton(onClick = { model.fork(point.sessionId, point.taskId); fork = null }) { Text("Створити гілку") } },
        dismissButton = { TextButton(onClick = { fork = null }) { Text("Скасувати") } }) }
    if (review) current?.payload?.pending?.let { pending -> AlertDialog(onDismissRequest = { review = false },
        title = { Text("Дозволити одну дію?") },
        text = { SelectionContainer { Text("${pending.plan.reason}\n\n${pending.plan.request.tool}\n${pending.plan.request.args}\n\nPython виконується з правами Termux. Обмеження шляхів інструментів не є ізоляцією довільного Python-коду.",
            Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(enabled = ui.runningTaskId == null, onClick = {
            model.approve(current.meta.id, pending.control.task.id); review = false
        }) { Text("Дозволити один раз") } },
        dismissButton = { TextButton(onClick = { model.reject(current.meta.id, pending.control.task.id); review = false }) { Text("Відхилити") } }) }
    ui.error?.let { message -> AlertDialog(onDismissRequest = model::clearError, title = { Text("Потрібна увага") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = model::clearError) { Text("Закрити") } }) }
}

@Composable
private fun HistoryDrawer(
    ui: HistoryUiState,
    onSelect: (String, String?) -> Unit,
    onNew: () -> Unit,
    onTopic: () -> Unit,
    onRename: (String, String, Boolean) -> Unit,
    onMove: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var collapsedTopics by remember { mutableStateOf(emptySet<String>()) }
    var collapsedChats by remember { mutableStateOf(emptySet<String>()) }
    var menu by remember { mutableStateOf<String?>(null) }
    Text("Історія", Modifier.padding(start = 20.dp, top = 20.dp), style = MaterialTheme.typography.headlineSmall)
    Text("Теми · розмови · задачі", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
    Row(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = onTopic) { Text("+ Тема") }
        TextButton(onClick = onNew, enabled = !ui.loading) { Text("+ Розмова") }
    }
    OutlinedTextField(query, { query = it }, label = { Text("Пошук назв і задач") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
    HorizontalDivider(Modifier.padding(top = 8.dp))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        ui.catalog.topics.forEach { topic ->
            val effectiveQuery = if (topic.title.contains(query, true)) "" else query
            val rows = HistoryLogic.tree(ui.catalog, topic.id, effectiveQuery, if (query.isBlank()) collapsedChats else emptySet())
            if (query.isBlank() || rows.isNotEmpty() || topic.title.contains(query, true)) {
                item(key = "topic:${topic.id}") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { collapsedTopics = if (topic.id in collapsedTopics) collapsedTopics - topic.id else collapsedTopics + topic.id }, modifier = Modifier.weight(1f)) {
                            Text((if (topic.id in collapsedTopics && query.isBlank()) "▸ " else "▾ ") + topic.title,
                                modifier = Modifier.fillMaxWidth(), maxLines = 2)
                        }
                        IconButton(onClick = { onRename(topic.id, topic.title, true) }, modifier = Modifier.semantics { contentDescription = "Перейменувати тему" }) { Text("⋮") }
                    }
                }
                if (topic.id !in collapsedTopics || query.isNotBlank()) rows.forEach { row ->
                    val c = row.meta
                    item(key = "chat:${c.id}") {
                        Row(Modifier.fillMaxWidth().padding(start = (8 + row.depth.coerceAtMost(6) * 12).dp)
                            .background(if (c.id == ui.selected?.meta?.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { collapsedChats = if (c.id in collapsedChats) collapsedChats - c.id else collapsedChats + c.id },
                                modifier = Modifier.semantics { contentDescription = "Розгорнути або згорнути розмову" }) { Text(if (c.id in collapsedChats) "▸" else "▾") }
                            Column(Modifier.weight(1f).clickable { onSelect(c.id, null) }.padding(vertical = 10.dp)) {
                                Text((if (row.depth > 0) "↳ " else "") + c.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text((if (ui.runningSessionId == c.id) "● Працює · " else "") + dateLabel(c.updatedAt), style = MaterialTheme.typography.labelSmall)
                            }
                            Box {
                                IconButton(onClick = { menu = c.id }, modifier = Modifier.semantics { contentDescription = "Дії з розмовою" }) { Text("⋮") }
                                DropdownMenu(expanded = menu == c.id, onDismissRequest = { menu = null }) {
                                    DropdownMenuItem(text = { Text("Перейменувати") }, onClick = { menu = null; onRename(c.id, c.title, false) })
                                    DropdownMenuItem(text = { Text("До іншої теми") }, onClick = { menu = null; onMove(c.id) })
                                }
                            }
                        }
                    }
                    if (c.id !in collapsedChats || query.isNotBlank()) c.tasks.filter {
                        !it.inherited && (query.isBlank() || it.goal.contains(query, true) || c.title.contains(query, true) || topic.title.contains(query, true))
                    }.forEach { task -> item(key = "task:${c.id}:${task.id}") {
                        Column(Modifier.fillMaxWidth().clickable { onSelect(c.id, task.anchorMessageId) }
                            .padding(start = (52 + row.depth.coerceAtMost(6) * 12).dp, end = 12.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 42.dp)) {
                            Text(task.goal.take(100), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(statusLabel(task.status) + " · " + dateLabel(task.startedAt), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                }
            }
        }
        if (ui.catalog.conversations.isEmpty()) item { Text("Історія ще порожня", Modifier.padding(20.dp)) }
    }
}

@Composable
private fun HistoryMessage(message: ChatEntry, canFork: Boolean, onFork: () -> Unit) {
    val user = message.role == "user"
    val background = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer
        "error" -> MaterialTheme.colorScheme.errorContainer
        "status" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Column(Modifier.fillMaxWidth(if (user) 0.9f else 0.98f).background(background, RoundedCornerShape(16.dp)).padding(12.dp)) {
            SelectionContainer { Text(message.text, style = if (message.role == "status") MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium) }
            if (canFork) TextButton(onClick = onFork, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("↳ Відгалузити звідси", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

private fun dateLabel(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.DONE -> "✓ Завершено"
    TaskStatus.FAILED -> "✗ Помилка / перервано"
    TaskStatus.CANCELLED -> "■ Зупинено"
    TaskStatus.WAITING_CONFIRMATION -> "Ⅱ Очікує дозволу"
    TaskStatus.NEW -> "○ Розпочато"
    else -> "● Виконується"
}
