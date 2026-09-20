package com.lumena.android.companion

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.LumenaAccessibilityService
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.settings.LumenaPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CompanionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { LumenaPreferences.load(context) }

    var bridgeUrl by rememberSaveable { mutableStateOf(initial.bridgeUrl) }
    var token by rememberSaveable { mutableStateOf(initial.bridgeToken) }
    var autoReturn by rememberSaveable { mutableStateOf(initial.companionAutoReturn) }
    var detected by remember { mutableStateOf<CompanionCommand?>(null) }
    var handledFingerprint by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Waiting for ChatGPT…") }
    var busy by remember { mutableStateOf(false) }

    fun persistConnection() {
        LumenaPreferences.saveBridgeUrl(context, bridgeUrl)
        LumenaPreferences.saveBridgeToken(context, token)
    }

    fun refreshCommand(force: Boolean = false) {
        val snapshot = LumenaAccessibilityService.lastChatGptSnapshot
        val command = CompanionProtocol.parse(snapshot)
        when {
            command == null -> status = if (snapshot == null) {
                "No ChatGPT snapshot yet. Open the official ChatGPT app once."
            } else {
                "ChatGPT captured, but no LUMENA_TOOL block is visible yet."
            }
            !force && command.fingerprint == handledFingerprint ->
                status = "Last tool request was already handled. Tap Rescan to run it again."
            else -> {
                detected = command
                status = "Tool request detected from official ChatGPT. Review it before running."
            }
        }
    }

    fun openChatGptWith(text: String, send: Boolean) {
        val service = LumenaAccessibilityService.instance
        if (service == null) {
            status = "Enable Lumena Accessibility service first."
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val launch = context.packageManager.getLaunchIntentForPackage(LumenaAccessibilityService.CHATGPT_PACKAGE)
        if (launch == null) {
            status = "Official ChatGPT app was not found as ${LumenaAccessibilityService.CHATGPT_PACKAGE}."
            return
        }
        service.scheduleChatGptInsert(text, send = send)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        status = if (send) "Opening ChatGPT and sending…" else "Opening ChatGPT and inserting text…"
    }

    fun runDetected() {
        val command = detected ?: return
        if (token.isBlank()) {
            status = "Paste the Termux bridge token first."
            return
        }
        val plan = ToolGate.plan(command.decision)
        if (!plan.allowed) {
            status = "Blocked unknown tool: ${plan.request.tool}"
            return
        }
        persistConnection()
        busy = true
        status = "Running ${plan.request.tool}…"
        scope.launch {
            val result = TermuxBridgeClient(bridgeUrl, token, context).execute(plan.request)
            val formatted = CompanionProtocol.formatResult(plan.request.tool, result)
            lastResult = formatted
            handledFingerprint = command.fingerprint
            busy = false

            if (autoReturn) {
                status = if (result.ok) {
                    "${plan.request.tool} completed. Returning the real result to ChatGPT…"
                } else {
                    "${plan.request.tool} returned an error. Returning the real error to ChatGPT…"
                }
                delay(250)
                openChatGptWith(formatted, send = true)
            } else {
                status = if (result.ok) {
                    "${plan.request.tool} completed. Review the result before returning it to ChatGPT."
                } else {
                    "${plan.request.tool} returned an error. The real error can still be sent back to ChatGPT."
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val command = CompanionProtocol.parse(LumenaAccessibilityService.lastChatGptSnapshot)
            if (command != null && command.fingerprint != handledFingerprint && command.fingerprint != detected?.fingerprint) {
                detected = command
                status = "New LUMENA_TOOL request detected."
            }
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Lumena Companion", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Official ChatGPT stays the main conversation. Lumena only bridges approved local tools to Termux/Python/Git.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1 · Connect this ChatGPT chat", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This inserts the LUMENA_TOOL protocol into the current official ChatGPT conversation. You still control whether it is sent.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openChatGptWith(CompanionProtocol.handshakeText, send = false) }) {
                        Text("Insert protocol")
                    }
                    OutlinedButton(onClick = { openChatGptWith(CompanionProtocol.handshakeText, send = true) }) {
                        Text("Insert + send")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2 · Local bridge", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Bridge settings are shared automatically with Local and Tools.",
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
                    value = token,
                    onValueChange = {
                        token = it
                        LumenaPreferences.saveBridgeToken(context, it)
                    },
                    label = { Text("Bridge token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            persistConnection()
                            busy = true
                            scope.launch {
                                val result = if (token.isBlank()) null else TermuxBridgeClient(bridgeUrl, token, context).execute(
                                    ToolRequest("health")
                                )
                                status = when {
                                    token.isBlank() -> "Paste the bridge token first."
                                    result == null -> "Bridge test failed."
                                    result.ok -> result.stdout.trim().ifBlank { "Bridge OK" }
                                    else -> result.error ?: "Bridge error"
                                }
                                busy = false
                            }
                        }
                    ) { Text("Test bridge") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("Accessibility")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-return result", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "After your Run once approval, Lumena sends the real LUMENA_RESULT back to the same ChatGPT chat automatically. New tool calls still require your approval.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = autoReturn,
                    onCheckedChange = {
                        autoReturn = it
                        LumenaPreferences.saveCompanionAutoReturn(context, it)
                    }
                )
            }
        }

        HorizontalDivider()
        Text("3 · Tool request", style = MaterialTheme.typography.titleLarge)
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

        detected?.let { command ->
            val plan = ToolGate.plan(command.decision)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(command.decision.request.tool, style = MaterialTheme.typography.titleMedium)
                    Text(command.decision.reason)
                    Text("Args: ${command.decision.request.args}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (plan.allowed) "Allowed by tool registry · explicit approval required" else "BLOCKED: unknown tool",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(enabled = !busy && plan.allowed, onClick = { runDetected() }) {
                        Text(if (busy) "Working…" else "Run once")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refreshCommand(force = false) }) { Text("Scan ChatGPT") }
            TextButton(onClick = {
                handledFingerprint = null
                refreshCommand(force = true)
            }) { Text("Rescan") }
        }

        if (lastResult.isNotBlank()) {
            HorizontalDivider()
            Text("4 · Real local result", style = MaterialTheme.typography.titleLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    lastResult,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openChatGptWith(lastResult, send = false) }) {
                    Text("Insert result")
                }
                OutlinedButton(onClick = { openChatGptWith(lastResult, send = true) }) {
                    Text("Insert + send")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (autoReturn) {
                "Security: every external LUMENA_TOOL still requires your Run once tap. Auto-return only sends the real result after an approved tool finishes. No unrestricted shell tool is exposed."
            } else {
                "Security: commands read from ChatGPT are never auto-executed. Every external LUMENA_TOOL request requires your Run once tap. No unrestricted shell tool is exposed."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
