package com.lumena.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.runtime.AgentRunCoordinator

@Composable
fun AgentWorkDrawer(
    task: TaskState?,
    coordinator: AgentRunCoordinator,
    model: String,
    pendingApproval: Boolean,
    onStop: () -> Unit,
    onClose: () -> Unit
) {
    var taskOpen by remember { mutableStateOf(false) }
    var eventsOpen by remember { mutableStateOf(false) }
    val running = task?.status in setOf(TaskStatus.PLANNING, TaskStatus.WAITING_MODEL, TaskStatus.EXECUTING, TaskStatus.VERIFYING, TaskStatus.WAITING_CONFIRMATION)
    Column(Modifier.width(330.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Agent", style = MaterialTheme.typography.headlineSmall); Text(model.ifBlank { "Local model" }, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onClose) { Text("Close") }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(when { pendingApproval -> "? Waiting for approval"; running -> "● ${coordinator.stage}"; task?.status == TaskStatus.DONE -> "✓ Done"; task?.status == TaskStatus.FAILED -> "! Failed"; task?.status == TaskStatus.CANCELLED -> "■ Cancelled"; else -> "○ Idle" }, fontWeight = FontWeight.SemiBold)
                task?.let { Text("Step ${it.step}/${it.maxSteps}", style = MaterialTheme.typography.bodySmall) }
                if (running || pendingApproval) OutlinedButton(onClick = onStop) { Text("STOP") }
            }
        }
        task?.let { current ->
            HorizontalDivider()
            Text(if (taskOpen) "▼ Task details" else "▶ Task details", Modifier.fillMaxWidth().clickable { taskOpen = !taskOpen }, style = MaterialTheme.typography.titleSmall)
            if (taskOpen) {
                Text("Goal\n${current.goal}", style = MaterialTheme.typography.bodySmall)
                current.lastTool?.let { Text("Last tool\n$it", style = MaterialTheme.typography.bodySmall) }
                current.lastResult?.let { Text("Last result\n${it.take(2500)}", style = MaterialTheme.typography.bodySmall) }
                if (current.errors.isNotEmpty()) Text("Errors\n${current.errors.takeLast(3).joinToString("\n")}", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Text(if (eventsOpen) "▼ Execution (${coordinator.progress.size})" else "▶ Execution (${coordinator.progress.size})", Modifier.fillMaxWidth().clickable { eventsOpen = !eventsOpen }, style = MaterialTheme.typography.titleSmall)
        if (eventsOpen) coordinator.progress.takeLast(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Detailed work stays here so the center remains focused on conversation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
