package com.lumena.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumena.android.settings.ConstitutionInspectorConflict
import com.lumena.android.settings.ConstitutionInspectorEntry
import com.lumena.android.settings.ConstitutionInspectorSnapshot
import com.lumena.android.settings.ImportedConstitutionInspectorEntry

private const val MAX_VISIBLE_INSPECTOR_ENTRIES = 12

@Composable
internal fun ConstitutionGenomeInspectorPanel(
    snapshot: ConstitutionInspectorSnapshot
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Constitution Genome Inspector",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "Пояснює, чому Lumena має конкретне правило, звідки воно взялося і яку authority реально має. Imported/learned записи не є permission.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Hard DNA ${snapshot.hardDna.size} · Learned ${snapshot.learned.size} · User constraints ${snapshot.userConstraints.size} · Contested ${snapshot.contested.size} · Imported ${snapshot.importedLearned.size + snapshot.importedUserConstraints.size}",
            style = MaterialTheme.typography.labelSmall
        )

        LocalSection(
            title = "Hard DNA",
            emptyText = "Немає Hard DNA — це неочікуваний стан.",
            entries = snapshot.hardDna
        )
        LocalSection(
            title = "Learned",
            emptyText = "Локально підтверджених learned-правил ще немає.",
            entries = snapshot.learned
        )
        LocalSection(
            title = "User constraints",
            emptyText = "Активних локальних user constraints немає.",
            entries = snapshot.userConstraints
        )
        ConflictSection(snapshot.contested)
        ImportedSection(
            title = "Imported · pending local revalidation",
            emptyText = "Немає імпортованих learned constitutional seeds.",
            entries = snapshot.importedLearned
        )
        ImportedSection(
            title = "Imported user constraints · pending reconfirmation",
            emptyText = "Немає імпортованих user-constraint records.",
            entries = snapshot.importedUserConstraints
        )
    }
}

@Composable
private fun LocalSection(
    title: String,
    emptyText: String,
    entries: List<ConstitutionInspectorEntry>
) {
    Text(
        "$title · ${entries.size}",
        style = MaterialTheme.typography.titleSmall
    )
    if (entries.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall)
        return
    }
    entries.take(MAX_VISIBLE_INSPECTOR_ENTRIES).forEach {
        LocalEntryCard(it)
    }
    if (entries.size > MAX_VISIBLE_INSPECTOR_ENTRIES) {
        Text(
            "Показано $MAX_VISIBLE_INSPECTOR_ENTRIES з ${entries.size}; повний стан зберігається локально.",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun LocalEntryCard(
    entry: ConstitutionInspectorEntry
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "${entry.id} · ${entry.status} · ${entry.authority}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "${entry.kind} · ${entry.stance} · ${entry.scope}",
                style = MaterialTheme.typography.labelSmall
            )
            Text(entry.statement, style = MaterialTheme.typography.bodySmall)
            Text(
                "WHY: ${entry.rationale}",
                style = MaterialTheme.typography.bodySmall
            )
            if (entry.threatPrevented.isNotBlank()) {
                Text(
                    "Threat prevented: ${entry.threatPrevented}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                entry.explanation,
                style = MaterialTheme.typography.bodySmall
            )
            if (
                entry.localEvidenceCount > 0 ||
                entry.distinctLocalContexts > 0
            ) {
                Text(
                    "Local verified evidence: ${entry.localEvidenceCount} · distinct contexts: ${entry.distinctLocalContexts}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (entry.contributorModelIds.isNotEmpty()) {
                Text(
                    "Models: ${entry.contributorModelIds.joinToString()}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (entry.enforcementPoints.isNotEmpty()) {
                Text(
                    "Enforcement: ${entry.enforcementPoints.joinToString()}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (entry.regressionTests.isNotEmpty()) {
                Text(
                    "Regression tests: ${entry.regressionTests.joinToString()}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (entry.provenance.isNotEmpty()) {
                Text("Provenance:", style = MaterialTheme.typography.labelSmall)
                entry.provenance.take(6).forEach { source ->
                    Text(
                        "↳ $source",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (entry.provenance.size > 6) {
                    Text(
                        "↳ +${entry.provenance.size - 6} more",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictSection(
    conflicts: List<ConstitutionInspectorConflict>
) {
    Text(
        "Contested · ${conflicts.size}",
        style = MaterialTheme.typography.titleSmall
    )
    if (conflicts.isEmpty()) {
        Text(
            "Невирішених локальних constitutional conflicts немає.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }

    conflicts.take(MAX_VISIBLE_INSPECTOR_ENTRIES).forEach { conflict ->
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    conflict.claimKey,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Scope: ${conflict.scope} · Hard invariant present: ${if (conflict.hasHardInvariant) "YES" else "NO"}",
                    style = MaterialTheme.typography.labelSmall
                )
                conflict.rules.forEach { rule ->
                    Text(
                        "${rule.id} · ${rule.stance} · ${rule.authority} · ${rule.status} · sources=${rule.sourceKinds.joinToString()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    if (conflicts.size > MAX_VISIBLE_INSPECTOR_ENTRIES) {
        Text(
            "Показано $MAX_VISIBLE_INSPECTOR_ENTRIES з ${conflicts.size} conflicts.",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ImportedSection(
    title: String,
    emptyText: String,
    entries: List<ImportedConstitutionInspectorEntry>
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (entries.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodySmall)
        return
    }

    entries.take(MAX_VISIBLE_INSPECTOR_ENTRIES).forEach { entry ->
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "${entry.id} · ${entry.sourceStatus} · ${entry.originAuthority}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "${entry.kind} · ${entry.stance} · source scope ${entry.sourceScopeHash.take(16)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(entry.statement, style = MaterialTheme.typography.bodySmall)
                Text(
                    "WHY (source device): ${entry.rationale}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Source device: ${entry.sourceDeviceHash.take(16)} · source evidence refs: ${entry.sourceEvidenceCount}",
                    style = MaterialTheme.typography.labelSmall
                )
                if (entry.contributorModelIds.isNotEmpty()) {
                    Text(
                        "Source models: ${entry.contributorModelIds.joinToString()}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    "Pending: ${entry.activationRequirement}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    entry.explanation,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    if (entries.size > MAX_VISIBLE_INSPECTOR_ENTRIES) {
        Text(
            "Показано $MAX_VISIBLE_INSPECTOR_ENTRIES з ${entries.size} imported records.",
            style = MaterialTheme.typography.labelSmall
        )
    }
}
