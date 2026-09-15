package com.lumena.android.agent.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AgentPanel() {
    val planner = remember { RulePlanner() }
    val scope = rememberCoroutineScope()

    var bridgeUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:8765") }
    var token by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable { mutableStateOf("health") }
    var output by remember { mutableStateOf("Agent idle") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PlannedTool?>(null) }

    fun execute(plan: PlannedTool) {
        if (!plan.allowed) {
            output = "Blocked: unknown tool ${plan.request.tool}"
            return
        }
        if (token.isBlank()) {
            output = "Paste the bridge token shown by Termux first."
            return
        }
        busy = true
        output = "Running ${plan.request.tool}…"
        scope.launch {
            val result = TermuxBridgeClient(bridgeUrl, token).execute(plan.request)
            output = formatResult(plan, result)
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("Local Agent", style = MaterialTheme.typography.titleLarge)
        Text(
            "Termux/Python bridge v0.3. Read-only tools run directly; Python execution asks for approval.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = bridgeUrl,
            onValueChange = { bridgeUrl = it },
            label = { Text("Bridge URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Bridge token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = instruction,
            onValueChange = { instruction = it },
            label = { Text("Agent instruction") },
            supportingText = {
                Text("Examples: health · git status project · read notes.txt · python scripts/test.py --fast")
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = !busy,
                onClick = { execute(ToolGate.plan(PlannerDecision(ToolRequest("health"), "Manual bridge test."))) }
            ) {
                Text("Test bridge")
            }

            Button(
                enabled = !busy,
                onClick = {
                    val decision = planner.plan(instruction)
                    if (decision == null) {
                        output = "No local rule matched. v0.3 accepts health/read/git/python commands; a local LLM planner is the next layer."
                    } else {
                        val plan = ToolGate.plan(decision)
                        when {
                            !plan.allowed -> output = "Blocked: unknown tool ${plan.request.tool}"
                            plan.requiresConfirmation -> pending = plan
                            else -> execute(plan)
                        }
                    }
                }
            ) {
                Text(if (busy) "Working…" else "Plan & run")
            }
        }

        Text(output, style = MaterialTheme.typography.bodySmall)
    }

    pending?.let { plan ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Allow local execution?") },
            text = {
                Text("${plan.reason}\n\nTool: ${plan.request.tool}\nArgs: ${plan.request.args}")
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    execute(plan)
                }) { Text("Run once") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            }
        )
    }
}

private fun formatResult(plan: PlannedTool, result: ToolResult): String = buildString {
    appendLine("Tool: ${plan.request.tool}")
    appendLine("OK: ${result.ok}")
    result.exitCode?.let { appendLine("Exit: $it") }
    if (!result.error.isNullOrBlank()) appendLine("Error: ${result.error}")
    if (result.stdout.isNotBlank()) {
        appendLine("--- stdout ---")
        appendLine(result.stdout.trimEnd())
    }
    if (result.stderr.isNotBlank()) {
        appendLine("--- stderr ---")
        appendLine(result.stderr.trimEnd())
    }
}
