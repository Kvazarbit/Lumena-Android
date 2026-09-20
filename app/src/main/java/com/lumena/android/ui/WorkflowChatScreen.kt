package com.lumena.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.core.AgentControlState
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.runtime.AgentRunCoordinator
import com.lumena.android.llama.EmbeddedLlamaClient
import com.lumena.android.ollama.ChatModelClient
import com.lumena.android.ollama.LocalWorkflowAgent
import com.lumena.android.ollama.OllamaClient
import com.lumena.android.ollama.OllamaMessage
import com.lumena.android.ollama.PendingWorkflowTool
import com.lumena.android.ollama.WorkflowOutcome
import com.lumena.android.ollama.WorkflowRunner
import com.lumena.android.settings.LocalSessionSnapshot
import com.lumena.android.settings.LocalSessionStore
import com.lumena.android.settings.LumenaPreferences
import com.lumena.android.settings.PersistedChatMessage
import com.lumena.android.settings.PersistedHistoryMessage
import com.lumena.android.settings.PersistedPendingTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private data class ChatBubble(
    val role: String,
    val text: String
)

@Composable
fun WorkflowChatScreen(
    agentWorkScope: CoroutineScope? = null,
    runCoordinator: AgentRunCoordinator? = null
) {
    val uiScope = rememberCoroutineScope()
    val workScope = agentWorkScope ?: uiScope
    val fallbackCoordinator = remember { AgentRunCoordinator() }
    val coordinator = runCoordinator ?: fallbackCoordinator
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val initial = remember { LumenaPreferences.load(context) }
    val restored = remember { LocalSessionStore.load(context) }
    val systemMessage = remember { OllamaMessage("system", LocalWorkflowAgent.systemPrompt) }

    val bubbles = remember {
        mutableStateListOf<ChatBubble>().apply {
            val restoredChat = restored.chat.map { ChatBubble(it.role, it.text) }
            if (restoredChat.isNotEmpty()) addAll(restoredChat)
            else add(
                ChatBubble(
                    "assistant",
                    "Lumena v0.8 local agent is ready. Plan, task state and verification are controlled by the app."
                )
            )
        }
    }

    var input by rememberSaveable { mutableStateOf(restored.inputDraft) }
    var ollamaUrl by rememberSaveable { mutableStateOf(initial.ollamaUrl) }
    var bridgeUrl by rememberSaveable { mutableStateOf(initial.bridgeUrl) }
    var bridgeToken by rememberSaveable { mutableStateOf(initial.bridgeToken) }
    var selectedModel by rememberSaveable { mutableStateOf(initial.selectedModel) }
    var inferenceBackend by rememberSaveable { mutableStateOf(initial.inferenceBackend) }
    var ggufPath by rememberSaveable { mutableStateOf(initial.ggufPath) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Checking Ollama…") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var currentTask by remember { mutableStateOf(restored.task) }
    var history by remember {
        mutableStateOf(
            listOf(systemMessage) + restored.history.map { OllamaMessage(it.role, it.content) }
        )
    }
    var busy by remember {
        mutableStateOf(
            restored.task?.status in setOf(
                TaskStatus.PLANNING,
                TaskStatus.WAITING_MODEL,
                TaskStatus.EXECUTING,
                TaskStatus.VERIFYING
            )
        )
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun restoredPendingFrom(saved: PersistedPendingTool?): PendingWorkflowTool? = saved?.let {
        val planned = ToolGate.plan(
            PlannerDecision(
                request = ToolRequest(it.tool, it.args),
                reason = it.reason
            )
        )
        val control = it.control
            ?: currentTask?.let { task -> AgentControlState(task = task) }
            ?: return@let null
        if (planned.allowed) {
            PendingWorkflowTool(
                plan = planned,
                history = listOf(systemMessage) + it.history.map { h ->
                    OllamaMessage(h.role, h.content)
                },
                control = control
            )
        } else null
    }

    var pending by remember { mutableStateOf(restoredPendingFrom(restored.pending)) }

    fun persistSession() {
        LocalSessionStore.save(
            context,
            LocalSessionSnapshot(
                chat = bubbles.map { PersistedChatMessage(it.role, it.text) },
                history = history
                    .filterNot { it.role == "system" }
                    .map { PersistedHistoryMessage(it.role, it.content) },
                task = currentTask,
                pending = pending?.let { active ->
                    PersistedPendingTool(
                        tool = active.plan.request.tool,
                        args = active.plan.request.args,
                        reason = active.plan.reason,
                        control = active.control,
                        history = active.history
                            .filterNot { it.role == "system" }
                            .map { PersistedHistoryMessage(it.role, it.content) }
                    )
                },
                inputDraft = input
            )
        )
    }

    fun isCurrentTask(taskId: String): Boolean =
        LocalSessionStore.load(context).task?.id == taskId

    fun acceptControl(taskId: String, runToken: Long, control: AgentControlState) {
        if (!coordinator.isCurrent(runToken, taskId) || !isCurrentTask(taskId)) return
        currentTask = control.task
        persistSession()
    }

    fun syncFromStoredSession() {
        val stored = LocalSessionStore.load(context)
        if (stored.task?.id != currentTask?.id) return
        if (stored.task != currentTask) currentTask = stored.task

        val storedChat = stored.chat.map { ChatBubble(it.role, it.text) }
        if (storedChat.isNotEmpty() && storedChat != bubbles.toList()) {
            bubbles.clear()
            bubbles.addAll(storedChat)
        }

        val storedHistory = listOf(systemMessage) + stored.history.map {
            OllamaMessage(it.role, it.content)
        }
        if (storedHistory != history) history = storedHistory

        val storedPending = restoredPendingFrom(stored.pending)
        if (storedPending?.plan?.request != pending?.plan?.request) pending = storedPending

        busy = stored.task?.status in setOf(
            TaskStatus.PLANNING,
            TaskStatus.WAITING_MODEL,
            TaskStatus.EXECUTING,
            TaskStatus.VERIFYING
        )
    }

    fun stopCurrentTask(reason: String = "Stopped by user") {
        val task = currentTask ?: return
        coordinator.cancel(reason)
        pending = null
        busy = false
        currentTask = task.copy(status = TaskStatus.CANCELLED)
        bubbles += ChatBubble("status", "STOP · $reason")
        persistSession()
    }

    fun clearConversation() {
        coordinator.cancel("New conversation")
        coordinator.clearFinished()
        input = ""
        pending = null
        currentTask = null
        history = listOf(systemMessage)
        bubbles.clear()
        bubbles += ChatBubble("assistant", "New local conversation started.")
        busy = false
        LocalSessionStore.clear(context)
        persistSession()
    }

    fun bridgeOrNull(): TermuxBridgeClient? = bridgeToken
        .takeIf { it.isNotBlank() }
        ?.let { TermuxBridgeClient(bridgeUrl, it) }

    fun modelClient(): ChatModelClient =
        if (inferenceBackend == "embedded") EmbeddedLlamaClient(ggufPath)
        else OllamaClient(ollamaUrl)

    fun modelNameForRun(): String =
        if (inferenceBackend == "embedded") "embedded-gguf" else selectedModel

    fun refreshModels() {
        status = "Checking Ollama…"
        uiScope.launch {
            val result = try {
                OllamaClient(ollamaUrl).listModels()
            } catch (t: Throwable) {
                Result.failure(t)
            }
            result.onSuccess { found ->
                models = found
                val resolvedModel = when {
                    selectedModel.isNotBlank() && selectedModel in found -> selectedModel
                    found.isNotEmpty() -> found.first()
                    else -> selectedModel
                }
                if (resolvedModel != selectedModel) {
                    selectedModel = resolvedModel
                    LumenaPreferences.saveSelectedModel(context, resolvedModel)
                }
                status = if (found.isEmpty()) {
                    "Ollama online · no local models"
                } else {
                    "Ollama online · ${found.size} model(s)"
                }
            }.onFailure {
                models = emptyList()
                status = "Ollama offline · ${it.message ?: it::class.simpleName}"
            }
        }
    }

    fun reportProgress(taskId: String, runToken: Long, message: String) {
        if (message.isBlank() ||
            !coordinator.isCurrent(runToken, taskId) ||
            !isCurrentTask(taskId)
        ) return

        coordinator.addProgress(runToken, message)
        val previous = bubbles.lastOrNull()
        if (previous?.role != "status" || previous.text != message) {
            bubbles += ChatBubble("status", message)
            persistSession()
        }
    }

    fun applyOutcome(taskId: String, runToken: Long, outcome: WorkflowOutcome) {
        if (!coordinator.isCurrent(runToken, taskId) || !isCurrentTask(taskId)) return
        when (outcome) {
            is WorkflowOutcome.Finished -> {
                history = outcome.history
                pending = null
                currentTask = outcome.control.task
                bubbles += ChatBubble("assistant", outcome.text)
                coordinator.finish(runToken, "Done")
            }

            is WorkflowOutcome.NeedsConfirmation -> {
                pending = outcome.pending
                history = outcome.pending.history
                currentTask = outcome.pending.control.task
                if (outcome.pending.taskPlan.isNotEmpty() &&
                    bubbles.none { it.role == "status" && it.text.startsWith("Plan\n") }
                ) {
                    bubbles += ChatBubble(
                        "status",
                        outcome.pending.taskPlan.mapIndexed { i, step -> "${i + 1}. $step" }
                            .joinToString(prefix = "Plan\n", separator = "\n")
                    )
                }
                bubbles += ChatBubble(
                    "status",
                    "Approval required: ${outcome.pending.plan.request.tool} · ${outcome.pending.plan.reason}"
                )
                coordinator.pauseForApproval(runToken)
            }

            is WorkflowOutcome.Failed -> {
                history = outcome.history
                pending = null
                currentTask = outcome.control.task
                bubbles += ChatBubble("error", outcome.message)
                coordinator.finish(runToken, "Failed")
            }
        }
        busy = currentTask?.status in setOf(
            TaskStatus.PLANNING,
            TaskStatus.WAITING_MODEL,
            TaskStatus.EXECUTING,
            TaskStatus.VERIFYING
        )
        persistSession()
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        if (inferenceBackend == "ollama" && selectedModel.isBlank()) {
            bubbles += ChatBubble("error", "No Ollama model selected. Start Ollama and refresh models.")
            persistSession()
            return
        }

        input = ""
        val task = TaskState(
            id = UUID.randomUUID().toString(),
            projectId = null,
            goal = text,
            status = TaskStatus.WAITING_MODEL
        )
        currentTask = task
        bubbles += ChatBubble("user", text)
        val turnHistory = history + OllamaMessage("user", text)
        history = turnHistory
        busy = true
        persistSession()

        coordinator.launch(workScope, task.id, resetProgress = true) { runToken ->
            val outcome = try {
                val backend = modelClient()
                WorkflowRunner(backend, bridgeOrNull(), modelNameForRun()).run(
                    history = turnHistory,
                    task = task,
                    onProgress = { reportProgress(task.id, runToken, it) },
                    onState = { acceptControl(task.id, runToken, it) }
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (t: Throwable) {
                val failedTask = task.copy(
                    status = TaskStatus.FAILED,
                    errors = listOf(t.message ?: t.toString())
                )
                WorkflowOutcome.Failed(
                    t.message ?: t.toString(),
                    turnHistory,
                    AgentControlState(task = failedTask)
                )
            }
            applyOutcome(task.id, runToken, outcome)
        }
    }

    LaunchedEffect(Unit) {
        val task = currentTask
        if (task != null &&
            task.status in setOf(
                TaskStatus.PLANNING,
                TaskStatus.WAITING_MODEL,
                TaskStatus.EXECUTING,
                TaskStatus.VERIFYING
            ) &&
            !coordinator.active
        ) {
            currentTask = task.copy(
                status = TaskStatus.CANCELLED,
                errors = (task.errors + "Previous agent run was interrupted before this app session resumed.").takeLast(8)
            )
            bubbles += ChatBubble("status", "Previous unfinished run was marked cancelled after app restart.")
            busy = false
            persistSession()
        }
        refreshModels()
    }

    LaunchedEffect(input) {
        delay(300)
        persistSession()
    }

    LaunchedEffect(currentTask?.id) {
        while (currentTask?.status in setOf(
                TaskStatus.PLANNING,
                TaskStatus.WAITING_MODEL,
                TaskStatus.EXECUTING,
                TaskStatus.VERIFYING
            )) {
            delay(750)
            syncFromStoredSession()
        }
    }

    LaunchedEffect(coordinator.active, coordinator.startedAtMs) {
        while (coordinator.active) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Lumena", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (selectedModel.isBlank()) status else "$status · $selectedModel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                currentTask?.let { task ->
                    Text(
                        "Task: ${task.status.name.lowercase().replace('_', ' ')} · ${task.step}/${task.maxSteps}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row {
                TextButton(onClick = { clearConversation() }) {
                    Text("New")
                }
                TextButton(onClick = { showSettings = !showSettings }) {
                    Text(if (showSettings) "Hide" else "Model")
                }
            }
        }

        AgentProgressPanel(
            task = currentTask,
            coordinator = coordinator,
            pendingApproval = pending != null,
            nowMs = nowMs,
            onStop = { stopCurrentTask() }
        )

        if (showSettings) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Local model", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { inferenceBackend = "embedded"; LumenaPreferences.saveInferenceBackend(context, "embedded") }) { Text(if (inferenceBackend == "embedded") "✓ Embedded" else "Embedded") }
                        OutlinedButton(onClick = { inferenceBackend = "ollama"; LumenaPreferences.saveInferenceBackend(context, "ollama") }) { Text(if (inferenceBackend == "ollama") "✓ Ollama" else "Ollama") }
                    }
                    if (inferenceBackend == "embedded") {
                        OutlinedTextField(
                            value = ggufPath,
                            onValueChange = { ggufPath = it; LumenaPreferences.saveGgufPath(context, it) },
                            label = { Text("GGUF model path") },
                            supportingText = { Text("Example: /storage/emulated/0/Download/model.gguf") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        "Lumena restores the model automatically and keeps AgentController state outside the model.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = ollamaUrl,
                        onValueChange = {
                            ollamaUrl = it
                            LumenaPreferences.saveOllamaUrl(context, it)
                        },
                        label = { Text("Ollama URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { refreshModels() }) {
                            Text("Refresh models")
                        }
                        if (models.isNotEmpty()) {
                            TextButton(onClick = {
                                val current = models.indexOf(selectedModel).coerceAtLeast(0)
                                val next = models[(current + 1) % models.size]
                                selectedModel = next
                                LumenaPreferences.saveSelectedModel(context, next)
                            }) {
                                Text("Next model")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = {
                            selectedModel = it
                            LumenaPreferences.saveSelectedModel(context, it)
                        },
                        label = { Text("Model") },
                        supportingText = {
                            if (models.isNotEmpty()) Text(models.joinToString(" · "))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    HorizontalDivider()
                    Text("Termux tools", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Bridge settings are shared with Companion and Tools.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = bridgeUrl,
                        onValueChange = {
                            bridgeUrl = it
                            LumenaPreferences.saveBridgeUrl(context, it)
                        },
                        label = { Text("Bridge URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bridgeToken,
                        onValueChange = {
                            bridgeToken = it
                            LumenaPreferences.saveBridgeToken(context, it)
                        },
                        label = { Text("Bridge token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(bubbles) { bubble -> MessageBubble(bubble) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Message Lumena") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            Button(enabled = !busy && pending == null && input.isNotBlank(), onClick = { send() }) {
                Text("Send")
            }
        }
    }

    pending?.let { requested ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Allow local action?") },
            text = {
                val taskPlan = if (requested.taskPlan.isEmpty()) "" else
                    requested.taskPlan.mapIndexed { i, step -> "${i + 1}. $step" }
                        .joinToString(prefix = "Plan:\n", separator = "\n", postfix = "\n\n")
                val verify = requested.control.verificationReason
                    ?.let { "\n\nVerification pending: $it" }
                    .orEmpty()
                Text(
                    taskPlan +
                        "${requested.plan.reason}\n\nTool: ${requested.plan.request.tool}\nArgs: ${requested.plan.request.args}" +
                        verify +
                        "\n\nThis request survives tab switching."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val taskId = requested.control.task.id
                    pending = null
                    currentTask = requested.control.task.copy(status = TaskStatus.EXECUTING)
                    busy = true
                    persistSession()

                    coordinator.launch(workScope, taskId, resetProgress = false) { runToken ->
                        val outcome = try {
                            val backend = modelClient()
                            WorkflowRunner(backend, bridgeOrNull(), modelNameForRun()).approve(
                                pending = requested,
                                onProgress = { reportProgress(taskId, runToken, it) },
                                onState = { acceptControl(taskId, runToken, it) }
                            )
                        } catch (_: CancellationException) {
                            return@launch
                        } catch (t: Throwable) {
                            val failedControl = requested.control.copy(
                                task = requested.control.task.copy(
                                    status = TaskStatus.FAILED,
                                    errors = (requested.control.task.errors + (t.message ?: t.toString())).takeLast(8)
                                )
                            )
                            WorkflowOutcome.Failed(
                                t.message ?: t.toString(),
                                requested.history,
                                failedControl
                            )
                        }
                        applyOutcome(taskId, runToken, outcome)
                    }
                }) { Text("Allow once") }
            },
            dismissButton = {
                TextButton(onClick = {
                    stopCurrentTask("Approval cancelled by user")
                }) { Text("Cancel task") }
            }
        )
    }
}

@Composable
private fun AgentProgressPanel(
    task: TaskState?,
    coordinator: AgentRunCoordinator,
    pendingApproval: Boolean,
    nowMs: Long,
    onStop: () -> Unit
) {
    val activeStatus = task?.status in setOf(
        TaskStatus.PLANNING,
        TaskStatus.WAITING_MODEL,
        TaskStatus.EXECUTING,
        TaskStatus.VERIFYING,
        TaskStatus.WAITING_CONFIRMATION
    )
    if (task == null || (!activeStatus && coordinator.progress.isEmpty())) return

    val canStop = coordinator.active || pendingApproval || activeStatus
    val elapsed = if (coordinator.startedAtMs > 0L) {
        ((if (coordinator.active) nowMs else System.currentTimeMillis()) - coordinator.startedAtMs)
            .coerceAtLeast(0L)
    } else 0L

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Agent progress", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${task.status.name.lowercase().replace('_', ' ')} · step ${task.step}/${task.maxSteps} · ${formatElapsed(elapsed)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        coordinator.stage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (canStop) {
                    OutlinedButton(onClick = onStop) {
                        Text("STOP")
                    }
                }
            }

            if (coordinator.active) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            coordinator.progress.takeLast(5).forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun MessageBubble(message: ChatBubble) {
    val isUser = message.role == "user"
    val color = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer
        "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
        "status" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val horizontal = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontal
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.86f else 0.94f)
                .background(color, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
