package com.lumena.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.LumenaAccessibilityService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumenaHome() }
    }

    @Composable
    private fun LumenaHome() {
        var status by remember { mutableStateOf("Accessibility: not connected") }
        var snapshotText by remember { mutableStateOf("No snapshot yet") }

        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Lumena Android", style = MaterialTheme.typography.headlineMedium)
                    Text("Screen Agent v0.1 — transparent, user-controlled Android automation")
                    Text(status)

                    Button(onClick = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Enable Screen Agent") }

                    Button(onClick = {
                        val service = LumenaAccessibilityService.instance
                        if (service == null) {
                            status = "Accessibility: not connected"
                        } else {
                            status = "Accessibility: connected"
                            val s = service.snapshot()
                            snapshotText = buildString {
                                appendLine("Package: ${s.packageName}")
                                appendLine("Nodes: ${s.nodes.size}")
                                s.nodes.take(30).forEach {
                                    appendLine("• ${it.text ?: it.contentDescription ?: it.className}")
                                }
                            }
                        }
                    }) { Text("Read current screen") }

                    HorizontalDivider()
                    Text(snapshotText, style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.weight(1f))
                    Text(
                        "Lumena never silently performs sensitive actions. Actions that can alter another app require explicit approval.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
