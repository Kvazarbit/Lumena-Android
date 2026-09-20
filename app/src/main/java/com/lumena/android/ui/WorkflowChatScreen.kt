package com.lumena.android.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
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
import com.lumena.android.llama.EmbeddedLlamaRuntime
import com.lumena.android.llama.LlamaHardwareProfile
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

private data class ChatBubble(val role: String, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowChatScreen(
    agentWorkScope: CoroutineScope? = null,
    runCoordinator: AgentRunCoordinator? = null,
    onOpenHistory: (() -> Unit)? = null,
    onOpenAgent: (() -> Unit)? = null
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
            else add(ChatBubble("assistant", "Привіт. Я Lumena. Чим можу допомогти?"))
        }
    }

    var input by rememberSaveable { mutableStateOf(restored.inputDraft) }
    var ollamaUrl by rememberSaveable { mutableStateOf(initial.ollamaUrl) }
    var bridgeUrl by rememberSaveable { mutableStateOf(initial.bridgeUrl) }
    var bridgeToken by rememberSaveable { mutableStateOf(initial.bridgeToken) }
    var selectedModel by rememberSaveable { mutableStateOf(initial.selectedModel) }
    var inferenceBackend by rememberSaveable { mutableStateOf(initial.inferenceBackend) }
    var ggufPath by rememberSaveable { mutableStateOf(initial.ggufPath) }
    var ggufPickerStatus by rememberSaveable { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Checking Ollama…") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var progressExpanded by rememberSaveable { mutableStateOf(false) }
    var currentTask by remember { mutableStateOf(restored.task) }
    var history by remember {
        mutableStateOf(listOf(systemMessage) + restored.history.map { OllamaMessage(it.role, it.content) })
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

    val ggufDisplayName = remember(ggufPath) { resolveGgufDisplayName(context, ggufPath) }
    val hardwareProfile = remember(showSettings, busy) { LlamaHardwareProfile.detect(context) }
    val modelLabel = when {
        inferenceBackend == "embedded" && ggufDisplayName.isNotBlank() -> ggufDisplayName
        inferenceBackend == "embedded" -> "Choose GGUF"
        selectedModel.isNotBlank() -> selectedModel
        else -> "No model"
    }
    val backendLabel = if (inferenceBackend == "embedded") {
        val runtime = EmbeddedLlamaRuntime.backendLabel()
        if (runtime == "not loaded yet") "Local" else runtime
    } else "Ollama"

    val ggufPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val selectedName = resolveGgufDisplayName(context, uri.toString())
            if (!selectedName.endsWith(".gguf", ignoreCase = true)) {
                ggufPickerStatus = "Choose a .gguf model file."
            } else {
                val permissionSaved = runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess
                EmbeddedLlamaClient.cancelActiveGeneration()
                uiScope.launch { EmbeddedLlamaRuntime.unload() }
                ggufPath = uri.toString()
                inferenceBackend = "embedded"
                LumenaPreferences.saveGgufPath(context, ggufPath)
                LumenaPreferences.saveInferenceBackend(context, "embedded")
                ggufPickerStatus = if (permissionSaved) "✓ $selectedName · access saved" else "✓ $selectedName · selected"
            }
        }
    }

    fun restoredPendingFrom(saved: PersistedPendingTool?): PendingWorkflowTool? = saved?.let {
        val planned = ToolGate.plan(PlannerDecision(request = ToolRequest(it.tool, it.args), reason = it.reason))
        val control = it.control ?: currentTask?.let { task -> AgentControlState(task = task) } ?: return@let null
        if (planned.allowed) {
            PendingWorkflowTool(
                plan = planned,
                history = listOf(systemMessage) + it.history.map { h -> OllamaMessage(h.role, h.content) },
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
                history = history.filterNot { it.role == "system" }
                    .map { PersistedHistoryMessage(it.role, it.content) },
                task = currentTask,
                pending = pending?.let { active ->
                    PersistedPendingTool(
                        tool = active.plan.request.tool,
                        args = active.plan.request.args,
                        reason = active.plan.reason,
                        control = active.control,
                        history = active.history.filterNot { it.role == "system" }
                            .map { PersistedHistoryMessage(it.role, it.content) }
                    )
                },
                inputDraft = input
            )
        )
    }

    fun isCurrentTask(taskId: String): Boolean = LocalSessionStore.load(context).task?.id == taskId

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

        val storedHistory = listOf(systemMessage) + stored.history.map { OllamaMessage(it.role, it.content) }
        if (storedHistory != history) history = storedHistory

        val storedPending = restoredPendingFrom(stored.pending)
        if (storedPending?.plan?.request != pending?.plan?.request) pending = storedPending

        busy = stored.task?.status in setOf(
            TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING
        )
    }

    fun stopCurrentTask(reason: String = "Stopped by user") {
        val task = currentTask ?: return
        EmbeddedLlamaClient.cancelActiveGeneration()
        coordinator.cancel(reason)
        pending = null
        busy = false
        currentTask = task.copy(status = TaskStatus.CANCELLED)
        persistSession()
    }

    fun clearConversation() {
        EmbeddedLlamaClient.cancelActiveGeneration()
        coordinator.cancel("New conversation")
        coordinator.clearFinished()
        input = ""
        pending = null
        currentTask = null
        history = listOf(systemMessage)
        bubbles.clear()
        bubbles += ChatBubble("assistant", "Новий чат. Що хочеш зробити?")
        busy = false
        LocalSessionStore.clear(context)
        persistSession()
    }

    fun bridgeOrNull(): TermuxBridgeClient? = bridgeToken.takeIf { it.isNotBlank() }
        ?.let { TermuxBridgeClient(bridgeUrl, it, context) }

    fun modelClient(): ChatModelClient =
        if (inferenceBackend == "embedded") EmbeddedLlamaClient(context, ggufPath) else OllamaClient(ollamaUrl)

    fun modelNameForRun(): String = if (inferenceBackend == "embedded") "embedded-gguf" else selectedModel

    fun refreshModels() {
        status = "Checking Ollama…"
        uiScope.launch {
            val result = try { OllamaClient(ollamaUrl).listModels() } catch (t: Throwable) { Result.failure(t) }
            result.onSuccess { found ->
                models = found
                val resolved = when {
                    selectedModel.isNotBlank() && selectedModel in found -> selectedModel
                    found.isNotEmpty() -> found.first()
                    else -> selectedModel
                }
                if (resolved != selectedModel) {
                    selectedModel = resolved
                    LumenaPreferences.saveSelectedModel(context, resolved)
                }
                status = if (found.isEmpty()) "Ollama online · no local models" else "Ollama online"
            }.onFailure {
                models = emptyList()
                status = "Ollama offline"
            }
        }
    }

    fun reportProgress(taskId: String, runToken: Long, message: String) {
        if (message.isBlank() || !coordinator.isCurrent(runToken, taskId) || !isCurrentTask(taskId)) return
        coordinator.addProgress(runToken, message)
        // Progress belongs to the compact agent status, not the conversation itself.
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
            TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING
        )
        persistSession()
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        if (inferenceBackend == "embedded" && ggufPath.isBlank()) {
            showSettings = true
            ggufPickerStatus = "Choose a GGUF model first."
            return
        }
        if (inferenceBackend == "ollama" && selectedModel.isBlank()) {
            showSettings = true
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
                WorkflowRunner(modelClient(), bridgeOrNull(), modelNameForRun()).run(
                    history = turnHistory,
                    task = task,
                    onProgress = { reportProgress(task.id, runToken, it) },
                    onModelText = { text ->
                        if (text.isEmpty()) coordinator.beginModelTurn(runToken)
                        else coordinator.updateModelText(runToken, text)
                    },
                    onState = { acceptControl(task.id, runToken, it) }
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (t: Throwable) {
                val failedTask = task.copy(status = TaskStatus.FAILED, errors = listOf(t.message ?: t.toString()))
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
            task.status in setOf(TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING) &&
            !coordinator.active
        ) {
            currentTask = task.copy(
                status = TaskStatus.CANCELLED,
                errors = (task.errors + "Previous agent run was interrupted before this app session resumed.").takeLast(8)
            )
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
                TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING
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
            .imePadding()
    ) {
        ModernChatHeader(
            modelLabel = modelLabel,
            backendLabel = backendLabel,
            busy = busy,
            onHistory = { onOpenHistory?.invoke() },
            onNew = { clearConversation() },
            onMore = {
                if (onOpenAgent != null) onOpenAgent() else showSettings = true
            },
            onModel = { showSettings = true }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(
                items = bubbles.filter { it.role != "status" },
                key = { it.hashCode() }
            ) { bubble ->
                MessageBubble(bubble)
            }
        }

        CompactAgentStatus(
            task = currentTask,
            coordinator = coordinator,
            pendingApproval = pending != null,
            nowMs = nowMs,
            expanded = progressExpanded,
            onToggle = { progressExpanded = !progressExpanded },
            onStop = { stopCurrentTask() }
        )

        ModernComposer(
            value = input,
            onValueChange = { input = it },
            busy = busy,
            canSend = pending == null && input.isNotBlank(),
            onPlus = { showSettings = true },
            onSend = { send() },
            onStop = { stopCurrentTask() }
        )
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ModelAndConnectionSheet(
                inferenceBackend = inferenceBackend,
                onBackend = {
                    inferenceBackend = it
                    LumenaPreferences.saveInferenceBackend(context, it)
                },
                ggufPath = ggufPath,
                ggufDisplayName = ggufDisplayName,
                ggufPickerStatus = ggufPickerStatus,
                hardwareSummary = hardwareProfile.summary,
                runtimeSummary = EmbeddedLlamaRuntime.backendLabel(),
                busy = busy,
                onChooseGguf = { ggufPicker.launch(arrayOf("*/*")) },
                onForgetGguf = {
                    EmbeddedLlamaClient.cancelActiveGeneration()
                    uiScope.launch { EmbeddedLlamaRuntime.unload() }
                    ggufPath = ""
                    ggufPickerStatus = "GGUF selection cleared."
                    LumenaPreferences.saveGgufPath(context, "")
                },
                ollamaUrl = ollamaUrl,
                onOllamaUrl = {
                    ollamaUrl = it
                    LumenaPreferences.saveOllamaUrl(context, it)
                },
                selectedModel = selectedModel,
                onSelectedModel = {
                    selectedModel = it
                    LumenaPreferences.saveSelectedModel(context, it)
                },
                models = models,
                status = status,
                onRefreshModels = { refreshModels() },
                bridgeUrl = bridgeUrl,
                onBridgeUrl = {
                    bridgeUrl = it
                    LumenaPreferences.saveBridgeUrl(context, it)
                },
                bridgeToken = bridgeToken,
                onBridgeToken = {
                    bridgeToken = it
                    LumenaPreferences.saveBridgeToken(context, it)
                }
            )
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
                        verify
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
                            WorkflowRunner(modelClient(), bridgeOrNull(), modelNameForRun()).approve(
                                pending = requested,
                                onProgress = { reportProgress(taskId, runToken, it) },
                                onModelText = { text ->
                                    if (text.isEmpty()) coordinator.beginModelTurn(runToken)
                                    else coordinator.updateModelText(runToken, text)
                                },
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
                            WorkflowOutcome.Failed(t.message ?: t.toString(), requested.history, failedControl)
                        }
                        applyOutcome(taskId, runToken, outcome)
                    }
                }) { Text("Allow once") }
            },
            dismissButton = {
                TextButton(onClick = { stopCurrentTask("Approval cancelled by user") }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ModernChatHeader(
    modelLabel: String,
    backendLabel: String,
    busy: Boolean,
    onHistory: () -> Unit,
    onNew: () -> Unit,
    onMore: () -> Unit,
    onModel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.clickable(onClick = onHistory),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text("☰", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onModel).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Lumena", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (busy) "Thinking…" else "$backendLabel · $modelLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Surface(
            modifier = Modifier.clickable(onClick = onNew),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text("✎", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(1.dp).padding(horizontal = 2.dp))
        TextButton(onClick = onMore) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable
private fun CompactAgentStatus(
    task: TaskState?,
    coordinator: AgentRunCoordinator,
    pendingApproval: Boolean,
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit
) {
    val active = coordinator.active || pendingApproval || task?.status in setOf(
        TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING,
        TaskStatus.VERIFYING, TaskStatus.WAITING_CONFIRMATION
    )
    if (!active) return

    val elapsed = if (coordinator.startedAtMs > 0L) {
        ((if (coordinator.active) nowMs else System.currentTimeMillis()) - coordinator.startedAtMs).coerceAtLeast(0L)
    } else 0L

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).clickable(onClick = onToggle)) {
                    Text(
                        when {
                            pendingApproval -> "Waiting for approval"
                            coordinator.stage.isNotBlank() -> coordinator.stage
                            else -> "Thinking…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        buildString {
                            task?.let { append("step ${it.step}/${it.maxSteps}") }
                            if (elapsed > 0) {
                                if (isNotEmpty()) append(" · ")
                                append(formatElapsed(elapsed))
                            }
                            append(if (expanded) " · hide details" else " · details")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onStop) { Text("Stop") }
            }
            if (coordinator.active) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            if (expanded) {
                coordinator.progress.takeLast(8).forEach {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernComposer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    canSend: Boolean,
    onPlus: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(30.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextButton(onClick = onPlus) { Text("＋", style = MaterialTheme.typography.headlineSmall) }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Message Lumena") },
                minLines = 1,
                maxLines = 5,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            )
            if (busy) {
                Button(onClick = onStop, shape = RoundedCornerShape(22.dp)) { Text("■") }
            } else {
                Button(
                    enabled = canSend,
                    onClick = onSend,
                    shape = RoundedCornerShape(22.dp)
                ) { Text("↑") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatBubble) {
    val isUser = message.role == "user"
    if (message.role == "error") {
        FriendlyErrorBubble(message.text)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Surface(
                modifier = Modifier.widthIn(max = 330.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(message.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        } else {
            Text(
                message.text,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun FriendlyErrorBubble(raw: String) {
    var details by remember(raw) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Не вдалося завершити запит.", fontWeight = FontWeight.SemiBold)
            Text(
                if (raw.contains("Unknown tool", ignoreCase = true)) "Модель запросила невідомий інструмент." else "Відкрий деталі, щоб побачити технічну причину.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details") }
            if (details) Text(raw, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelAndConnectionSheet(
    inferenceBackend: String,
    onBackend: (String) -> Unit,
    ggufPath: String,
    ggufDisplayName: String,
    ggufPickerStatus: String,
    hardwareSummary: String,
    runtimeSummary: String,
    busy: Boolean,
    onChooseGguf: () -> Unit,
    onForgetGguf: () -> Unit,
    ollamaUrl: String,
    onOllamaUrl: (String) -> Unit,
    selectedModel: String,
    onSelectedModel: (String) -> Unit,
    models: List<String>,
    status: String,
    onRefreshModels: () -> Unit,
    bridgeUrl: String,
    onBridgeUrl: (String) -> Unit,
    bridgeToken: String,
    onBridgeToken: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Model", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onBackend("embedded") }) {
                Text(if (inferenceBackend == "embedded") "✓ Embedded" else "Embedded")
            }
            OutlinedButton(onClick = { onBackend("ollama") }) {
                Text(if (inferenceBackend == "ollama") "✓ Ollama" else "Ollama")
            }
        }

        if (inferenceBackend == "embedded") {
            Text(if (ggufPath.isBlank()) "No GGUF selected" else ggufDisplayName, fontWeight = FontWeight.Medium)
            Text("Auto: $hardwareSummary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Inference: $runtimeSummary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = onChooseGguf) { Text(if (ggufPath.isBlank()) "Choose GGUF" else "Change GGUF") }
                if (ggufPath.isNotBlank()) TextButton(enabled = !busy, onClick = onForgetGguf) { Text("Forget") }
            }
            if (ggufPickerStatus.isNotBlank()) Text(ggufPickerStatus, style = MaterialTheme.typography.labelSmall)
        } else {
            Text(status, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = selectedModel,
                onValueChange = onSelectedModel,
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                supportingText = { if (models.isNotEmpty()) Text(models.joinToString(" · ")) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefreshModels) { Text("Refresh") }
                if (models.isNotEmpty()) {
                    TextButton(onClick = {
                        val current = models.indexOf(selectedModel).coerceAtLeast(0)
                        onSelectedModel(models[(current + 1) % models.size])
                    }) { Text("Next") }
                }
            }
            OutlinedTextField(
                value = ollamaUrl,
                onValueChange = onOllamaUrl,
                label = { Text("Ollama URL") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        HorizontalDivider()
        Text("Local tools", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = bridgeUrl,
            onValueChange = onBridgeUrl,
            label = { Text("Bridge URL") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = bridgeToken,
            onValueChange = onBridgeToken,
            label = { Text("Bridge token") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(18.dp))
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun resolveGgufDisplayName(context: android.content.Context, modelRef: String): String {
    if (modelRef.isBlank()) return ""
    if (!modelRef.startsWith("content://")) return modelRef.substringAfterLast('/').ifBlank { modelRef }
    val uri = runCatching { Uri.parse(modelRef) }.getOrNull() ?: return "Selected GGUF"
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull().orEmpty().ifBlank { "Selected GGUF" }
}
