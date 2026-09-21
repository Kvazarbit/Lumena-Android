package com.lumena.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lumena.android.llama.LlamaTuning
import com.lumena.android.settings.LumenaPreferences
import kotlin.math.roundToInt

@Composable
internal fun PerformanceSettingsAccordion(busy: Boolean) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var tuning by remember { mutableStateOf(LumenaPreferences.loadTuning(context)) }
    fun update(value: LlamaTuning) {
        if (busy) return
        tuning = value.normalized()
        LumenaPreferences.saveTuning(context, tuning)
    }

    HorizontalDivider()
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().semantics {
            stateDescription = if (expanded) "Розгорнуто" else "Згорнуто"
        }
    ) {
        Text((if (expanded) "▾ " else "▸ ") + "Пам’ять і продуктивність")
    }
    if (expanded) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Ручні значення — верхні межі. Автоматика може обрати менші значення через модель, нагрів або нестачу RAM. Зміни діють із наступного запиту.",
                style = MaterialTheme.typography.bodySmall
            )
            Row {
                TextButton(enabled = !busy, onClick = { update(LlamaTuning()) }) { Text("Автоматично") }
                TextButton(enabled = !busy, onClick = { update(LlamaTuning.ECONOMY) }) { Text("Економний") }
            }
            TuningSlider("Контекст", "Менший контекст зменшує кеш, але вміщує менше історії.",
                tuning.contextTokens, LlamaTuning.CONTEXT, "токенів", busy) { update(tuning.copy(contextTokens = it)) }
            TuningSlider("Пакет обробки", "Менший пакет знижує пікові витрати пам’яті; обробка запиту може бути повільнішою.",
                tuning.batchTokens, LlamaTuning.BATCH, "токенів", busy) { update(tuning.copy(batchTokens = it)) }
            TuningSlider("Потоки CPU", "Менше потоків знижує навантаження на процесор.",
                tuning.cpuThreads, LlamaTuning.THREADS, "", busy) { update(tuning.copy(cpuThreads = it)) }
            TuningSlider("Довжина відповіді", "Ліміт нових токенів за один виклик моделі.",
                tuning.responseTokens, LlamaTuning.RESPONSE, "токенів", busy) { update(tuning.copy(responseTokens = it)) }
            TuningSlider("Додатковий запас RAM", "Посилює перевірку вільної пам’яті. Це не жорсткий ліміт споживання застосунку.",
                tuning.extraRamMb, LlamaTuning.RESERVE, "МіБ", busy, zeroLabel = "Без додаткового запасу") {
                update(tuning.copy(extraRamMb = it))
            }
            Text("Ці параметри не зменшують розмір ваг GGUF у пам’яті. Економний пресет не змінює вибраний режим CPU/GPU.",
                style = MaterialTheme.typography.bodySmall)
            if (busy) Text("Зупиніть поточний запит, щоб змінити параметри.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TuningSlider(
    label: String, hint: String, value: Int, choices: List<Int>, unit: String,
    busy: Boolean, zeroLabel: String = "Авто", onChange: (Int) -> Unit
) {
    val selected = choices.indexOf(value).coerceAtLeast(0)
    val description = if (value == 0) zeroLabel else "$value $unit".trim()
    Text("$label: $description", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = selected.toFloat(),
        onValueChange = { onChange(choices[it.roundToInt().coerceIn(choices.indices)]) },
        valueRange = 0f..choices.lastIndex.toFloat(),
        steps = choices.size - 2,
        enabled = !busy,
        modifier = Modifier.semantics { contentDescription = label; stateDescription = description }
    )
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
