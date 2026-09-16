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
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.ollama.LocalWorkflowAgent
import com.lumena.android.ollama.OllamaClient
import com.lumena.android.ollama.OllamaMessage
import com.lumena.android.ollama.PendingWorkflowTool
import com.lumena.android.ollama.WorkflowOutcome
import com.lumena.android.ollama.WorkflowRunner
import kotlinx.coroutines.launch

private data class ChatBubble(
    val role: String,
    val text: String
)

@Composable
fun WorkflowChatScreen() {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val bubbles = remember {
        mutableStateListOf(
            ChatBubble(
                "assistant",
                "Lumena local agent is ready. Connect Ollama, choose a model, then talk normally. I can also use approved Termux/Python/Git tools."
            )
        )
    }

    var input by rememberSaveable { mutableStateOf("") }
    var ollamaUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:11434") }
    var bridgeUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:8765") }
    var bridgeToken by rememberSaveable { mutableStateOf("") }
    var selectedModel by rememberSaveable { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Ollama not checked") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(true) }
    var pending by remember { mutableStateOf<PendingWorkflowTool?>(null) }
    var history by remember {
        mutableStateOf(
            listOf(OllamaMessage("system", LocalWorkflowAgent.systemPrompt))
        )
    }

    fun bridgeOrNull(): TermuxBridgeClient? = bridgeToken
        .takeIf { it.isNotBlank() }
        ?.let { TermuxBridgeClient(bridgeUrl, it) }

    fun refreshModels() {
        busy = true
        scope.launch {
            val result = try {
                OllamaClient(ollamaUrl).listModels()
            } catch (t: Throwable) {
                Result.failure(t)
            }
            result.onSuccess { found ->
                models = found
                if (selectedModel.isBlank() || selectedModel !in found) {
                    selectedModel = found.firstOrNull().orEmpty()
                }
                status = if (found.isEmpty()) "Ollama online · no local models" else "Ollama online · ${found.size} model(s)"
            }.onFailure {
                models = emptyList()
                status = "Ollama offline · ${it.message ?: it::class.simpleName}"
            }
            busy = false
        }
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
            is WorkflowOutcome.Failed -> {
                bubbles += ChatBubble("error", outcome.message)
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        if (selectedModel.isBlank()) {
            bubbles += ChatBubble("error", "No Ollama model selected. Open Local model settings and refresh models.")
            return
        }

        input = ""
        bubbles += ChatBubble("user", text)
        val turnHistory = history + OllamaMessage("user", text)
        history = turnHistory
        busy = true

        scope.launch {
            val outcome = runCatching {
                val ollama = OllamaClient(ollamaUrl)
                WorkflowRunner(ollama, bridgeOrNull(), selectedModel).run(turnHistory)
            }.getOrElse { WorkflowOutcome.Failed(it.message ?: it.toString()) }
            applyOutcome(outcome)
            busy = false
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
            Column {
                Text("Lumena", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (selectedModel.isBlank()) status else "$status · $selectedModel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Hide setup" else "Local model")
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
                        "Ollama stays on this phone at 127.0.0.1. Lumena never connects this provider to Wi-Fi addresses.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = ollamaUrl,
                        onValueChange = { ollamaUrl = it },
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
                                selectedModel = models[(current + 1) % models.size]
                            }) {
                                Text("Next model")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = { selectedModel = it },
                        label = { Text("Model") },
                        supportingText = {
                            if (models.isNotEmpty()) Text(models.joinToString(" · "))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    HorizontalDivider()
                    Text("Termux tools", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = bridgeUrl,
                        onValueChange = { bridgeUrl = it },
                        label = { Text("Bridge URL") },
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
            onDismissRequest = { pending = null },
            title = { Text("Allow local action?") },
            text = {
                Text(
                    "${requested.plan.reason}\n\nTool: ${requested.plan.request.tool}\nArgs: ${requested.plan.request.args}"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    busy = true
                    scope.launch {
                        val outcome = runCatching {
                            val ollama = OllamaClient(ollamaUrl)
                            WorkflowRunner(ollama, bridgeOrNull(), selectedModel).approve(requested)
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
