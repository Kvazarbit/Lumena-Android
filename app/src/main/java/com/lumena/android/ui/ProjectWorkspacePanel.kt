package com.lumena.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspacePanel(
    projects: List<WorkspaceProject>, activeId: String?, error: String?,
    onDismiss: () -> Unit, onSelect: (String?) -> Unit,
    onCreate: (String?, String, String, String, List<String>, ProjectVerification) -> Unit,
    onDecision: (String, String, String, List<String>) -> Unit,
    onContinue: (ProjectWorkRecord) -> Unit, onCloseWork: (String, Boolean) -> Unit
) {
    var adding by remember { mutableStateOf(projects.isEmpty()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var constraints by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf(ProjectVerification.PYTHON_TESTS) }
    var decisionOpen by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    var decision by remember { mutableStateOf("") }
    var rationale by remember { mutableStateOf("") }
    var alternatives by remember { mutableStateOf("") }
    var showContext by remember { mutableStateOf(false) }
    val project = projects.firstOrNull { it.id == activeId }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Проєкти · локальний контекст", style = MaterialTheme.typography.titleLarge)
            Text("Каталог має бути доступний bridge у Termux. Для індексу потрібен bridge 0.30. Windows-папки самі на телефон не копіюються.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { onSelect(null) }) { Text("Звичайний чат") }
            projects.forEach { p ->
                OutlinedButton(onClick = { onSelect(p.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text((if (p.id == activeId) "✓ " else "") + p.name + " · " + p.root)
                }
            }
            TextButton(onClick = { adding = !adding; editingId = null; name = ""; root = ""; goal = ""; constraints = "" }) { Text(if (adding) "Згорнути додавання" else "Додати проєкт") }
            if (adding) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Назва") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(root, { root = it.take(360) }, enabled = editingId == null, label = { Text("Каталог у Termux: demo або @repo") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(goal, { goal = it.take(1000) }, label = { Text("Мета проєкту") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(constraints, { constraints = it.take(1400) }, label = { Text("Обмеження — по одному на рядок") }, modifier = Modifier.fillMaxWidth())
                Text("Перевірка змін:")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(verification == ProjectVerification.PYTHON_TESTS, { verification = ProjectVerification.PYTHON_TESTS }, label = { Text("Python + pytest") })
                    FilterChip(verification == ProjectVerification.FILE_REVIEW, { verification = ProjectVerification.FILE_REVIEW }, label = { Text("Перечитати файли") })
                }
                Text("Перечитування підтверджує огляд тексту. Для функціональної перевірки потрібні тести відповідного середовища.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = name.isNotBlank() && root.isNotBlank() && goal.isNotBlank(), onClick = {
                    onCreate(editingId, name.trim(), root.trim(), goal.trim(), constraints.lines().map { it.trim() }.filter { it.isNotEmpty() }, verification)
                }) { Text("Зберегти й відкрити") }
            }
            project?.let { p ->
                HorizontalDivider()
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(p.goal)
                TextButton(onClick = {
                    editingId = p.id; adding = true; name = p.name; root = p.root; goal = p.goal
                    constraints = p.constraints.joinToString("\n"); verification = p.verification
                }) { Text("Змінити мету й параметри") }
                p.constraints.forEach { Text("• $it") }
                Text("Останнє спостереження: " + (p.repository.head?.take(12) ?: "ще немає") +
                    " · файлів " + p.repository.files + if (p.repository.truncated) " · покриття обмежене" else "")
                Text("Чат-модель виконує роботу. Embedding-рушій допомагає шукати пам'ять; він налаштовується окремо.", style = MaterialTheme.typography.bodySmall)
                Text("Відкриті задачі: ${p.tasks.count { it.open }}", style = MaterialTheme.typography.titleMedium)
                p.tasks.filter { it.open }.asReversed().forEach { work ->
                    Text(work.goal)
                    Text("${work.status} · ${work.modelId}" + if (work.unknownEffect) " · UNKNOWN: спочатку перевір наслідки" else "", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { onContinue(work) }) { Text("Продовжити") }
                        TextButton(onClick = { onCloseWork(work.id, true) }) { Text("Закрити зобов'язання") }
                    }
                }
                p.tasks.filterNot { it.open }.takeLast(5).asReversed().forEach {
                    Text("${if (it.closedByOwner) "Закрито власником" else it.status} · ${it.goal.take(150)}", style = MaterialTheme.typography.bodySmall)
                    if (it.closedByOwner) TextButton(onClick = { onCloseWork(it.id, false) }) { Text("Відкрити знову") }
                }
                TextButton(onClick = { showContext = !showContext }) { Text("Показати обов'язковий контекст") }
                if (showContext) Text(ProjectWorkspacePolicy.capsule(p).text, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { decisionOpen = !decisionOpen }) { Text("Записати рішення власника") }
                if (decisionOpen) {
                    OutlinedTextField(problem, { problem = it.take(300) }, label = { Text("Проблема") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(decision, { decision = it.take(500) }, label = { Text("Рішення") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(rationale, { rationale = it.take(500) }, label = { Text("Чому обрано цей спосіб") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(alternatives, { alternatives = it.take(800) }, label = { Text("Відкинуті альтернативи — по рядках") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = problem.isNotBlank() && decision.isNotBlank() && rationale.isNotBlank(), onClick = {
                        onDecision(problem.trim(), decision.trim(), rationale.trim(), alternatives.lines().filter { it.isNotBlank() })
                        decisionOpen = false
                    }) { Text("Зберегти рішення") }
                }
                p.decisions.takeLast(6).asReversed().forEach {
                    Text("${it.problem}\n→ ${it.decision}\nЧому: ${it.rationale}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
