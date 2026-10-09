package com.lumena.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.core.BridgeCompatibility
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.settings.ModuleSettings

/** Kernel vs modules (charter ART-14): every module can be switched off; the kernel keeps working. */
@Composable
fun ModulesPanel(onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    var disabled by remember { mutableStateOf(ModuleSettings.disabled(context)) }
    val violations = remember { ModuleRegistry.violations() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Модулі", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ядро Lumena (хартія, повноваження, докази, досвід) працює з будь-яким набором модулів. " +
                    "Модуль додає можливості, але не повноваження; вимкнений модуль не маршрутизує, не виконує і нічого не зберігає.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Termux bridge на телефоні: " +
                    (BridgeCompatibility.observedVersion()?.let { "v$it" } ?: "ще не перевірено"),
                style = MaterialTheme.typography.labelMedium
            )
            ModuleRegistry.all().forEach { module ->
                val m = module.manifest
                val enabled = m.id !in disabled
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${m.title} · v${m.version}", style = MaterialTheme.typography.bodyMedium)
                        Text(m.description, style = MaterialTheme.typography.bodySmall)
                        if (m.tools.isNotEmpty()) {
                            Text(
                                "Інструменти: " + m.tools.joinToString(", ") { it.spec.name },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (enabled) {
                            ModuleRegistry.unavailableReason(module)?.let { reason ->
                                Text(
                                    "Недоступний: $reason. Онови bridge у Termux: bash install_bridge.sh, потім перезапусти Lumena.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            ModuleSettings.setEnabled(context, m.id, on)
                            disabled = ModuleSettings.disabled(context)
                            onChanged()
                        }
                    )
                }
            }
            if (violations.isNotEmpty()) {
                Text("Ядро відхилило модулі:", style = MaterialTheme.typography.labelMedium)
                violations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
