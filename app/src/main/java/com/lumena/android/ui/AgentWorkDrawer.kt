package com.lumena.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.runtime.AgentRunCoordinator
import com.lumena.android.settings.LumenaDiagnosticReport
import kotlinx.coroutines.launch

@Composable
fun AgentWorkDrawer(
    task: TaskState?,
    coordinator: AgentRunCoordinator,
    model: String,
    pendingApproval: Boolean,
    onStop: () -> Unit,
    onClose: () -> Unit
) {
    var modelOpen by remember { mutableStateOf(true) }
    var taskOpen by remember { mutableStateOf(false) }
    var eventsOpen by remember { mutableStateOf(true) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var diagnosticText by remember { mutableStateOf("") }
    var diagnosticStatus by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val running = task?.status in setOf(
        TaskStatus.PLANNING,
        TaskStatus.WAITING_MODEL,
        TaskStatus.EXECUTING,
        TaskStatus.VERIFYING,
        TaskStatus.WAITING_CONFIRMATION
    )

    Column(
        Modifier
            .width(350.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Agent", style = MaterialTheme.typography.headlineSmall)
                Text(model.ifBlank { "Local model" }, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onClose) { Text("Close") }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        pendingApproval -> "? Waiting for approval"
                        running -> "● ${coordinator.stage}"
                        task?.status == TaskStatus.DONE -> "✓ Done"
                        task?.status == TaskStatus.PARTIAL -> "◐ Частково виконано"
                        task?.status == TaskStatus.FAILED -> "! Failed"
                        task?.status == TaskStatus.CANCELLED -> "■ Cancelled"
                        else -> "○ Idle"
                    },
                    fontWeight = FontWeight.SemiBold
                )
                task?.let {
                    Text(
                        if (it.step == 0 && running) "Planning task budget…"
                        else "Step ${it.step}/${it.maxSteps}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (running || pendingApproval) {
                    OutlinedButton(onClick = onStop) { Text("STOP") }
                }
            }
        }

        HorizontalDivider()

        Text(
            if (modelOpen) "▼ Live model output · turn ${coordinator.modelTurn}"
            else "▶ Live model output · turn ${coordinator.modelTurn}",
            modifier = Modifier.fillMaxWidth().clickable { modelOpen = !modelOpen },
            style = MaterialTheme.typography.titleSmall
        )

        if (modelOpen) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                SelectionContainer {
                    Text(
                        coordinator.liveModelText.ifBlank {
                            if (running) "Waiting for model output…" else "No model output yet."
                        },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(
                "Shows text the model actually emits to Lumena. It is not a hidden internal reasoning trace.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (coordinator.liveToolTelemetry.isNotBlank()) {
            HorizontalDivider()
            Text(
                "▼ Live process health",
                style = MaterialTheme.typography.titleSmall
            )
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                SelectionContainer {
                    Text(
                        coordinator.liveToolTelemetry,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(
                "Live telemetry for bridge-started executable tools: PID, elapsed time and RSS when available.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        task?.let { current ->
            HorizontalDivider()
            Text(
                if (taskOpen) "▼ Task details" else "▶ Task details",
                Modifier.fillMaxWidth().clickable { taskOpen = !taskOpen },
                style = MaterialTheme.typography.titleSmall
            )
            if (taskOpen) {
                Text("Goal\n${current.goal}", style = MaterialTheme.typography.bodySmall)
                Text("Локальне ядро контексту · ${com.lumena.android.agent.core.CoreDna.VERSION}", style = MaterialTheme.typography.titleSmall)
                Text("Кроків залишилось: ${(current.maxSteps - current.step).coerceAtLeast(0)} · доказів ${current.kernel.evidence.size}/${current.kernel.observed}", style = MaterialTheme.typography.bodySmall)
                current.kernel.inFlight?.let { Text("Результат ще не зафіксовано: ${it.tool}. Автоматичного повтору немає.", color = MaterialTheme.colorScheme.error) }
                if (current.kernel.pendingVerification.isNotEmpty()) Text("Ще перевірити: ${current.kernel.pendingVerification.joinToString()}", style = MaterialTheme.typography.bodySmall)
                current.kernel.evidence.groupBy { it.phase }.forEach { (phase, events) ->
                    Text(when (phase) {
                        com.lumena.android.agent.core.CognitivePhase.OBSERVE -> "Спостереження"
                        com.lumena.android.agent.core.CognitivePhase.ACT -> "Дії"
                        com.lumena.android.agent.core.CognitivePhase.VERIFY -> "Перевірки"
                    }, style = MaterialTheme.typography.labelLarge)
                    events.takeLast(4).forEach { event ->
                        Text("${event.id} · ${if (event.ok) "✓" else "✕"} ${event.tool} · ${event.target}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Доказ виконання інструмента не підтверджує всю мету. Записи збережені локально.", style = MaterialTheme.typography.labelSmall)
                current.lastTool?.let {
                    Text("Last tool\n$it", style = MaterialTheme.typography.bodySmall)
                }
                current.lastResult?.let {
                    SelectionContainer {
                        Text(
                            "Last result\n${it.take(4_000)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (current.errors.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            "Errors\n${current.errors.takeLast(3).joinToString("\n")}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        Text(
            if (eventsOpen) "▼ Execution trace (${coordinator.progress.size})"
            else "▶ Execution trace (${coordinator.progress.size})",
            Modifier.fillMaxWidth().clickable { eventsOpen = !eventsOpen },
            style = MaterialTheme.typography.titleSmall
        )

        if (eventsOpen) {
            coordinator.progress.takeLast(24).forEach { event ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    SelectionContainer {
                        Text(
                            event,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (
                                event.startsWith("MODEL REPLY") ||
                                event.startsWith("TOOL RESULT")
                            ) FontFamily.Monospace else FontFamily.Default
                        )
                    }
                }
            }
        }

        Text(
            "Trace includes public model output, tool calls, arguments, tool results, retries and corrections.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        Text(
            if (diagnosticsOpen) "▼ Diagnostics"
            else "▶ Diagnostics",
            Modifier
                .fillMaxWidth()
                .clickable {
                    diagnosticsOpen = !diagnosticsOpen
                },
            style = MaterialTheme.typography.titleSmall
        )

        if (diagnosticsOpen) {
            Text(
                "App-owned report: package/version, Bridge/Ollama probes, TaskState, ContextKernel, Evidence Graph and Constitution Genome. The bridge token is never included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        diagnosticStatus =
                            "Collecting diagnostics…"
                        scope.launch {
                            diagnosticText =
                                runCatching {
                                    LumenaDiagnosticReport
                                        .build(context)
                                }.getOrElse {
                                    "LUMENA_DIAGNOSTIC_V1\n" +
                                        "report_error=" +
                                        (
                                            it.message
                                                ?: it::class
                                                    .simpleName
                                                ?: "unknown"
                                            )
                                                .replace(
                                                    Regex(
                                                        "[\\r\\n]+"
                                                    ),
                                                    " "
                                                )
                                }
                            diagnosticStatus =
                                "Report ready · " +
                                    diagnosticText.length +
                                    " chars"
                        }
                    }
                ) {
                    Text("Generate report")
                }

                OutlinedButton(
                    enabled =
                        diagnosticText.isNotBlank(),
                    onClick = {
                        val clipboard =
                            context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as? ClipboardManager
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(
                                "Lumena diagnostic report",
                                diagnosticText
                            )
                        )
                        diagnosticStatus =
                            if (clipboard != null) {
                                "Copied to clipboard"
                            } else {
                                "Clipboard unavailable"
                            }
                    }
                ) {
                    Text("Copy report")
                }
            }

            if (diagnosticStatus.isNotBlank()) {
                Text(
                    diagnosticStatus,
                    style =
                        MaterialTheme.typography.labelSmall
                )
            }

            if (diagnosticText.isNotBlank()) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme
                                    .colorScheme.surface
                        )
                ) {
                    SelectionContainer {
                        Text(
                            diagnosticText,
                            modifier =
                                Modifier.padding(10.dp),
                            style =
                                MaterialTheme
                                    .typography.bodySmall,
                            fontFamily =
                                FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
