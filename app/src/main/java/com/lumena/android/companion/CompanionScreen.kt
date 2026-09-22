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
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.lumena.android.settings.LumenaPreferences
import com.lumena.android.settings.ExperienceMemoryStore
import com.lumena.android.settings.CoordinatorExperienceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CompanionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { LumenaPreferences.load(context) }

    var bridgeUrl by rememberSaveable { mutableStateOf(initial.bridgeUrl) }
    var token by rememberSaveable { mutableStateOf(initial.bridgeToken) }
    var autoReturn by rememberSaveable { mutableStateOf(initial.companionAutoReturn) }
    var safeAuto by rememberSaveable { mutableStateOf(initial.companionSafeAuto) }
    var detected by remember { mutableStateOf<CompanionCommand?>(null) }
    var handledFingerprint by remember { mutableStateOf<String?>(null) }
    var activeFingerprint by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Waiting for ChatGPT…") }
    var busy by remember { mutableStateOf(false) }

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
        val launch = context.packageManager.getLaunchIntentForPackage(LumenaAccessibilityService.CHATGPT_PACKAGE)
        if (launch == null) {
            status = "Official ChatGPT app was not found as ${LumenaAccessibilityService.CHATGPT_PACKAGE}."
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
        status = if (send) "Opening ChatGPT and sending…" else "Opening ChatGPT and inserting text…"
    }

    fun planFor(command: CompanionCommand) =
        ToolGate.plan(command.decision, externalSource = true)

    fun isSafeReadOnly(command: CompanionCommand): Boolean {
        val plan = planFor(command)
        if (!plan.allowed) return false
        return ToolRegistry.get(plan.request.tool)?.risk == ToolRisk.READ_ONLY
    }

    fun executeCommand(command: CompanionCommand, automatic: Boolean) {
        if (!CompanionRequestLifecycle.shouldAccept(
                fingerprint = command.fingerprint,
                handledFingerprint = handledFingerprint,
                activeFingerprint = activeFingerprint,
                busy = busy
            )
        ) return
        if (token.isBlank()) {
            status = "Paste the Termux bridge token first."
            return
        }

        val plan = planFor(command)
        if (!plan.allowed) {
            status = "Blocked tool request: ${plan.reason}"
            return
        }

        val readOnly = ToolRegistry.get(plan.request.tool)?.risk == ToolRisk.READ_ONLY
        if (automatic && (!safeAuto || !readOnly)) {
            status = "Approval required for ${plan.request.tool}."
            return
        }

        persistConnection()
        busy = true
        activeFingerprint = command.fingerprint
        if (detected?.fingerprint == command.fingerprint) detected = null
        status = if (automatic) {
            "Safe Auto · running ${plan.request.tool}…"
        } else {
            "Running ${plan.request.tool}…"
        }

        scope.launch {
            val result = try {
                TermuxBridgeClient(bridgeUrl, token, context).execute(plan.request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ToolResult(
                    ok = false,
                    error = "Companion execution failed: ${error::class.simpleName}: ${error.message}",
                    errorCode = "COMPANION_EXECUTION",
                    failureClass = "LOCAL_EXECUTION",
                    retryable = false,
                    dependency = "termux_bridge"
                )
            }
            val experienceRef = if (result.outcomeUnknown) {
                null
            } else {
                runCatching {
                    withContext(Dispatchers.IO) {
                        ExperienceMemoryStore.record(
                            context = context,
                            request = plan.request,
                            result = result
                        )
                    }
                }.getOrNull()
            }

            val episodeEvent = runCatching {
                withContext(Dispatchers.IO) {
                    CoordinatorExperienceStore.record(
                        context = context,
                        sessionId = command.sessionId
                            ?: "single-${command.fingerprint.take(24)}",
                        taskId = command.taskId,
                        request = plan.request,
                        result = result,
                        experienceId = experienceRef
                    )
                }
            }.getOrNull()

            val memoryQuery = buildString {
                append(plan.request.tool)
                plan.request.args.toSortedMap().forEach { (key, value) ->
                    append(' ').append(key).append('=').append(value.take(220))
                }
            }
            val memoryHints = runCatching {
                withContext(Dispatchers.IO) {
                    (
                        ExperienceMemoryStore.relevant(
                            context = context,
                            query = memoryQuery,
                            limit = 3
                        ) +
                            CoordinatorExperienceStore.relevant(
                                context = context,
                                query = memoryQuery,
                                limit = 3
                            )
                    )
                        .distinct()
                        .take(3)
                }
            }.getOrElse { emptyList() }

            val formatted = CompanionProtocol.formatResult(
                tool = plan.request.tool,
                result = result,
                experienceRef = experienceRef,
                memoryHints = memoryHints,
                sessionId = command.sessionId,
                taskId = command.taskId,
                episodeEventId = episodeEvent?.id
            )
            lastResult = formatted
            handledFingerprint = command.fingerprint
            if (activeFingerprint == command.fingerprint) activeFingerprint = null
            busy = false

            if (autoReturn) {
                status = if (result.ok) {
                    "${plan.request.tool} completed. Returning the real result to ChatGPT…"
                } else {
                    "${plan.request.tool} returned an error. Returning the real error to ChatGPT…"
                }
                delay(250)
                openChatGptWith(
                    formatted,
                    send = true,
                    onFinished = { sent ->
                        if (detected == null && activeFingerprint == null) {
                            status = if (sent) {
                                "${plan.request.tool} result sent to ChatGPT."
                            } else {
                                "${plan.request.tool} finished, but ChatGPT Send was not confirmed. Result is ready below."
                            }
                        }
                    }
                )
            } else {
                status = if (result.ok) {
                    "${plan.request.tool} completed. Result is ready below."
                } else {
                    "${plan.request.tool} returned an error. The real error is shown below."
                }
            }
        }
    }

    fun acceptDetected(command: CompanionCommand, force: Boolean = false) {
        if (!force && !CompanionRequestLifecycle.shouldAccept(
                fingerprint = command.fingerprint,
                handledFingerprint = handledFingerprint,
                activeFingerprint = activeFingerprint,
                busy = busy
            )
        ) {
            if (command.fingerprint == handledFingerprint) {
                if (detected?.fingerprint == command.fingerprint) detected = null
                status = "Last tool request was already handled."
            }
            return
        }

        detected = command
        val plan = planFor(command)
        when {
            !plan.allowed -> status = "Blocked tool request: ${plan.reason}"
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
        if (force && command.fingerprint == handledFingerprint) handledFingerprint = null
        acceptDetected(command, force = force)
    }

    fun runDetected() {
        val command = detected ?: return
        if (!CompanionRequestLifecycle.shouldAccept(
                fingerprint = command.fingerprint,
                handledFingerprint = handledFingerprint,
                activeFingerprint = activeFingerprint,
                busy = busy
            )
        ) {
            if (command.fingerprint == handledFingerprint) detected = null
            return
        }
        executeCommand(command, automatic = false)
    }

    LaunchedEffect(safeAuto, token, bridgeUrl) {
        while (true) {
            val command = CompanionProtocol.parse(LumenaAccessibilityService.lastChatGptSnapshot)
            if (
                command != null &&
                CompanionRequestLifecycle.shouldAccept(
                    fingerprint = command.fingerprint,
                    handledFingerprint = handledFingerprint,
                    activeFingerprint = activeFingerprint,
                    busy = busy
                )
            ) {
                acceptDetected(command)
            }
            delay(700)
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
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Safe Auto", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Automatically runs only read-only tools, including context.snapshot, inspect.batch, process.status, http.json/http.get, image.search, system and file/Git inspection.",
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
                        Text("Auto-return result", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "After an approved or Safe Auto tool finishes, Lumena sends the real LUMENA_RESULT back to ChatGPT automatically.",
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

        HorizontalDivider()
        Text("3 · Tool request", style = MaterialTheme.typography.titleLarge)
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

        detected
            ?.takeIf { command ->
                CompanionRequestLifecycle.isVisible(
                    fingerprint = command.fingerprint,
                    handledFingerprint = handledFingerprint,
                    activeFingerprint = activeFingerprint
                )
            }
            ?.let { command ->
            val plan = planFor(command)
            val readOnly = plan.allowed &&
                ToolRegistry.get(plan.request.tool)?.risk == ToolRisk.READ_ONLY
            val autoEligible = safeAuto && readOnly

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(plan.request.tool, style = MaterialTheme.typography.titleMedium)
                    Text(plan.reason)
                    Text("Args: ${plan.request.args}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            !plan.allowed -> "BLOCKED by tool registry"
                            autoEligible -> "READ-ONLY · Safe Auto eligible"
                            else -> "Approval required · this tool can change state or execute code"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!autoEligible) {
                        Button(
                            enabled = !busy && plan.allowed,
                            onClick = { runDetected() }
                        ) {
                            Text(if (busy) "Working…" else "Run once")
                        }
                    } else if (busy) {
                        Text("Running automatically…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refreshCommand(force = false) }) { Text("Scan ChatGPT") }
            TextButton(onClick = { refreshCommand(force = true) }) { Text("Rescan") }
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
            when {
                safeAuto && autoReturn ->
                    "Security: only READ_ONLY tools may auto-run. Mutating and executable tools still require Run once. Results return automatically. No unrestricted shell tool is exposed."
                safeAuto ->
                    "Security: only READ_ONLY tools may auto-run. Mutating and executable tools still require Run once. Results stay in Lumena until you send them."
                else ->
                    "Security: Safe Auto is off. Every external LUMENA_TOOL requires Run once. No unrestricted shell tool is exposed."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
