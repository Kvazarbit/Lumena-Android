package com.lumena.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumena.android.settings.ExperienceLandscapeStore
import com.lumena.android.settings.LandscapeRuleStatus
import com.lumena.android.settings.LandscapeSnapshot
import com.lumena.android.agent.core.CoreDna
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ExperienceLandscapePanel(busy: Boolean, refreshKey: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedNode by remember { mutableStateOf<String?>(null) }
    var selectedIntent by remember { mutableStateOf<String?>(null) }
    var selectedVersion by remember { mutableStateOf<Long?>(null) }
    val snapshot by produceState<LandscapeSnapshot?>(null, expanded, busy, refreshKey, revision) {
        if (expanded) {
            try {
                value = withContext(Dispatchers.IO) { ExperienceLandscapeStore.snapshot(context) }
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Не вдалося прочитати досвід: ${failure.message}"
            }
        }
    }
    fun edit(action: () -> Unit) {
        if (busy || editing) return
        editing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                error = null
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Зміни не збережені: ${failure.message}"
            } finally { editing = false }
        }
    }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text((if (expanded) "▾ " else "▸ ") + "Ландшафт досвіду та конституція")
    }
    if (!expanded) return
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val data = snapshot
    if (data == null) {
        Text("Завантаження…")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Конституція v${data.state.revision} · вивчених правил ${data.state.activeRules.size}",
            style = MaterialTheme.typography.titleSmall)
        Text("Початкове ДНК: ${CoreDna.VERSION}", style = MaterialTheme.typography.titleSmall)
        Text("${CoreDna.principles.size} інженерних правил проєкту. Це початкові принципи, без приписаних успіхів. Короткі коди мають явне значення; модель не повинна вгадувати їх.", style = MaterialTheme.typography.bodySmall)
        CoreDna.principles.forEach { rule -> Text("${rule.id} · ${rule.title}", style = MaterialTheme.typography.bodySmall) }
        Text("Оцінюються виконання інструментів, а не досягнення всієї мети. Поради не змінюють дозволи та обов’язкові перевірки.",
            style = MaterialTheme.typography.bodySmall)
        Text("Вікно: 30 днів, до 1024 спостережень. Підвищення: від 8 різних завдань і достатньої підтримки. Після суперечності правило знімається з активних.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !busy && !editing, onClick = {
            edit { ExperienceLandscapeStore.setAutoPromote(context, !data.state.autoPromote) }
        }) { Text(if (data.state.autoPromote) "Призупинити підвищення правил" else "Відновити підвищення правил") }

        if (data.view.nodes.isEmpty()) Text("Новий ландшафт порожній. Він наповнюється результатами інструментів; старим капсулам без умов виконання рейтинг не приписується.")
        TextButton(onClick = { selectedIntent = null }) { Text(if (selectedIntent == null) "✓ Усі класи задач" else "Усі класи задач") }
        data.view.nodes.map { it.intent }.distinct().sorted().forEach { intent ->
            TextButton(onClick = { selectedIntent = intent }) { Text((if (intent == selectedIntent) "✓ " else "") + intent) }
        }
        val nodes = data.view.nodes.filter { selectedIntent == null || it.intent == selectedIntent }
        val visible = (nodes.take(4) + nodes.takeLast(4) + nodes.filter { it.id == selectedNode }).distinctBy { it.id }
        visible.forEach { node ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${if (node.score >= 0) "Вершина" else "Яма"} · ${"%.0f".format(node.score)} балів · ${node.label}",
                        style = MaterialTheme.typography.titleSmall)
                    Text("${node.intent} · ${node.scopeLabel}", style = MaterialTheme.typography.labelSmall)
                    Text("Завдань: успіх ${node.successes}, з помилками ${node.failures} · медіана ${node.medianMs} мс",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { selectedNode = if (selectedNode == node.id) null else node.id }) { Text("Докази та умови") }
                    if (selectedNode == node.id) {
                        Text("Область ${node.scope.take(8)} · точний набір аргументів. Показано останні 8 результатів; оцінка використовує все збережене вікно.", style = MaterialTheme.typography.bodySmall)
                        data.state.observations.filter { it.id in node.evidenceIds }.takeLast(8).reversed().forEach { event ->
                            Text("${if (event.ok) "✓" else "✕"} ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.at))} · ${event.elapsedMs} мс\nДжерело ДНК: ${event.genomeEventId}\n${event.detail}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        val edges = data.view.edges.filter { edge -> nodes.any { it.id == edge.from } && nodes.any { it.id == edge.to } }
        if (edges.isNotEmpty()) {
            Text("Спостережувані переходи", style = MaterialTheme.typography.titleSmall)
            Text("Послідовність не доводить причинність. Успіх переходу означає, що обидва інструменти виконались успішно.", style = MaterialTheme.typography.bodySmall)
            edges.sortedByDescending { it.successes + it.failures }.take(6).forEach { edge ->
                val from = nodes.first { it.id == edge.from }.label
                val to = nodes.first { it.id == edge.to }.label
                Text("$from → $to\nУспіх ${edge.successes} · невдача ${edge.failures}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Правила та кандидати", style = MaterialTheme.typography.titleSmall)
        data.view.rules.filter { selectedIntent == null || it.intent == selectedIntent }
            .sortedBy { if (it.status == LandscapeRuleStatus.ACTIVE) 0 else 1 }.take(12).forEach { rule ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    val node = data.view.nodes.firstOrNull { it.id == rule.nodeId }
                    val status = when (rule.status) {
                        LandscapeRuleStatus.CANDIDATE -> "Кандидат"
                        LandscapeRuleStatus.ACTIVE -> "Активне"
                        LandscapeRuleStatus.CONTESTED -> "Суперечливе"
                        LandscapeRuleStatus.DISABLED -> "Вимкнене"
                        LandscapeRuleStatus.STALE -> "Застаріле"
                        LandscapeRuleStatus.HELD -> "Очікує підвищення"
                    }
                    Text("$status · ${node?.label ?: rule.id.take(8)}", style = MaterialTheme.typography.titleSmall)
                    Text(if (rule.kind == "PREFER") "Переважно успішне виконання за цих умов; перевірити поточну застосовність."
                        else if (rule.kind == "RECHECK") "Повторювані невдачі; перед повтором перевірити передумови."
                        else "Докази поза збереженим вікном.", style = MaterialTheme.typography.bodySmall)
                    node?.let { Text(it.scopeLabel, style = MaterialTheme.typography.labelSmall) }
                    Row {
                        TextButton(enabled = !busy && !editing, onClick = { edit { ExperienceLandscapeStore.toggleRule(context, rule.id) } }) {
                            Text(if (rule.status == LandscapeRuleStatus.DISABLED) "Зняти виключення" else "Вимкнути")
                        }
                        if (node != null) TextButton(onClick = { selectedNode = node.id }) { Text("Докази") }
                    }
                    if (node != null && selectedNode == node.id) {
                        data.state.observations.filter { it.id in rule.evidenceIds }.takeLast(5).forEach { event ->
                            Text("${if (event.ok) "✓" else "✕"} ${event.target}\nДНК: ${event.genomeEventId}\n${event.detail}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (data.view.rules.isEmpty()) Text("Для кандидатів потрібно щонайменше 3 завдання з результатами одного типу.", style = MaterialTheme.typography.bodySmall)
        Text("Історія конституції", style = MaterialTheme.typography.titleSmall)
        Text("Відновлення повертає лише досі підтверджені правила та призупиняє нові підвищення. Ручні виключення зберігаються. У базі — до 32 версій.", style = MaterialTheme.typography.bodySmall)
        data.versions.take(6).forEach { version ->
            Text("v${version.revision} · ${version.reason} · правил ${version.rules.size}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { selectedVersion = if (selectedVersion == version.revision) null else version.revision }) {
                Text("Переглянути правила v${version.revision}")
            }
            if (selectedVersion == version.revision) {
                if (version.rules.isEmpty()) Text("Порожній набір вивчених правил; початкове ДНК залишається чинним.", style = MaterialTheme.typography.bodySmall)
                version.rules.forEach { rule ->
                    Text("${rule.kind} · ${rule.intent} · ${rule.scope.take(8)}\n${rule.text}\nДоказів: ${rule.evidenceIds.size}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(enabled = !busy && !editing, onClick = { edit { ExperienceLandscapeStore.restore(context, version.revision) } }) {
                Text("Відновити набір v${version.revision}")
            }
        }
    }
}
