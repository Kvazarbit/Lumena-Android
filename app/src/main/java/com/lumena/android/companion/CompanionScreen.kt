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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.settings.LumenaPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val ONE_888_LABEL = "One_888+"

private enum class CompanionStage(val label: String) {
    WAITING("Waiting for ChatGPT"),
    APPROVAL("Waiting approval"),
    RUNNING("Running"),
    RESULT("Result ready")
}

@Composable
private fun StatusCard(
    title: String,
    detail: String,
    enabled: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (enabled != null && onToggle != null) {
                Switch(checked = enabled, onCheckedChange = onToggle)
            } else {
                Text(
                    "●",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun StageStrip(active: CompanionStage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CompanionStage.values().forEach { stage ->
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = if (stage == active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Text(
                    text = stage.label,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stage == active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { LumenaPreferences.load(context) }

    var bridgeUrl by rememberSaveable { mutableStateOf(initial.bridgeUrl) }
    var token by rememberSaveable { mutableStateOf(initial.bridgeToken) }
    var autoReturn by rememberSaveable { mutableStateOf(initial.companionAutoReturn) }
    var safeAuto by rememberSaveable { mutableStateOf(initial.companionSafeAuto) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var lastResultOpen by rememberSaveable { mutableStateOf(false) }

    var detected by remember { mutableStateOf<CompanionCommand?>(null) }
    var handledFingerprint by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Waiting for ChatGPT…") }
    var busy by remember { mutableStateOf(false) }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    var startedAtMs by remember { mutableStateOf<Long?>(null) }
    var elapsedSeconds by remember { mutableStateOf(0L) }
    var bridgeSummary by remember {
        mutableStateOf(if (token.isBlank()) "Not configured" else "Configured")
    }

    fun persistConnection() {
        LumenaPreferences.saveBridgeUrl(context, bridgeUrl)
        LumenaPreferences.saveBridgeToken(context, token)
    }

    fun openChatGptWith(
        text: String,
        send: Boolean,
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        val service = LumenaAccessibilityService.instance
        if (service == null) {
            status = "Enable Lumena Accessibility service first."
            onFinished?.invoke(false)
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val launch = context.packageManager.getLaunchIntentForPackage(
            LumenaAccessibilityService.CHATGPT_PACKAGE
        )
        if (launch == null) {
            status = "Official ChatGPT app was not found as " +
                LumenaAccessibilityService.CHATGPT_PACKAGE + "."
            onFinished?.invoke(false)
            return
        }

        service.scheduleChatGptInsert(
            text = text,
            send = send,
            onFinished = onFinished
        )
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        status = if (send) {
            "Opening ChatGPT and sending…"
        } else {
            "Opening ChatGPT and inserting text…"
        }
    }

    fun planFor(command: CompanionCommand) =
        ToolGate.plan(command.decision, externalSource = true)

    fun isSafeReadOnly(command: CompanionCommand): Boolean {
        val plan = planFor(command)
        if (!plan.allowed) return false
        return ToolRegistry.get(plan.request.tool)?.risk == ToolRisk.READ_ONLY
    }

    fun updateBridgeSummary(raw: String, ok: Boolean) {
        val version = Regex("""(?m)^version=([^\s]+)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
        bridgeSummary = when {
            ok && version != null -> "OK · v" + version
            ok -> "OK"
            else -> "Error"
        }
    }

    LaunchedEffect(busy, startedAtMs) {
        while (busy && startedAtMs != null) {
            elapsedSeconds = ((System.currentTimeMillis() - (startedAtMs ?: System.currentTimeMillis())) / 1000L)
                .coerceAtLeast(0L)
            delay(500)
        }
    }

    fun testBridge() {
        persistConnection()
        if (token.isBlank()) {
            bridgeSummary = "Token required"
            status = "Paste the bridge token first."
            return
        }

        busy = true
        scope.launch {
            val result = TermuxBridgeClient(bridgeUrl, token, context).execute(
                ToolRequest("health")
            )
            updateBridgeSummary(result.stdout, result.ok)
            status = if (result.ok) {
                result.stdout.trim().ifBlank { "Bridge OK" }
            } else {
                result.error ?: "Bridge error"
            }
            busy = false
        }
    }

    fun executeCommand(command: CompanionCommand, automatic: Boolean) {
        if (busy || command.fingerprint == handledFingerprint) return
        if (token.isBlank()) {
            status = "Paste the Termux bridge token first."
            return
        }

        val plan = planFor(command)
        if (!plan.allowed) {
            status = "Blocked tool request: " + plan.reason
            return
        }

        val readOnly = ToolRegistry.get(plan.request.tool)?.risk == ToolRisk.READ_ONLY
        if (automatic && (!safeAuto || !readOnly)) {
            status = "Approval required for " + plan.request.tool + "."
            return
        }

        val request = if (plan.request.requestId.isNullOrBlank()) {
            plan.request.copy(requestId = "companion-" + UUID.randomUUID().toString())
        } else {
            plan.request
        }

        persistConnection()
        busy = true
        startedAtMs = System.currentTimeMillis()
        elapsedSeconds = 0L
        status = if (automatic) {
            "Safe Auto · running " + request.tool + "…"
        } else {
            "Running " + request.tool + "…"
        }

        runningJob = scope.launch {
            var finished = false
            try {
                val result = TermuxBridgeClient(
                    bridgeUrl,
                    token,
                    context
                ).execute(request)

                val formatted = CompanionProtocol.formatResult(
                    request.tool,
                    result
                )
                lastResult = formatted
                lastResultOpen = false
                handledFingerprint = command.fingerprint
                finished = true

                if (autoReturn) {
                    status = if (result.ok) {
                        request.tool + " completed. Returning the real result to ChatGPT…"
                    } else {
                        request.tool + " returned an error. Returning the real error to ChatGPT…"
                    }
                    delay(250)
                    openChatGptWith(
                        formatted,
                        send = true,
                        onFinished = { sent ->
                            status = if (sent) {
                                request.tool + " result sent to ChatGPT."
                            } else {
                                request.tool + " finished, but ChatGPT Send was not confirmed. Result is ready below."
                            }
                        }
                    )
                } else {
                    status = if (result.ok) {
                        request.tool + " completed. Result is ready below."
                    } else {
                        request.tool + " returned an error. The real error is shown below."
                    }
                }
            } finally {
                if (!finished && status.startsWith("Stopping")) {
                    status = "Stopped by user."
                }
                busy = false
                runningJob = null
                startedAtMs = null
            }
        }
    }

    fun stopRunning() {
        if (!busy) return
        status = "Stopping current tool…"
        runningJob?.cancel()
    }

    fun runDiagnostic(tool: String) {
        if (busy) return
        if (token.isBlank()) {
            status = "Paste the Termux bridge token first."
            return
        }

        val request = ToolRequest(
            tool = tool,
            requestId = "diagnostic-" + UUID.randomUUID().toString()
        )
        busy = true
        startedAtMs = System.currentTimeMillis()
        elapsedSeconds = 0L
        status = "Diagnostic · running " + tool + "…"

        runningJob = scope.launch {
            var finished = false
            try {
                val result = TermuxBridgeClient(
                    bridgeUrl,
                    token,
                    context
                ).execute(request)
                if (tool == "health") {
                    updateBridgeSummary(result.stdout, result.ok)
                }
                lastResult = CompanionProtocol.formatResult(tool, result)
                lastResultOpen = true
                status = if (result.ok) {
                    "Diagnostic completed · " + tool
                } else {
                    "Diagnostic failed · " + tool
                }
                finished = true
            } finally {
                if (!finished && status.startsWith("Stopping")) {
                    status = "Stopped by user."
                }
                busy = false
                runningJob = null
                startedAtMs = null
            }
        }
    }

    fun acceptDetected(command: CompanionCommand, force: Boolean = false) {
        if (!force && command.fingerprint == handledFingerprint) {
            status = "Last tool request was already handled."
            return
        }

        detected = command
        val plan = planFor(command)
        when {
            !plan.allowed -> status = "Blocked tool request: " + plan.reason
            safeAuto && isSafeReadOnly(command) && !busy -> {
                status = "Safe read-only tool detected."
                executeCommand(command, automatic = true)
            }
            else -> status = "Tool request detected. Review it before running."
        }
    }

    fun refreshCommand(force: Boolean = false) {
        val snapshot = LumenaAccessibilityService.lastChatGptSnapshot
        val command = CompanionProtocol.parse(snapshot)
        if (command == null) {
            status = if (snapshot == null) {
                "No ChatGPT snapshot yet. Open the official ChatGPT app once."
            } else {
                "ChatGPT captured, but no LUMENA_TOOL block is visible yet."
            }
            return
        }
        if (force && command.fingerprint == handledFingerprint) {
            handledFingerprint = null
        }
        acceptDetected(command, force = force)
    }

    fun runDetected() {
        detected?.let { executeCommand(it, automatic = false) }
    }

    fun rejectDetected() {
        detected?.let { command ->
            handledFingerprint = command.fingerprint
            detected = null
            status = "Tool request rejected."
        }
    }

    LaunchedEffect(Unit) {
        if (token.isNotBlank()) {
            val result = TermuxBridgeClient(
                bridgeUrl,
                token,
                context
            ).execute(ToolRequest("health"))
            updateBridgeSummary(result.stdout, result.ok)
        }
    }

    LaunchedEffect(safeAuto, token, bridgeUrl) {
        while (true) {
            val command = CompanionProtocol.parse(
                LumenaAccessibilityService.lastChatGptSnapshot
            )
            if (
                command != null &&
                command.fingerprint != handledFingerprint &&
                !busy
            ) {
                acceptDetected(command)
            }
            delay(700)
        }
    }

    val chatGptConnected = LumenaAccessibilityService.instance != null
    val stage = when {
        busy -> CompanionStage.RUNNING
        detected != null && detected?.fingerprint != handledFingerprint ->
            CompanionStage.APPROVAL
        lastResult.isNotBlank() -> CompanionStage.RESULT
        else -> CompanionStage.WAITING
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Lumena Companion",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    ONE_888_LABEL + " · focused companion UI",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedButton(onClick = { setupOpen = true }) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        }

        Text(
            "ChatGPT stays the main conversation. Lumena bridges approved local tools to Termux/Python/Git.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusCard(
                title = "ChatGPT",
                detail = if (chatGptConnected) "connected" else "open ChatGPT once",
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Bridge",
                detail = bridgeSummary,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusCard(
                title = "Safe Auto",
                detail = if (safeAuto) "ON" else "OFF",
                enabled = safeAuto,
                onToggle = {
                    safeAuto = it
                    LumenaPreferences.saveCompanionSafeAuto(context, it)
                },
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Auto-return",
                detail = if (autoReturn) "ON" else "OFF",
                enabled = autoReturn,
                onToggle = {
                    autoReturn = it
                    LumenaPreferences.saveCompanionAutoReturn(context, it)
                },
                modifier = Modifier.weight(1f)
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Current request",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Elapsed · " + elapsedSeconds + " s",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(onClick = { stopRunning() }) {
                            Text("Stop")
                        }
                    }
                }

                val command = detected
                if (command != null) {
                    val plan = planFor(command)
                    val readOnly = plan.allowed &&
                        ToolRegistry.get(plan.request.tool)?.risk == ToolRisk.READ_ONLY
                    val autoEligible = safeAuto && readOnly

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                plan.request.tool,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                when {
                                    !plan.allowed -> "Blocked by tool registry"
                                    busy -> "Running"
                                    autoEligible -> "Safe Auto eligible"
                                    else -> "Approval required"
                                },
                                color = if (!plan.allowed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(plan.reason)
                            if (plan.request.args.isNotEmpty()) {
                                Text(
                                    "Args: " + plan.request.args,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (busy) {
                                    Button(
                                        onClick = { stopRunning() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Stop · " + elapsedSeconds + " s")
                                    }
                                } else {
                                    Button(
                                        enabled = plan.allowed,
                                        onClick = { runDetected() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Run once")
                                    }
                                    OutlinedButton(
                                        onClick = { rejectDetected() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Reject")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "No tool is pending. Scan the visible ChatGPT conversation for a LUMENA_TOOL request.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { refreshCommand(force = false) }) {
                            Text("Scan ChatGPT")
                        }
                        TextButton(onClick = { refreshCommand(force = true) }) {
                            Text("Rescan")
                        }
                    }
                }

                StageStrip(stage)
            }
        }

        if (lastResult.isNotBlank()) {
            val lastTool = Regex("""(?m)^tool=(.+)$""")
                .find(lastResult)
                ?.groupValues
                ?.getOrNull(1)
                ?: "tool"

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Last result",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                lastTool + " · result ready",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { lastResultOpen = !lastResultOpen }
                        ) {
                            Text(if (lastResultOpen) "▲" else "▼")
                        }
                    }

                    Text(
                        if (lastResultOpen) {
                            lastResult.take(6000)
                        } else {
                            lastResult.lineSequence().take(4).joinToString("\n")
                        },
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (lastResultOpen) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    openChatGptWith(lastResult, send = false)
                                }
                            ) {
                                Text("Insert result")
                            }
                            OutlinedButton(
                                onClick = {
                                    openChatGptWith(lastResult, send = true)
                                }
                            ) {
                                Text("Insert + send")
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Setup & diagnostics are in +",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
    }

    if (setupOpen) {
        ModalBottomSheet(
            onDismissRequest = { setupOpen = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Setup & diagnostics",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    ONE_888_LABEL,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    "Connection",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Connect this ChatGPT chat",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Insert the LUMENA_TOOL protocol into the current official ChatGPT conversation.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    openChatGptWith(
                                        CompanionProtocol.handshakeText,
                                        send = false
                                    )
                                }
                            ) {
                                Text("Insert protocol")
                            }
                            OutlinedButton(
                                onClick = {
                                    openChatGptWith(
                                        CompanionProtocol.handshakeText,
                                        send = true
                                    )
                                }
                            ) {
                                Text("Insert + send")
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Local bridge",
                            style = MaterialTheme.typography.titleMedium
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
                                val clean = LumenaPreferences.normalizeBridgeToken(it)
                                token = clean
                                LumenaPreferences.saveBridgeToken(context, clean)
                            },
                            label = { Text("Bridge token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { testBridge() }
                            ) {
                                Text("Test bridge")
                            }
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    )
                                }
                            ) {
                                Text("Accessibility")
                            }
                        }
                        Text(
                            "Bridge status: " + bridgeSummary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Text(
                    "Automation",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Safe Auto",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Automatically runs only registry-marked read-only tools.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = safeAuto,
                                onCheckedChange = {
                                    safeAuto = it
                                    LumenaPreferences.saveCompanionSafeAuto(context, it)
                                }
                            )
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Auto-return result",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Return the real LUMENA_RESULT to ChatGPT after a tool finishes.",
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
                }

                Text(
                    "Diagnostics & recovery",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Run read-only checks without leaving Companion.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { runDiagnostic("health") }
                            ) { Text("Health") }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { runDiagnostic("context.snapshot") }
                            ) { Text("Context") }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { runDiagnostic("ollama.status") }
                            ) { Text("Ollama") }
                        }
                        if (busy) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Running · " + elapsedSeconds + " s",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { stopRunning() }) {
                                    Text("Stop")
                                }
                            }
                        }
                    }
                }

                Text(
                    "Advanced",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            setupOpen = false
                            refreshCommand(force = false)
                        }
                    ) {
                        Text("Scan ChatGPT")
                    }
                    TextButton(
                        onClick = {
                            setupOpen = false
                            refreshCommand(force = true)
                        }
                    ) {
                        Text("Rescan")
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            )
                        }
                    ) {
                        Text("Accessibility")
                    }
                }

                Text(
                    "Security: only READ_ONLY tools may auto-run. Mutating and executable tools still require Run once. No unrestricted shell tool is exposed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
