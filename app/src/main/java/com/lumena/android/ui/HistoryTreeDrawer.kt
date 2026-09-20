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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.settings.HistoryBranch
import com.lumena.android.settings.HistoryTreeState
import com.lumena.android.settings.WorkReportFormatter
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryTreeDrawer(
    state: HistoryTreeState,
    onActivate: (String) -> Unit,
    onForkOpen now: () -> Unit,
    onCreateTask: (String) -> Unit,
    onCreateTopic: (String) -> Unit,
    onRenameOpen now: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var newTask by remember { mutableStateOf("") }
    var newTopic by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf("") }
    val active = state.branches.firstOrNull { it.id == state.activeBranchId }
    val filtered = remember(state, query) {
        if (query.isBlank()) state.branches
        else state.branches.filter { branch ->
            listOf(branch.topic, branch.taskTitle, branch.title)
                .any { it.contains(query, ignoreCase = true) }
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        copiedNotice = "$label copied"
    }

    Column(
        modifier = Modifier
            .width(330.dp)
            .fillMaxHeight()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Chats & work", style = MaterialTheme.typography.headlineSmall)
                Text("Choose a conversation, start new work, or continue from an earlier point.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onClose) { Text("Close") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search chats") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onForkActive, enabled = active != null) {
                Text("Continue from selected point")
            }
        }

        if (active != null) {
            Text(
                "Open now: ${active.topic} / ${active.taskTitle} / ${active.title}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    copyToClipboard("Lumena compact report", WorkReportFormatter.compact(active))
                }) {
                    Text("Copy summary")
                }
                OutlinedButton(onClick = {
                    copyToClipboard("Lumena full report", WorkReportFormatter.full(active))
                }) {
                    Text("Copy full report")
                }
            }
            if (copiedNotice.isNotBlank()) {
                Text(
                    copiedNotice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val topics = filtered.groupBy { it.topic }
                .toList()
                .sortedByDescending { (_, branches) -> branches.maxOfOrNull { it.updatedAt } ?: 0L }

            topics.forEach { (topic, topicBranches) ->
                item(key = "topic:$topic") {
                    Text(
                        topic,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }

                topicBranches.groupBy { it.taskTitle }.forEach { (task, taskBranches) ->
                    item(key = "task:$topic:$task") {
                        Text(
                            "↳ $task",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }

                    val ordered = orderBranches(taskBranches)
                    items(ordered, key = { it.id }) { branch ->
                        BranchRow(
                            branch = branch,
                            depth = branchDepth(branch, taskBranches),
                            selected = branch.id == state.activeBranchId,
                            onClick = { onActivate(branch.id) }
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        if (active != null) {
            Text("Start another task here", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = newTask,
                onValueChange = { newTask = it },
                label = { Text("What do you want to do?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = newTask.isNotBlank(),
                onClick = {
                    onCreateTask(newTask.trim())
                    newTask = ""
                }
            ) { Text("Start task") }
        }

        Text("Start a new topic", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = newTopic,
            onValueChange = { newTopic = it },
            label = { Text("Topic name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = newTopic.isNotBlank(),
            onClick = {
                onCreateTopic(newTopic.trim())
                newTopic = ""
            }
        ) { Text("Start topic") }

        if (active != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rename,
                    onValueChange = { rename = it },
                    label = { Text("Rename this chat") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = rename.isNotBlank(),
                    onClick = {
                        onRenameActive(rename.trim())
                        rename = ""
                    }
                ) { Text("Rename") }
            }
        }
    }
}

@Composable
private fun BranchRow(
    branch: HistoryBranch,
    depth: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val status = branch.session.task?.status
    val prefix = when (status) {
        TaskStatus.DONE -> "✓"
        TaskStatus.FAILED -> "!"
        TaskStatus.CANCELLED -> "■"
        TaskStatus.WAITING_CONFIRMATION -> "?"
        TaskStatus.EXECUTING, TaskStatus.WAITING_MODEL, TaskStatus.PLANNING, TaskStatus.VERIFYING -> "●"
        else -> "○"
    }
    val time = remember(branch.updatedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(branch.updatedAt))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (16 + depth * 14).dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                "$prefix ${if (depth > 0) "↳ " else ""}${branch.title}",
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(time, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun branchDepth(branch: HistoryBranch, peers: List<HistoryBranch>): Int {
    val byId = peers.associateBy { it.id }
    var depth = 0
    var cursor = branch.parentBranchId
    val seen = mutableSetOf<String>()
    while (cursor != null && cursor in byId && seen.add(cursor) && depth < 8) {
        depth++
        cursor = byId[cursor]?.parentBranchId
    }
    return depth
}

private fun orderBranches(branches: List<HistoryBranch>): List<HistoryBranch> {
    val children = branches.groupBy { it.parentBranchId }
    val out = mutableListOf<HistoryBranch>()
    val visited = mutableSetOf<String>()

    fun visit(parentId: String?) {
        children[parentId]
            .orEmpty()
            .sortedBy { it.createdAt }
            .forEach { child ->
                if (visited.add(child.id)) {
                    out += child
                    visit(child.id)
                }
            }
    }

    visit(null)
    branches.sortedBy { it.createdAt }.forEach { if (visited.add(it.id)) out += it }
    return out
}
