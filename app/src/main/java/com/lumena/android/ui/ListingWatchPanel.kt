package com.lumena.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.lumena.android.listing.ListingAlerts
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.modules.ListingAttentionModule
import com.lumena.android.listing.ListingAttentionPolicy
import com.lumena.android.listing.ListingAttentionState
import com.lumena.android.listing.ListingDecision
import com.lumena.android.listing.ListingRecord
import com.lumena.android.settings.ListingAttentionStore
import com.lumena.android.settings.ListingCaptureSettings
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Status, recent decisions with their reasons, and 👍/👎 for the listing-attention skill. */
@Composable
fun ListingWatchPanel(refreshToken: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ListingAttentionState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var listenerOn by remember { mutableStateOf(false) }
    var alertsOn by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var captureRaw by remember { mutableStateOf(ListingCaptureSettings.isEnabled(context)) }
    val askAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    LaunchedEffect(refreshToken, tick) {
        listenerOn = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        alertsOn = ListingAlerts.canAlert(context)
        val loaded = withContext(Dispatchers.IO) { runCatching { ListingAttentionStore.load(context) } }
        state = loaded.getOrNull()
        error = loaded.exceptionOrNull()?.message
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Оголошення: навик відбору", style = MaterialTheme.typography.titleMedium)
            Text(
                "Lumena читає лише сповіщення OLX про нові оголошення (збережений пошук у застосунку OLX). " +
                    "Рідкісне приватне оголошення під ваші навички — гучне сповіщення; постійний набір — тиша. " +
                    "Навик вчиться тільки з ваших 👍/👎."
            )
            if (!ModuleRegistry.isEnabled(ListingAttentionModule.ID)) {
                Text("Модуль вимкнено в розділі «Модулі»: сповіщення OLX ігноруються і нічого не зберігається.")
            }
            Text(if (listenerOn) "Доступ до сповіщень: увімкнено" else "Доступ до сповіщень: вимкнено — Lumena не бачить OLX")
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Відкрити доступ до сповіщень") }
            if (!alertsOn) {
                Text("Сповіщення Lumena вимкнені — тривоги не прийдуть")
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        askAlerts.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Дозволити сповіщення Lumena") }
            }
            OutlinedButton(onClick = { tick++ }) { Text("Оновити") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Зберігати сирі OLX push для калібрування (окремий дозвіл, типово вимкнено)",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = captureRaw, onCheckedChange = { on ->
                    ListingCaptureSettings.setEnabled(context, on)
                    captureRaw = on
                    tick++
                })
            }
            PracujWatchSection { tick++ }
            error?.let { Text("Стан не прочитано: $it") }

            state?.let { current ->
                val m = ListingAttentionPolicy.metrics(current)
                val precision = m.alertPrecision()?.let { "${(it * 100).roundToInt()}%" } ?: "ще немає оцінок"
                Text(
                    "Побачено: ${m.seen} · тривог: ${m.alerts} · переглянути: ${m.digests}\n" +
                        "Оцінено: ${m.labelled} · влучність тривог: $precision · пропущено: ${m.missed}\n" +
                        "Кроків навчання: ${m.learningUpdates} · архів: ${(m.archiveWarmup * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                if (current.records.isEmpty()) {
                    Text("Ще жодного оголошення. Збережіть пошук в OLX з push-сповіщеннями.")
                }
                current.records.takeLast(15).asReversed().forEach { record ->
                    ListingRow(record) { useful ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { ListingAttentionStore.feedback(context, record.id, useful) }
                            }
                            tick++
                        }
                    }
                }
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "Сховати сирі сповіщення" else "Сирі сповіщення OLX (${current.rawCaptures.size})")
                }
                if (showRaw) {
                    current.rawCaptures.takeLast(10).asReversed().forEach { raw ->
                        Text(
                            "${raw.source} · ${raw.category ?: "-"}${if (raw.groupSummary) " · summary" else ""}\n" +
                                "title: ${raw.title}\ntext: ${raw.text}" +
                                if (raw.lines.isEmpty()) "" else "\nlines: ${raw.lines.joinToString(" | ")}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListingRow(record: ListingRecord, onFeedback: (Boolean) -> Unit) {
    val decision = when (record.decision) {
        ListingDecision.ALERT -> "ТРИВОГА"
        ListingDecision.DIGEST -> "переглянути"
        ListingDecision.QUIET -> "тиша"
    }
    val label = when (record.feedback) {
        1 -> " · ваша оцінка: 👍"
        -1 -> " · ваша оцінка: 👎"
        else -> ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(record.title.ifBlank { record.text.take(80) }, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${record.source} · $decision · ${(record.score * 100).roundToInt()}%$label\n${record.reasons.joinToString("; ")}",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onFeedback(true) }) { Text("👍 Варте уваги") }
            TextButton(onClick = { onFeedback(false) }) { Text("👎 Не те") }
        }
    }
}
