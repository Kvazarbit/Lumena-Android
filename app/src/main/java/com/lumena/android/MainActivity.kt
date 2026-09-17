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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.LumenaAccessibilityService
import com.lumena.android.agent.core.TaskStatus
import com.lumena.android.agent.local.AgentPanel
import com.lumena.android.agent.runtime.AgentRunCoordinator
import com.lumena.android.companion.CompanionScreen
import com.lumena.android.settings.HistoryTreeStore
import com.lumena.android.settings.LocalSessionStore
import com.lumena.android.ui.HistoryTreeDrawer
import com.lumena.android.ui.LumenaTheme
import com.lumena.android.ui.WorkflowChatScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var refreshToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LumenaTheme {
                LumenaApp(refreshToken)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshToken++
    }

    private fun serviceComponent(): ComponentName =
        ComponentName(this, LumenaAccessibilityService::class.java)

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!enabled) return false
        val expected = serviceComponent().flattenToString()
        val services = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return services.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    private fun cancelPersistedTask(reason: String) {
        val snapshot = LocalSessionStore.load(this)
        val task = snapshot.task ?: return
        if (task.status !in setOf(
                TaskStatus.NEW,
                TaskStatus.PLANNING,
                TaskStatus.WAITING_CONFIRMATION,
                TaskStatus.EXECUTING,
                TaskStatus.VERIFYING,
                TaskStatus.WAITING_MODEL
            )
        ) return

        LocalSessionStore.save(
            this,
            snapshot.copy(
                task = task.copy(
                    status = TaskStatus.CANCELLED,
                    errors = (task.errors + reason).takeLast(8)
                ),
                pending = null
            )
        )
    }

    @Composable
    private fun LumenaApp(refreshToken: Int) {
        var tab by remember { mutableIntStateOf(0) }
        val agentWorkScope = rememberCoroutineScope()
        val agentRunCoordinator = remember { AgentRunCoordinator() }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val drawerScope = rememberCoroutineScope()
        var historyState by remember { mutableStateOf(HistoryTreeStore.load(this@MainActivity)) }

        fun reloadHistory() {
            historyState = HistoryTreeStore.load(this@MainActivity)
        }

        fun stopBeforeContextSwitch(reason: String) {
            agentRunCoordinator.cancel(reason)
            cancelPersistedTask(reason)
        }

        fun activateBranch(branchId: String) {
            if (branchId == historyState.activeBranchId) {
                drawerScope.launch { drawerState.close() }
                return
            }
            stopBeforeContextSwitch("Switched history branch")
            if (HistoryTreeStore.activate(this@MainActivity, branchId)) reloadHistory()
            drawerScope.launch { drawerState.close() }
        }

        fun forkActive() {
            stopBeforeContextSwitch("Forked history context")
            HistoryTreeStore.forkActive(this@MainActivity)
            reloadHistory()
            drawerScope.launch { drawerState.close() }
        }

        fun createTask(title: String) {
            stopBeforeContextSwitch("Started a new task in this topic")
            HistoryTreeStore.createTask(this@MainActivity, title)
            reloadHistory()
            drawerScope.launch { drawerState.close() }
        }

        fun createTopic(title: String) {
            stopBeforeContextSwitch("Started a new history topic")
            HistoryTreeStore.createTopic(this@MainActivity, title)
            reloadHistory()
            drawerScope.launch { drawerState.close() }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = tab == 1,
            drawerContent = {
                ModalDrawerSheet {
                    HistoryTreeDrawer(
                        state = historyState,
                        onActivate = ::activateBranch,
                        onForkActive = ::forkActive,
                        onCreateTask = ::createTask,
                        onCreateTopic = ::createTopic,
                        onRenameActive = { title ->
                            HistoryTreeStore.renameBranch(
                                this@MainActivity,
                                historyState.activeBranchId,
                                title
                            )
                            reloadHistory()
                        },
                        onClose = { drawerScope.launch { drawerState.close() } }
                    )
                }
            }
        ) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Text("●") },
                            label = { Text("Companion") }
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = {
                                tab = 1
                                reloadHistory()
                            },
                            icon = { Text("◈") },
                            label = { Text("Local") }
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Text("◆") },
                            label = { Text("Tools") }
                        )
                    }
                }
            ) { innerPadding ->
                when (tab) {
                    0 -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) { CompanionScreen() }

                    1 -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = {
                                reloadHistory()
                                drawerScope.launch { drawerState.open() }
                            }) {
                                Text("☰ History")
                            }
                            val active = historyState.branches.firstOrNull {
                                it.id == historyState.activeBranchId
                            }
                            Text(
                                active?.let { "${it.topic} › ${it.taskTitle} › ${it.title}" } ?: "Local",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            key(historyState.activeBranchId) {
                                WorkflowChatScreen(
                                    agentWorkScope = agentWorkScope,
                                    runCoordinator = agentRunCoordinator
                                )
                            }
                        }
                    }

                    else -> ToolsScreen(
                        refreshToken = refreshToken,
                        modifier = Modifier.padding(innerPadding)
                    )
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
            rawServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        }

        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Tools", style = MaterialTheme.typography.headlineMedium)
            Text("Android control, Termux bridge and diagnostics")

            Text(
                if (enabled || LumenaAccessibilityService.instance != null)
                    "Accessibility: connected"
                else
                    "Accessibility: not connected"
            )

            Button(onClick = { openAccessibilitySettings() }) {
                Text("Open Accessibility settings")
            }

            OutlinedButton(onClick = { openAppDetails() }) {
                Text("Open Lumena app settings")
            }

            Button(onClick = {
                val service = LumenaAccessibilityService.instance
                if (service == null) {
                    snapshotText = "Accessibility service is not running yet."
                } else {
                    val s = service.snapshot()
                    snapshotText = buildString {
                        appendLine("Package: ${s.packageName}")
                        appendLine("Nodes: ${s.nodes.size}")
                        s.nodes.take(30).forEach {
                            appendLine("• ${it.text ?: it.contentDescription ?: it.className}")
                        }
                    }
                }
            }) {
                Text("Read current screen")
            }

            HorizontalDivider()
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Text("Expected service: ${serviceComponent().flattenToString()}")
            Text("System says enabled: $enabled")
            Text(
                if (rawServices.isBlank()) "Enabled services list: empty"
                else "Enabled services: $rawServices",
                style = MaterialTheme.typography.bodySmall
            )
            Text(snapshotText, style = MaterialTheme.typography.bodySmall)

            AgentPanel()
        }
    }
}
