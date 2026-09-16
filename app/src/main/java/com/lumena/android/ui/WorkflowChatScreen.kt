package com.lumena.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.chat.ChatBackend
import com.lumena.android.chat.EmbeddedLlamaBackend
import com.lumena.android.chat.EmbeddedModelFile
import com.lumena.android.chat.EmbeddedModelStore
import com.lumena.android.chat.OllamaChatBackend
import com.lumena.android.chat.humanFileSize
import com.lumena.android.ollama.LocalWorkflowAgent
import com.lumena.android.ollama.OllamaClient
import com.lumena.android.ollama.OllamaMessage
import com.lumena.android.ollama.PendingWorkflowTool
import com.lumena.android.ollama.WorkflowOutcome
import com.lumena.android.ollama.WorkflowRunner
import kotlinx.coroutines.launch

private const val PROVIDER_EMBEDDED = "embedded"
private const val PROVIDER_OLLAMA = "ollama"

private data class ChatBubble(
    val role: String,
    val text: String
)

@Composable
fun WorkflowChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val modelStore = remember { EmbeddedModelStore(context.applicationContext) }

    val bubbles = remember {
        mutableStateListOf(
            ChatBubble(
                "assistant",
                "Lumena workspace is ready. I can collaborate on projects through approved files, Git and Python tools. Choose an embedded GGUF or an Ollama model and talk normally."
            )
        )
    }

    var input by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf(PROVIDER_EMBEDDED) }
    var ollamaUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:11434") }
    var bridgeUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:8765") }
    var bridgeToken by rememberSaveable { mutableStateOf("") }
    var selectedOllamaModel by rememberSaveable { mutableStateOf("") }
    var selectedEmbeddedPath by rememberSaveable { mutableStateOf("") }

    var ollamaModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var embeddedModels by remember { mutableStateOf<List<EmbeddedModelFile>>(emptyList()) }
    var ollamaStatus by remember { mutableStateOf("Ollama: checking…") }
    var embeddedStatus by remember { mutableStateOf("Embedded: scanning…") }
    var busy by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(true) }
    var pending by remember { mutableStateOf<PendingWorkflowTool?>(null) }
    var history by remember {
        mutableStateOf(listOf(OllamaMessage("system", LocalWorkflowAgent.systemPrompt)))
    }

    fun bridgeOrNull(): TermuxBridgeClient? = bridgeToken
        .takeIf { it.isNotBlank() }
        ?.let { TermuxBridgeClient(bridgeUrl, it) }

    suspend fun scanEmbeddedModels() {
        val found = modelStore.listModels()
        embeddedModels = found
        if (selectedEmbeddedPath.isBlank() || found.none { it.path == selectedEmbeddedPath }) {
            selectedEmbeddedPath = found.firstOrNull()?.path.orEmpty()
        }
        embeddedStatus = if (found.isEmpty()) {
            "Embedded: no imported GGUF"
        } else {
            "Embedded: ${found.size} model(s)"
        }
    }

    suspend fun scanOllamaModels() {
        val result = try {
            OllamaClient(ollamaUrl).listModels()
        } catch (t: Throwable) {
            Result.failure(t)
        }
        result.onSuccess { found ->
            ollamaModels = found
            if (selectedOllamaModel.isBlank() || selectedOllamaModel !in found) {
                selectedOllamaModel = found.firstOrNull().orEmpty()
            }
            ollamaStatus = if (found.isEmpty()) {
                "Ollama: online · no models"
            } else {
                "Ollama: online · ${found.size} model(s)"
            }
            if (embeddedModels.isEmpty() && found.isNotEmpty() && selectedEmbeddedPath.isBlank()) {
                provider = PROVIDER_OLLAMA
            }
        }.onFailure {
            ollamaModels = emptyList()
            ollamaStatus = "Ollama: offline · ${it.message ?: it::class.simpleName}"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                modelStore.importModel(uri)
                    .onSuccess { imported ->
                        scanEmbeddedModels()
                        selectedEmbeddedPath = imported.path
                        provider = PROVIDER_EMBEDDED
                        bubbles += ChatBubble(
                            "status",
                            "✓ Imported ${imported.name} (${imported.sizeBytes.humanFileSize()}) for embedded llama.cpp"
                        )
                    }
                    .onFailure {
                        bubbles += ChatBubble("error", "Model import failed: ${it.message ?: it}")
                    }
                importing = false
            }
        }
    }

    fun selectedEmbedded(): EmbeddedModelFile? =
        embeddedModels.firstOrNull { it.path == selectedEmbeddedPath }

    fun currentBackend(): ChatBackend? = when (provider) {
        PROVIDER_EMBEDDED -> selectedEmbedded()?.let { model ->
            EmbeddedLlamaBackend(context.applicationContext, model.path, model.name)
        }
        PROVIDER_OLLAMA -> selectedOllamaModel.takeIf { it.isNotBlank() }?.let { model ->
            OllamaChatBackend(OllamaClient(ollamaUrl), model)
        }
        else -> null
    }

    fun applyOutcome(outcome: WorkflowOutcome) {
        when (outcome) {
            is WorkflowOutcome.Finished -> {
                history = outcome.history
                bubbles += ChatBubble("assistant", outcome.text)
            }
            is WorkflowOutcome.NeedsConfirmation -> {
                pending = outcome.pending
                bubbles += ChatBubble(
                    "status",
                    "Approval required: ${outcome.pending.plan.request.tool} · ${outcome.pending.plan.reason}"
                )
            }
            is WorkflowOutcome.Failed -> bubbles += ChatBubble("error", outcome.message)
        }
    }

    fun runnerFor(backend: ChatBackend): WorkflowRunner = WorkflowRunner(
        backend = backend,
        bridge = bridgeOrNull(),
        onEvent = { event -> bubbles += ChatBubble("status", event) }
    )

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy || importing) return
        val backend = currentBackend()
        if (backend == null) {
            bubbles += ChatBubble(
                "error",
                if (provider == PROVIDER_EMBEDDED)
                    "No embedded GGUF selected. Import a GGUF model or switch to Ollama."
                else
                    "No Ollama model selected. Start Ollama or switch to Embedded."
            )
            return
        }

        input = ""
        bubbles += ChatBubble("user", text)
        val turnHistory = history + OllamaMessage("user", text)
        history = turnHistory
        busy = true

        scope.launch {
            val outcome = runCatching {
                runnerFor(backend).run(turnHistory)
            }.getOrElse { WorkflowOutcome.Failed(it.message ?: it.toString()) }
            applyOutcome(outcome)
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        scanEmbeddedModels()
        scanOllamaModels()
    }

    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.lastIndex)
    }

    val activeModelLabel = when (provider) {
        PROVIDER_EMBEDDED -> selectedEmbedded()?.let { "Embedded · ${it.name}" } ?: embeddedStatus
        else -> selectedOllamaModel.takeIf { it.isNotBlank() }?.let { "Ollama · $it" } ?: ollamaStatus
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
                    activeModelLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Hide setup" else "Models")
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
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("Model engine", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (provider == PROVIDER_EMBEDDED) {
                            Button(onClick = {}) { Text("Embedded llama.cpp") }
                            OutlinedButton(onClick = { provider = PROVIDER_OLLAMA }) { Text("Ollama") }
                        } else {
                            OutlinedButton(onClick = { provider = PROVIDER_EMBEDDED }) { Text("Embedded llama.cpp") }
                            Button(onClick = { provider = PROVIDER_OLLAMA }) { Text("Ollama") }
                        }
                    }

                    Text(embeddedStatus, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !importing && !busy,
                            onClick = { importLauncher.launch(arrayOf("*/*")) }
                        ) {
                            Text(if (importing) "Importing…" else "Import GGUF")
                        }
                        if (embeddedModels.size > 1) {
                            TextButton(onClick = {
                                val current = embeddedModels.indexOfFirst { it.path == selectedEmbeddedPath }
                                    .coerceAtLeast(0)
                                selectedEmbeddedPath = embeddedModels[(current + 1) % embeddedModels.size].path
                                provider = PROVIDER_EMBEDDED
                            }) { Text("Next embedded") }
                        }
                    }
                    selectedEmbedded()?.let { model ->
                        Text(
                            "${model.name} · ${model.sizeBytes.humanFileSize()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    HorizontalDivider()
                    Text(ollamaStatus, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = ollamaUrl,
                        onValueChange = { ollamaUrl = it },
                        label = { Text("Ollama URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            scope.launch { scanOllamaModels() }
                        }) { Text("Refresh Ollama") }
                        if (ollamaModels.size > 1) {
                            TextButton(onClick = {
                                val current = ollamaModels.indexOf(selectedOllamaModel).coerceAtLeast(0)
                                selectedOllamaModel = ollamaModels[(current + 1) % ollamaModels.size]
                                provider = PROVIDER_OLLAMA
                            }) { Text("Next Ollama") }
                        }
                    }
                    if (selectedOllamaModel.isNotBlank()) {
                        Text("Selected: $selectedOllamaModel", style = MaterialTheme.typography.bodySmall)
                    }

                    HorizontalDivider()
                    Text("Workspace tools", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Read-only inspection can run automatically. File writes, project creation, Git changes and Python execution always ask before running.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = bridgeUrl,
                        onValueChange = { bridgeUrl = it },
                        label = { Text("Termux bridge URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bridgeToken,
                        onValueChange = { bridgeToken = it },
                        label = { Text("Bridge token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { input = "Покажи структуру workspace і коротко поясни, над якими проєктами ми можемо працювати." }) {
                            Text("Workspace")
                        }
                        TextButton(onClick = { input = "Створи новий Python-проєкт. Спочатку запропонуй коротку назву та структуру, потім створи його через project.create." }) {
                            Text("New project")
                        }
                        TextButton(onClick = { input = "Перевір git status активного проєкту і скажи, що варто зробити далі." }) {
                            Text("Git")
                        }
                    }
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
                maxLines = 5,
                modifier = Modifier.weight(1f)
            )
            Button(enabled = !busy && !importing && input.isNotBlank(), onClick = { send() }) {
                Text("Send")
            }
        }
    }

    pending?.let { requested ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Allow workspace action?") },
            text = {
                Text(
                    "${requested.plan.reason}\n\nTool: ${requested.plan.request.tool}\nArgs: ${requested.plan.request.args}"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val backend = currentBackend()
                    if (backend == null) {
                        pending = null
                        bubbles += ChatBubble("error", "The selected model is no longer available.")
                        return@TextButton
                    }
                    pending = null
                    busy = true
                    scope.launch {
                        val outcome = runCatching {
                            runnerFor(backend).approve(requested)
                        }.getOrElse { WorkflowOutcome.Failed(it.message ?: it.toString()) }
                        applyOutcome(outcome)
                        busy = false
                    }
                }) { Text("Allow once") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    bubbles += ChatBubble("status", "Local action cancelled by user.")
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
