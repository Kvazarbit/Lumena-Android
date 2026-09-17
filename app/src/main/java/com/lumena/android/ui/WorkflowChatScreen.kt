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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.PlannerDecision
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private data class ChatBubble(
    val role: String,
    val text: String
)

@Composable
fun WorkflowChatScreen(agentWorkScope: CoroutineScope? = null) {
    val uiScope = rememberCoroutineScope()
    val workScope = agentWorkScope ?: uiScope
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val initial = remember { LumenaPreferences.load(context) }
    val restored = remember { LocalSessionStore.load(context) }
    val systemMessage = remember { OllamaMessage("system", LocalWorkflowAgent.systemPrompt) }

    val bubbles = remember {
        mutableStateListOf<ChatBubble>().apply {
            val restoredChat = restored.chat.map { ChatBubble(it.role, it.text) }
            if (restoredChat.isNotEmpty()) {
                addAll(restoredChat)
            } else {
                add(
                    ChatBubble(
                        "assistant",
                        "Lumena local agent is ready. Chat, model and task state are restored automatically."
                    )
                )
            }
        }
    }

    var input by rememberSaveable { mutableStateOf(restored.inputDraft) }
    var ollamaUrl by rememberSaveable { mutableStateOf(initial.ollamaUrl) }
    var bridgeUrl by rememberSaveable { mutableStateOf(initial.bridgeUrl) }
    var bridgeToken by rememberSaveable { mutableStateOf(initial.bridgeToken) }
    var selectedModel by rememberSaveable { mutableStateOf(initial.selectedModel) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Checking Ollama…") }
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
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var currentTask by remember { mutableStateOf(restored.task) }
    var history by remember {
        mutableStateOf(
            listOf(systemMessage) + restored.history.map { OllamaMessage(it.role, it.content) }
        )
    }

    fun restoredPendingFrom(saved: PersistedPendingTool?): PendingWorkflowTool? = saved?.let {
        val plan = ToolGate.plan(
            PlannerDecision(
                request = ToolRequest(it.tool, it.args),
                reason = it.reason
            )
        )
        if (plan.allowed) {
            PendingWorkflowTool(
                plan = plan,
                history = listOf(systemMessage) + it.history.map { h ->
                    OllamaMessage(h.role, h.content)
                }
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
                        history = active.history
                            .filterNot { it.role == "system" }
                            .map { PersistedHistoryMessage(it.role, it.content) }
                    )
                },
                inputDraft = input
            )
        )
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

    fun clearConversation() {
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

    fun reportProgress(message: String) {
        if (message.isBlank()) return
        val previous = bubbles.lastOrNull()
        if (previous?.role != "status" || previous.text != message) {
            bubbles += ChatBubble("status", message)
        }

        if (message.startsWith("Plan\n")) {
            currentTask = currentTask?.copy(status = TaskStatus.PLANNING)
        } else if (message.startsWith("Step ")) {
            val parsedStep = message.substringAfter("Step ").substringBefore(':').trim().toIntOrNull()
            currentTask = currentTask?.copy(
                status = TaskStatus.EXECUTING,
                step = maxOf(currentTask?.step ?: 0, parsedStep ?: 0)
            )
        } else if (message.startsWith("Thinking")) {
            currentTask = currentTask?.copy(status = TaskStatus.WAITING_MODEL)
        }
        persistSession()
    }

    fun applyOutcome(outcome: WorkflowOutcome) {
        when (outcome) {
            is WorkflowOutcome.Finished -> {
                history = outcome.history
                pending = null
                bubbles += ChatBubble("assistant", outcome.text)
                currentTask = currentTask?.copy(
                    status = TaskStatus.DONE,
                    lastResult = outcome.text.take(4_000)
                )
            }

            is WorkflowOutcome.NeedsConfirmation -> {
                pending = outcome.pending
                if (outcome.pending.taskPlan.isNotEmpty() && bubbles.none { it.role == "status" && it.text.startsWith("Plan\n") }) {
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
                currentTask = currentTask?.copy(
                    status = TaskStatus.WAITING_CONFIRMATION,
                    lastTool = outcome.pending.plan.request.tool
                )
            }

            is WorkflowOutcome.Failed -> {
                pending = null
                bubbles += ChatBubble("error", outcome.message)
                currentTask = currentTask?.copy(
                    status = TaskStatus.FAILED,
                    errors = (currentTask?.errors.orEmpty() + outcome.message).takeLast(8)
                )
            }
        }
        busy = false
        persistSession()
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        if (selectedModel.isBlank()) {
            bubbles += ChatBubble("error", "No Ollama model selected. Start Ollama and refresh models.")
            persistSession()
            return
        }

        input = ""
        currentTask = TaskState(
            id = UUID.randomUUID().toString(),
            projectId = null,
            goal = text,
            status = TaskStatus.WAITING_MODEL
        )
        bubbles += ChatBubble("user", text)
        val turnHistory = history + OllamaMessage("user", text)
        history = turnHistory
        busy = true
        persistSession()

        workScope.launch {
            val outcome = runCatching {
                val ollama = OllamaClient(ollamaUrl)
                WorkflowRunner(ollama, bridgeOrNull(), selectedModel).run(
                    history = turnHistory,
                    maxSteps = 8,
                    onProgress = ::reportProgress
                )
            }.getOrElse { WorkflowOutcome.Failed(it.message ?: it.toString()) }
            applyOutcome(outcome)
        }
    }

    LaunchedEffect(Unit) {
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
                    Text("Local Ollama sidecar", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Lumena restores the last model automatically and rechecks Ollama whenever this screen opens.",
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
                        "Bridge URL and token are shared automatically with Companion and Tools.",
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
            items(bubbles) { bubble ->
                MessageBubble(bubble)
            }
            if (busy) {
                item {
                    Text(
                        "Lumena is working…",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
            Button(enabled = !busy && input.isNotBlank(), onClick = { send() }) {
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
                Text(
                    taskPlan +
                        "${requested.plan.reason}\n\nTool: ${requested.plan.request.tool}\nArgs: ${requested.plan.request.args}\n\nThis request survives tab switching."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    currentTask = currentTask?.copy(
                        status = TaskStatus.EXECUTING,
                        lastTool = requested.plan.request.tool
                    )
                    busy = true
                    persistSession()
                    workScope.launch {
                        val outcome = runCatching {
                            val ollama = OllamaClient(ollamaUrl)
                            WorkflowRunner(ollama, bridgeOrNull(), selectedModel).approve(
                                pending = requested,
                                onProgress = ::reportProgress
                            )
                        }.getOrElse { WorkflowOutcome.Failed(it.message ?: it.toString()) }
                        applyOutcome(outcome)
                    }
                }) { Text("Allow once") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    currentTask = currentTask?.copy(status = TaskStatus.CANCELLED)
                    bubbles += ChatBubble("status", "Local action cancelled by user.")
                    busy = false
                    persistSession()
                }) { Text("Cancel") }
            }
        )
    }
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
