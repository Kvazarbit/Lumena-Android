package com.lumena.android.companion

import android.content.Intent
import com.lumena.android.settings.StateVault
import androidx.compose.runtime.SideEffect
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
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.core.ToolRegistry
import com.lumena.android.agent.core.ToolRisk
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolGate
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.lumena.android.settings.LumenaPreferences
import com.lumena.android.settings.ExperienceMemoryStore
import com.lumena.android.settings.CoordinatorExperienceStore
import com.lumena.android.settings.ConstitutionGenomeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CompanionScreen(visible: Boolean = true, handoffVersion: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { LumenaPreferences.load(context) }

    var bridgeUrl by rememberSaveable { mutableStateOf(initial.bridgeUrl) }
    var token by rememberSaveable { mutableStateOf(initial.bridgeToken) }
    var autoReturn by rememberSaveable { mutableStateOf(initial.companionAutoReturn) }
    var safeAuto by rememberSaveable { mutableStateOf(initial.companionSafeAuto) }
    val consent = remember { runCatching { CompanionTaskGrantStore.recover(context) }.getOrDefault(CompanionTaskGrantStore.State()) }
    var detected by remember { mutableStateOf<CompanionCommand?>(null) }
    var handledFingerprint by remember { mutableStateOf<String?>(consent.handled) }
    var activeFingerprint by remember { mutableStateOf<String?>(null) }
    var lastResult by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Waiting for ChatGPT…") }
    var busy by remember { mutableStateOf(false) }
    var taskGrant by remember { mutableStateOf(consent.grant) }
    var grantEditor by remember { mutableStateOf<String?>(null) }
    var grantPython by remember { mutableStateOf(false) }
    var grantPaths by remember { mutableStateOf("") }
    var resultExpanded by remember { mutableStateOf(false) }
    SideEffect { StateVault.companionBusy = busy }
    var handoffDraft by remember { mutableStateOf(CompanionHandoffStore.load(context)) }

    LaunchedEffect(visible, handoffVersion) {
        if (visible) {
            handoffDraft = CompanionHandoffStore.load(context)
            val saved = LumenaPreferences.load(context)
            bridgeUrl = saved.bridgeUrl
            token = saved.bridgeToken
        }
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
        val launch = context.packageManager.getLaunchIntentForPackage(LumenaAccessibilityService.CHATGPT_PACKAGE)
        if (launch == null) {
            status = "Official ChatGPT app was not found as ${LumenaAccessibilityService.CHATGPT_PACKAGE}."
            onFinished?.invoke(false)
            return
        }
        val chatGptAlreadyActive = service.isChatGptActive()
        val generationActive = service.isChatGptGenerating()
        service.scheduleChatGptInsert(
            text = text,
            send = send,
            onFinished = onFinished
        )
        if (!chatGptAlreadyActive) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
        status = when {
            generationActive && send ->
                "ChatGPT is still generating. Result queued until the turn finishes…"
            generationActive ->
                "ChatGPT is still generating. Insert queued until the turn finishes…"
            send && chatGptAlreadyActive ->
                "Sending result to the active ChatGPT conversation…"
            !send && chatGptAlreadyActive ->
                "Inserting into the active ChatGPT conversation…"
            send -> "Opening ChatGPT and sending…"
            else -> "Opening ChatGPT and inserting text…"
        }
    }

    fun planFor(command: CompanionCommand) =
        ToolGate.plan(command.decision, externalSource = true)

    fun isSafeReadOnly(command: CompanionCommand): Boolean {
        val plan = planFor(command)
        if (!plan.allowed) return false
        return ToolRegistry.get(plan.request.tool)?.risk == ToolRisk.READ_ONLY
    }

    fun setTaskGrant(grant: CompanionTaskGrant?): Boolean {
        return try {
            CompanionTaskGrantStore.setGrant(context, grant)
            taskGrant = grant
            true
        } catch (e: Exception) {
            status = "Не вдалося зберегти дозвіл: ${e::class.simpleName}"
            false
        }
    }

    fun grantAllows(command: CompanionCommand): Boolean {
        val connection = LumenaPreferences.load(context)
        return taskGrant?.allows(command, connection.bridgeUrl, connection.bridgeToken) == true
    }

    fun executeCommand(command: CompanionCommand, automatic: Boolean) {
        if (StateVault.restoring || StateVault.startupError != null) return
        if (!CompanionRequestLifecycle.shouldAccept(
                fingerprint = command.fingerprint,
                handledFingerprint = handledFingerprint,
                activeFingerprint = activeFingerprint,
                busy = busy
            )
        ) return
        // Local can update the shared connection while this UI is hidden.
        val savedConnection = LumenaPreferences.load(context)
        bridgeUrl = savedConnection.bridgeUrl
        token = savedConnection.bridgeToken
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
        if (automatic && !(safeAuto && readOnly) && !grantAllows(command)) {
            status = "Approval required for ${plan.request.tool}."
            return
        }

        try { CompanionTaskGrantStore.begin(context, command.fingerprint) }
        catch (e: Exception) {
            status = "Не вдалося зберегти стан запуску; команду не виконано."
            return
        }
        persistConnection()
        busy = true
        activeFingerprint = command.fingerprint
        if (detected?.fingerprint == command.fingerprint) detected = null
        status = if (automatic) {
            "${if (readOnly) "Safe Auto" else "Task permission"} · running ${plan.request.tool}…"
        } else {
            "Running ${plan.request.tool}…"
        }

        val episodeSessionId = command.sessionId
            ?: "single-${command.fingerprint.take(24)}"
        val contributorModelId = command.modelId
            ?: CompanionProtocol.DEFAULT_COMPANION_MODEL_ID

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
                        sessionId = episodeSessionId,
                        taskId = command.taskId,
                        request = plan.request,
                        result = result,
                        experienceId = experienceRef,
                        modelId = contributorModelId,
                        scopeId = command.sessionId ?: "global"
                    )
                }
            }.getOrNull()

            if (episodeEvent != null) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val examples =
                            CoordinatorExperienceStore.examplesForTask(
                                context = context,
                                sessionId = episodeSessionId,
                                taskId = command.taskId,
                                limit = 64
                            )
                        val constitutionTask = TaskState(
                            id = command.taskId ?: episodeSessionId,
                            projectId = command.sessionId,
                            goal = "Companion coordinator task",
                            status = TaskStatus.WAITING_MODEL
                        )
                        ConstitutionGenomeStore.ingestVerifiedRecoveryExamples(
                            context = context,
                            task = constitutionTask,
                            examples = examples,
                            contributorModelId = contributorModelId
                        )
                    }
                }
            }

            val memoryQuery = buildString {
                append(plan.request.tool)
                plan.request.args.toSortedMap().forEach { (key, value) ->
                    append(' ').append(key).append('=').append(value.take(220))
                }
            }
            val memoryHints = runCatching {
                withContext(Dispatchers.IO) {
                    (
                        CoordinatorExperienceStore.relevant(
                            context = context,
                            query = memoryQuery,
                            limit = 2,
                            scopeId = command.sessionId ?: "global",
                            modelId = contributorModelId
                        ) +
                            ExperienceMemoryStore.relevant(
                                context = context,
                                query = memoryQuery,
                                limit = 2
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
                contributorModelId = contributorModelId,
                episodeEventId = episodeEvent?.id
            )
            if (result.outcomeUnknown) taskGrant = taskGrant?.copy(paused = true)
            try { CompanionTaskGrantStore.finish(context, command.fingerprint, taskGrant) }
            catch (e: Exception) { taskGrant = taskGrant?.copy(paused = true) }
            resultExpanded = false
            StateVault.requestSave(context)
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
            !busy && ((safeAuto && isSafeReadOnly(command)) || grantAllows(command)) -> {
                status = "Approved automatic tool detected."
                executeCommand(command, automatic = true)
            }
            else -> status = "Tool request detected. Review it before running."
        }
    }

    fun refreshCommand(force: Boolean = false) {
        val snapshot = LumenaAccessibilityService.lastChatGptSnapshot
        val command = CompanionProtocol.parse(snapshot)
        if (command == null) {
            status = CompanionProtocol.captureDiagnostic(snapshot)
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

    LaunchedEffect(safeAuto, token, bridgeUrl, taskGrant) {
        while (true) {
            val service = LumenaAccessibilityService.instance
            val now = System.currentTimeMillis()
            val mayScan = CompanionRequestLifecycle.shouldAutoScan(
                isGenerating = service?.isChatGptGenerating() == true,
                lastUpdatedAtMs = LumenaAccessibilityService.lastChatGptUpdatedAt,
                nowMs = now
            )
            if (mayScan) {
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
            }
            delay(700)
        }
    }

    // Processing above belongs to the app composition, not the selected tab.
    if (!visible) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Lumena Companion", style = MaterialTheme.typography.headlineMedium)
        Text(status, style = MaterialTheme.typography.bodyMedium)
        taskGrant?.let { grant ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Дозвіл для завдання: ${grant.taskId}")
                    Text("${if (grant.paused) "Призупинено" else "Активний"} · ${grant.patterns.joinToString()}\nPython: ${if (grant.allowPython) "дозволено" else "вручну"}. Без ліміту часу й команд; зберігається після перезапуску.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (setTaskGrant(null)) status = "Дозвіл скасовано; запущену команду це не зупиняє." }) {
                        Text("Скасувати дозвіл")
                    }
                }
            }
        }
        if (handoffDraft.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Повідомлення з Local", style = MaterialTheme.typography.titleMedium)
                    Text("Переглянь текст перед передаванням у ChatGPT.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = handoffDraft,
                        onValueChange = {
                            handoffDraft = it
                            CompanionHandoffStore.save(context, it)
                        },
                        label = { Text("Текст для Companion") },
                        minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { openChatGptWith(handoffDraft, send = false) }) {
                        Text("Вставити в ChatGPT")
                    }
                    TextButton(onClick = {
                        handoffDraft = ""
                        CompanionHandoffStore.save(context, "")
                    }) { Text("Очистити чернетку") }
                }
            }
        }
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
            val autoEligible = (safeAuto && readOnly) || grantAllows(command)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(plan.request.tool, style = MaterialTheme.typography.titleMedium)
                    Text(plan.reason)
                    Text("Args: ${plan.request.args}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            !plan.allowed -> "BLOCKED by tool registry"
                            autoEligible -> "Дозволено автоматичне виконання"
                            else -> taskGrant?.denial(command, bridgeUrl, token) ?: "Потрібен разовий запуск або дозвіл для задачі."
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
                    if (plan.allowed && CompanionTaskGrant.canOffer(command)) {
                        TextButton(enabled = !busy, onClick = {
                            grantEditor = command.fingerprint
                            grantPaths = taskGrant?.takeIf { it.sessionId == command.sessionId && it.taskId == command.taskId }?.patterns?.joinToString("\n") ?: (plan.request.args["path"] ?: plan.request.args["script"]).orEmpty()
                            grantPython = taskGrant?.allowPython == true
                        }) { Text("Дозволити виконання плану…") }
                        if (grantEditor == command.fingerprint) {
                            Text("Сесія: ${command.sessionId}\nЗавдання: ${command.taskId}\nМодель: ${command.modelId.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                            Text("Дозвіл зберігається до скасування для цієї сесії, задачі, моделі та bridge. Запис — лише в перелічені файли. Звичайна помилка не скасовує дозвіл; невідомий результат призупиняє його.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = grantPaths, onValueChange = { grantPaths = it },
                                label = { Text("Файли: по одному в рядку; * наприкінці — префікс імені") },
                                modifier = Modifier.fillMaxWidth(), minLines = 2)
                            Text("Приклад: aquarium-v2.part* дозволяє запис частин у цьому каталозі. Існуючі файли також можуть змінюватися.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Switch(checked = grantPython, onCheckedChange = { grantPython = it })
                                Text("Автоматично запускати Python-скрипти з переліку")
                            }
                            Text("Python виконується з правами Termux і може звертатися поза цими файлами. Перелік обмежує вибір скрипта, а не його дії.", style = MaterialTheme.typography.bodySmall)
                            Button(enabled = !busy, onClick = {
                                val connection = LumenaPreferences.load(context)
                                val grant = CompanionTaskGrant.create(command, grantPaths,
                                    connection.bridgeUrl, connection.bridgeToken, allowPython = grantPython)
                                if (grant == null) status = "Некоректні шляхи або немає session_id / task_id."
                                else if (setTaskGrant(grant)) {
                                    grantEditor = null
                                    status = "Дозвіл збережено для задачі та перелічених файлів/скриптів."
                                    acceptDetected(command)
                                }
                            }) { Text("Дозволити й продовжити") }
                            TextButton(onClick = { grantEditor = null }) { Text("Назад") }
                        }
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
            Text("4 · Попередній результат", style = MaterialTheme.typography.titleLarge)
            Text("Це результат виконаної команди. Нові команди та Run once — у секції 3 вище.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { resultExpanded = !resultExpanded }) {
                Text(if (resultExpanded) "Згорнути результат" else "Показати результат")
            }
            if (resultExpanded) Card(modifier = Modifier.fillMaxWidth()) {
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
            "Safe Auto виконує читання. Дозвіл для задачі зберігається після перезапуску без ліміту часу та кількості команд. Python можна включити окремо. Інша задача, модель, bridge або шлях потребують нового дозволу. Завершивши роботу, натисни «Скасувати дозвіл».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
