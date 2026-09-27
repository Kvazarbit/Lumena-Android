package com.lumena.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Small glyphs, with separate 48dp touch targets and accessibility labels.
@Composable
internal fun MessageActions(onCopy: () -> Unit, onCompanion: (() -> Unit)?,
    onEdit: (() -> Unit)?, editEnabled: Boolean) {
    Row {
        ActionIcon(CopyGlyph, "Копіювати", true, onCopy)
        if (onEdit != null) ActionIcon(EditGlyph, "Редагувати запит", editEnabled, onEdit)
        if (onCompanion != null) ActionIcon(CompanionGlyph, "Передати в Companion", true, onCompanion)
    }
}

@Composable
private fun ActionIcon(glyph: ImageVector, label: String, enabled: Boolean, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(glyph, contentDescription = label, modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.3f))
    }
}

private val CopyGlyph = ImageVector.Builder("Copy", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(16f, 1f); lineTo(4f, 1f); lineTo(4f, 17f); lineTo(6f, 17f)
        lineTo(6f, 3f); lineTo(16f, 3f); close()
        moveTo(8f, 5f); lineTo(20f, 5f); lineTo(20f, 23f); lineTo(8f, 23f); close()
        moveTo(10f, 7f); lineTo(10f, 21f); lineTo(18f, 21f); lineTo(18f, 7f); close()
    }
}.build()
private val EditGlyph = ImageVector.Builder("Edit", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 17f); lineTo(3f, 21f); lineTo(7f, 21f)
        lineTo(19f, 9f); lineTo(15f, 5f); close()
        moveTo(16f, 4f); lineTo(20f, 8f); lineTo(22f, 6f)
        lineTo(18f, 2f); close()
    }
}.build()
private val CompanionGlyph = ImageVector.Builder("Companion", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(2f, 3f); lineTo(22f, 12f); lineTo(2f, 21f)
        lineTo(2f, 14f); lineTo(15f, 12f); lineTo(2f, 10f); close()
    }
}.build()
