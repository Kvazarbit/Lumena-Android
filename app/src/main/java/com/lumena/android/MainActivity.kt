package com.lumena.android

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.LumenaAccessibilityService

class MainActivity : ComponentActivity() {

    private var refreshToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumenaHome(refreshToken) }
    }

    override fun onResume() {
        super.onResume()
        refreshToken++
    }

    private fun serviceComponent(): ComponentName =
        ComponentName(this, LumenaAccessibilityService::class.java)

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1

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

    @Composable
    private fun LumenaHome(refreshToken: Int) {
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

        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Lumena Android", style = MaterialTheme.typography.headlineMedium)
                    Text("Screen Agent v0.2 — accessibility diagnostics + transparent control")

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
                    }) { Text("Read current screen") }

                    HorizontalDivider()

                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text("Expected service: ${serviceComponent().flattenToString()}")
                    Text("System says enabled: $enabled")
                    Text(
                        if (rawServices.isBlank())
                            "Enabled services list: empty"
                        else
                            "Enabled services: $rawServices",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(snapshotText, style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(24.dp))
                    Text(
                        "If Lumena is greyed out in Accessibility, open Lumena app settings and use the three-dot menu for 'Allow restricted settings' when your Android firmware provides that option.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        "Lumena never silently performs sensitive actions. Actions that can alter another app require explicit approval.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
