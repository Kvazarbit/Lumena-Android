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
    onForkActive: () -> Unit,
    onCreateTask: (String) -> Unit,
    onCreateTopic: (String) -> Unit,
    onRenameActive: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var createOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var newTask by remember { mutableStateOf("") }
    var newTopic by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf("") }

    val active = state.branches.firstOrNull { it.id == state.activeBranchId }
    val filtered = remember(state, query) {
        if (query.isBlank()) state.branches
        else state.branches.filter { branch ->
            listOf(branch.topic, branch.taskTitle, branch.title).any { it.contains(query, ignoreCase = true) }
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        copiedNotice = "$label copied"
    }

    Column(
        modifier = Modifier.width(340.dp).fillMaxHeight().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Chats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    active?.title ?: "Choose a conversation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClose) { Text("Close") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { createOpen = !createOpen }) { Text("＋ New") }
            OutlinedButton(onClick = onForkActive, enabled = active != null) { Text("Continue") }
            TextButton(onClick = { moreOpen = !moreOpen }, enabled = active != null) { Text("⋮") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search chats") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (createOpen) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active != null) {
                        Text("New task here", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = newTask,
                            onValueChange = { newTask = it },
                            placeholder = { Text("What do you want to do?") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            enabled = newTask.isNotBlank(),
                            onClick = {
                                onCreateTask(newTask.trim())
                                newTask = ""
                                createOpen = false
                            }
                        ) { Text("Start task") }
                    }

                    Text("New topic", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = newTopic,
                        onValueChange = { newTopic = it },
                        placeholder = { Text("Topic name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        enabled = newTopic.isNotBlank(),
                        onClick = {
                            onCreateTopic(newTopic.trim())
                            newTopic = ""
                            createOpen = false
                        }
                    ) { Text("Start topic") }
                }
            }
        }

        if (moreOpen && active != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current chat", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${active.topic} / ${active.taskTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            copyToClipboard("Lumena compact report", WorkReportFormatter.compact(active))
                        }) { Text("Copy summary") }
                        TextButton(onClick = {
                            copyToClipboard("Lumena full report", WorkReportFormatter.full(active))
                        }) { Text("Full report") }
                    }
                    OutlinedTextField(
                        value = rename,
                        onValueChange = { rename = it },
                        placeholder = { Text("Rename chat") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        enabled = rename.isNotBlank(),
                        onClick = {
                            onRenameActive(rename.trim())
                            rename = ""
                        }
                    ) { Text("Rename") }

                    if (copiedNotice.isNotBlank()) {
                        Text(copiedNotice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
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
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }

                topicBranches.groupBy { it.taskTitle }.forEach { (task, taskBranches) ->
                    item(key = "task:$topic:$task") {
                        Text(
                            task,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        TaskStatus.PARTIAL -> "◐"
        TaskStatus.FAILED -> "!"
        TaskStatus.CANCELLED -> "■"
        TaskStatus.WAITING_CONFIRMATION -> "?"
        TaskStatus.EXECUTING, TaskStatus.WAITING_MODEL, TaskStatus.PLANNING, TaskStatus.VERIFYING -> "●"
        else -> ""
    }
    val time = remember(branch.updatedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(branch.updatedAt))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (8 + depth * 12).dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                buildString {
                    if (prefix.isNotBlank()) append(prefix).append(' ')
                    append(branch.title)
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        children[parentId].orEmpty().sortedBy { it.createdAt }.forEach { child ->
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
