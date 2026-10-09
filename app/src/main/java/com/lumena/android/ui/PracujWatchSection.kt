package com.lumena.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.core.ModuleRegistry
import com.lumena.android.listing.PracujSearch
import com.lumena.android.listing.PracujSource
import com.lumena.android.listing.PracujWatch
import com.lumena.android.modules.PracujModule
import java.text.DateFormat
import java.util.Date

/** Owner settings for the Pracuj.pl source of the listing-attention skill. */
@Composable
fun PracujWatchSection(onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    if (!ModuleRegistry.isEnabled(PracujModule.ID)) {
        Text("Pracuj.pl: модуль вимкнено в розділі «Модулі».", style = MaterialTheme.typography.bodySmall)
        return
    }
    val saved = remember { PracujWatch.load(context) }
    var enabled by remember { mutableStateOf(saved.enabled) }
    var city by remember { mutableStateOf(saved.search.city) }
    var radius by remember { mutableStateOf(saved.search.radiusKm.toString()) }
    var keywords by remember { mutableStateOf(saved.search.keywords) }
    var interval by remember { mutableStateOf(saved.intervalMinutes.toString()) }
    var status by remember { mutableStateOf(PracujWatch.status(context)) }

    fun current() = PracujWatch.Config(
        enabled = enabled,
        search = PracujSearch(
            city = city,
            radiusKm = radius.toIntOrNull() ?: 10,
            keywords = keywords
        ),
        intervalMinutes = (interval.toIntOrNull() ?: 30).coerceAtLeast(PracujWatch.MIN_INTERVAL_MINUTES)
    )

    HorizontalDivider()
    Text("Вакансії Pracuj.pl", style = MaterialTheme.typography.titleSmall)
    Text(
        "Lumena читає публічний пошук Pracuj.pl із вказаним інтервалом і передає вакансії цьому навику. " +
            "Якщо сайт обмежить доступ, перевірка зупиняється — захист не обходиться.",
        style = MaterialTheme.typography.bodySmall
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Стежити", modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = { enabled = it })
    }
    OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("Місто") }, singleLine = true)
    OutlinedTextField(
        value = radius,
        onValueChange = { radius = it.filter(Char::isDigit).take(3) },
        label = { Text("Радіус, км") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
    OutlinedTextField(
        value = keywords,
        onValueChange = { keywords = it },
        label = { Text("Ключові слова (необов'язково)") },
        singleLine = true
    )
    OutlinedTextField(
        value = interval,
        onValueChange = { interval = it.filter(Char::isDigit).take(4) },
        label = { Text("Інтервал, хв (мін. ${PracujWatch.MIN_INTERVAL_MINUTES})") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
    Text(PracujSource.searchUrl(current().search), style = MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            PracujWatch.save(context, current())
            status = PracujWatch.status(context)
            onChanged()
        }) { Text("Зберегти") }
        OutlinedButton(onClick = {
            PracujWatch.save(context, current())
            PracujWatch.runNow(context)
            onChanged()
        }) { Text("Перевірити зараз") }
    }
    val at = if (status.at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.at)) else ""
    Text(
        "Остання перевірка: ${status.message}${if (at.isNotEmpty()) " ($at)" else ""}",
        style = MaterialTheme.typography.bodySmall
    )
}
