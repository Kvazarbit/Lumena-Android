package com.lumena.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumena.android.settings.StateVault
import kotlinx.coroutines.*

@Composable
fun StateVaultPanel(agentBusy: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(StateVault.status(context)) }
    var folder by remember { mutableStateOf(StateVault.folder(context)) }
    var auto by remember { mutableStateOf(StateVault.enabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var copies by remember { mutableStateOf<List<StateVault.Copy>>(emptyList()) }
    var selected by remember { mutableStateOf<StateVault.Copy?>(null) }
    var staged by remember { mutableStateOf(false) }
    val allowed = !busy && !agentBusy && !StateVault.companionBusy && !StateVault.restoring
    suspend fun work(block: suspend () -> String) {
        busy = true
        try { status = block() }
        catch (e: Exception) { status = "Не виконано: ${e.message}" }
        finally { busy = false }
    }
    val chooseSave = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            work {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                StateVault.setFolder(context, uri); folder = uri.toString()
                withContext(Dispatchers.IO) { StateVault.save(context) }
            }
        }
    }
    val chooseLoad = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            work {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                copies = withContext(Dispatchers.IO) { StateVault.list(context, uri) }
                if (copies.isEmpty()) "У папці немає копій Lumena" else "Вибери копію для відновлення"
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(5000); if (!busy && copies.isEmpty() && !staged) status = StateVault.status(context) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Папка пам’яті Lumena", style = MaterialTheme.typography.titleMedium)
            Text("Історія, досвід, конституція, Evidence Graph і калібрування зберігаються у перевірених копіях поза APK. Робочий стан залишається в застосунку.")
            Text(folder ?: "Папку ще не вибрано", style = MaterialTheme.typography.bodySmall)
            Text("Створи окрему папку, наприклад Documents/Lumena. Копії містять приватні чати. Моделі, проєкти Termux, bridge token та дозволи не включаються.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = allowed, onClick = { chooseSave.launch(folder?.let(Uri::parse)) }) { Text("Вибрати папку збереження") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Автозбереження", modifier = Modifier.weight(1f))
                Switch(checked = auto, enabled = allowed, onCheckedChange = { auto = it; StateVault.setEnabled(context, it); if (it) StateVault.requestSave(context) })
            }
            Text("Після змін і кожні 30 секунд, поки процес Lumena працює. Зберігаються останні 10 копій зі змінами. Перед видаленням APK натисни «Зберегти зараз» і перевір успішний статус.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = allowed && folder != null, onClick = { scope.launch { work { withContext(Dispatchers.IO) { StateVault.save(context) } } } }) { Text("Зберегти зараз") }
            OutlinedButton(enabled = allowed, onClick = { chooseLoad.launch(null) }) { Text("Завантажити з папки…") }
            copies.forEach { copy -> TextButton(enabled = allowed, onClick = { selected = copy }) { Text(copy.name) } }
            if (copies.isNotEmpty()) TextButton(onClick = { copies = emptyList() }) { Text("Закрити список") }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (staged) {
                Text("Копію перевірено. Закрий Lumena цією кнопкою та відкрий знову: стан буде відновлено перед запуском агента.")
                Button(onClick = { (context as? Activity)?.finishAffinity(); android.os.Process.killProcess(android.os.Process.myPid()) }) { Text("Закрити для відновлення") }
            }
        }
    }
    selected?.let { copy ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Відновити стан Lumena?") },
            text = { Text("${copy.name}\nПоточні чати й досвід буде замінено цією копією. Спочатку збережи поточний стан, якщо він потрібен. Використовуй лише власні копії. Дозволи та незавершені команди не запускаються після відновлення.") },
            confirmButton = { TextButton(enabled = allowed, onClick = {
                selected = null
                scope.launch { work {
                    withContext(Dispatchers.IO) { StateVault.stage(context, copy) }
                    staged = true; copies = emptyList(); "Копію перевірено й підготовлено"
                } }
            }) { Text("Відновити") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Скасувати") } })
    }
}
