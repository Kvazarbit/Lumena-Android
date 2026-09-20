package com.lumena.android

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.LumenaAccessibilityService
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.AgentPanel
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.runtime.AgentRunCoordinator
import com.lumena.android.companion.CompanionScreen
import com.lumena.android.llama.EmbeddedLlamaClient
import com.lumena.android.settings.HistoryTreeStore
import com.lumena.android.settings.LocalSessionStore
import com.lumena.android.settings.LumenaPreferences
import com.lumena.android.ui.AgentWorkDrawer
import com.lumena.android.ui.HistoryTreeDrawer
import com.lumena.android.ui.LumenaTheme
import com.lumena.android.ui.WorkflowChatScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var refreshToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumenaTheme { LumenaApp(refreshToken) } }
    }

    override fun onResume() { super.onResume(); refreshToken++ }

    private fun serviceComponent() = ComponentName(this, LumenaAccessibilityService::class.java)
    private fun isAccessibilityEnabled(): Boolean {
        if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val expected = serviceComponent()
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { enabled ->
                enabled.packageName.equals(expected.packageName, ignoreCase = true) &&
                    enabled.className.equals(expected.className, ignoreCase = true)
            }
    }
    private fun openAccessibilitySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    private fun openAppDetails() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") })

    private fun cancelPersistedTask(reason: String) {
        val snapshot = LocalSessionStore.load(this)
        val task = snapshot.task ?: return
        if (task.status !in setOf(TaskStatus.NEW, TaskStatus.PLANNING, TaskStatus.WAITING_CONFIRMATION, TaskStatus.EXECUTING, TaskStatus.VERIFYING, TaskStatus.WAITING_MODEL)) return
        LocalSessionStore.save(this, snapshot.copy(task = task.copy(status = TaskStatus.CANCELLED, errors = (task.errors + reason).takeLast(8)), pending = null))
    }

    @Composable
    private fun LumenaApp(refreshToken: Int) {
        var tab by remember { mutableIntStateOf(0) }
        val agentWorkScope = rememberCoroutineScope()
        val coordinator = remember { AgentRunCoordinator() }
        val leftDrawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var agentOpen by remember { mutableStateOf(false) }
        var historyState by remember { mutableStateOf(HistoryTreeStore.load(this@MainActivity)) }
        var liveSession by remember { mutableStateOf(LocalSessionStore.load(this@MainActivity)) }
        var model by remember { mutableStateOf(LumenaPreferences.load(this@MainActivity).selectedModel) }

        LaunchedEffect(refreshToken) {
            val bridgeSettings = LumenaPreferences.load(this@MainActivity)
            if (bridgeSettings.bridgeToken.isNotBlank()) {
                TermuxBridgeClient(
                    bridgeSettings.bridgeUrl,
                    bridgeSettings.bridgeToken,
                    this@MainActivity
                ).execute(ToolRequest("health"))
            }
        }

        fun reloadHistory() { historyState = HistoryTreeStore.load(this@MainActivity) }
        fun stopBeforeSwitch(reason: String) {
            EmbeddedLlamaClient.cancelActiveGeneration()
            coordinator.cancel(reason)
            cancelPersistedTask(reason)
        }
        fun closeRight() { agentOpen = false }
        fun openLeft() { agentOpen = false; reloadHistory(); scope.launch { leftDrawer.open() } }
        fun openRight() { scope.launch { leftDrawer.close() }; liveSession = LocalSessionStore.load(this@MainActivity); model = LumenaPreferences.load(this@MainActivity).selectedModel; agentOpen = true }

        LaunchedEffect(tab, agentOpen, coordinator.active) {
            while (tab == 1) {
                liveSession = LocalSessionStore.load(this@MainActivity)
                model = LumenaPreferences.load(this@MainActivity).selectedModel
                delay(600)
            }
        }

        fun activateBranch(id: String) {
            if (id != historyState.activeBranchId) {
                stopBeforeSwitch("Switched history branch")
                if (HistoryTreeStore.activate(this@MainActivity, id)) reloadHistory()
            }
            scope.launch { leftDrawer.close() }
        }
        fun forkActive() { stopBeforeSwitch("Forked history context"); HistoryTreeStore.forkActive(this@MainActivity); reloadHistory(); scope.launch { leftDrawer.close() } }
        fun createTask(title: String) { stopBeforeSwitch("Started a new task in this topic"); HistoryTreeStore.createTask(this@MainActivity, title); reloadHistory(); scope.launch { leftDrawer.close() } }
        fun createTopic(title: String) { stopBeforeSwitch("Started a new history topic"); HistoryTreeStore.createTopic(this@MainActivity, title); reloadHistory(); scope.launch { leftDrawer.close() } }

        ModalNavigationDrawer(
            drawerState = leftDrawer,
            gesturesEnabled = tab == 1 && !agentOpen,
            drawerContent = {
                ModalDrawerSheet {
                    HistoryTreeDrawer(
                        state = historyState,
                        onActivate = ::activateBranch,
                        onForkActive = ::forkActive,
                        onCreateTask = ::createTask,
                        onCreateTopic = ::createTopic,
                        onRenameActive = { title -> HistoryTreeStore.renameBranch(this@MainActivity, historyState.activeBranchId, title); reloadHistory() },
                        onClose = { scope.launch { leftDrawer.close() } }
                    )
                }
            }
        ) {
            Scaffold(
                bottomBar = {
                    if (tab != 1) NavigationBar {
                        NavigationBarItem(tab == 0, { tab = 0 }, { Text("●") }, label = { Text("Companion") })
                        NavigationBarItem(tab == 1, { tab = 1; reloadHistory() }, { Text("◈") }, label = { Text("Local") })
                        NavigationBarItem(tab == 2, { tab = 2 }, { Text("◆") }, label = { Text("Tools") })
                    }
                }
            ) { inner ->
                when (tab) {
                    0 -> Column(Modifier.fillMaxSize().padding(inner)) { CompanionScreen() }
                    1 -> Box(Modifier.fillMaxSize().padding(inner)) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(onClick = ::openLeft) { Text("☰") }
                                val task = liveSession.task
                                Text(
                                    when {
                                        coordinator.active && task != null -> "● ${task.step}/${task.maxSteps} · ${coordinator.stage}"
                                        model.isNotBlank() -> "Lumena · $model"
                                        else -> "Lumena"
                                    },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                TextButton(onClick = ::openRight) { Text("☷") }
                            }
                            Box(Modifier.weight(1f)) {
                                key(historyState.activeBranchId) {
                                    WorkflowChatScreen(agentWorkScope = agentWorkScope, runCoordinator = coordinator)
                                }
                            }
                        }
                        if (agentOpen) {
                            Surface(
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                tonalElevation = 8.dp,
                                shadowElevation = 12.dp
                            ) {
                                AgentWorkDrawer(
                                    task = liveSession.task,
                                    coordinator = coordinator,
                                    model = model,
                                    pendingApproval = liveSession.pending != null,
                                    onStop = {
                                        EmbeddedLlamaClient.cancelActiveGeneration()
                                        coordinator.cancel("Stopped from Agent panel")
                                        cancelPersistedTask("Stopped from Agent panel")
                                        liveSession = LocalSessionStore.load(this@MainActivity)
                                    },
                                    onClose = ::closeRight
                                )
                            }
                        }
                    }
                    else -> ToolsScreen(refreshToken, Modifier.padding(inner))
                }
            }
        }
    }

    @Composable
    private fun ToolsScreen(refreshToken: Int, modifier: Modifier = Modifier) {
        var snapshotText by remember { mutableStateOf("No snapshot yet") }
        var enabled by remember { mutableStateOf(false) }
        var rawServices by remember { mutableStateOf("") }
        LaunchedEffect(refreshToken) {
            enabled = isAccessibilityEnabled()
            rawServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        }
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Tools", style = MaterialTheme.typography.headlineMedium)
            Text("Android control, Termux bridge and diagnostics")
            Text(if (enabled || LumenaAccessibilityService.instance != null) "Accessibility: connected" else "Accessibility: not connected")
            Button(onClick = { openAccessibilitySettings() }) { Text("Open Accessibility settings") }
            OutlinedButton(onClick = { openAppDetails() }) { Text("Open Lumena app settings") }
            Button(onClick = {
                val service = LumenaAccessibilityService.instance
                snapshotText = if (service == null) "Accessibility service is not running yet." else {
                    val s = service.snapshot(); buildString { appendLine("Package: ${s.packageName}"); appendLine("Nodes: ${s.nodes.size}"); s.nodes.take(30).forEach { appendLine("• ${it.text ?: it.contentDescription ?: it.className}") } }
                }
            }) { Text("Read current screen") }
            HorizontalDivider()
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Text("Expected service: ${serviceComponent().flattenToString()}")
            Text("System says enabled: $enabled")
            Text(if (rawServices.isBlank()) "Enabled services list: empty" else "Enabled services: $rawServices", style = MaterialTheme.typography.bodySmall)
            Text(snapshotText, style = MaterialTheme.typography.bodySmall)
            AgentPanel()
        }
    }
}
