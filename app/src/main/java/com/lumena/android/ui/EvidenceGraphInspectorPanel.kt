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
import com.lumena.android.settings.EvidenceInspectorClaim
import com.lumena.android.settings.EvidenceInspectorSnapshot
import com.lumena.android.settings.EvidenceInspectorSource
import com.lumena.android.settings.EvidenceInspectorSemanticLink
import java.text.DateFormat
import java.util.Date

private const val MAX_VISIBLE_EVIDENCE_CLAIMS = 12
private const val MAX_VISIBLE_EVIDENCE_SOURCES = 6

@Composable
internal fun EvidenceGraphInspectorPanel(
    snapshot: EvidenceInspectorSnapshot
) {
    Column(
        verticalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Evidence Graph Inspector",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "Показує лише структуровані записи, прив’язані до verified TOOL_RESULT. Search snippet ≠ прочитана сторінка; corroborated ≠ permission; contested не приховується.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Claims ${snapshot.totalClaims} · Sources ${snapshot.totalSources} · Discovered ${snapshot.discovered} · Retrieved ${snapshot.retrieved} · Corroborated ${snapshot.corroborated} · Contested ${snapshot.contested} · Stale ${snapshot.staleEffective}",
            style = MaterialTheme.typography.labelSmall
        )

        if (snapshot.claims.isEmpty()) {
            Text(
                "Evidence Graph ще порожній. Він наповнюється тільки після verified web TOOL_RESULT.",
                style = MaterialTheme.typography.bodySmall
            )
            return
        }

        snapshot.claims
            .take(MAX_VISIBLE_EVIDENCE_CLAIMS)
            .forEach { claim ->
                ClaimCard(claim)
            }

        if (
            snapshot.claims.size >
            MAX_VISIBLE_EVIDENCE_CLAIMS
        ) {
            Text(
                "Показано $MAX_VISIBLE_EVIDENCE_CLAIMS з ${snapshot.claims.size} claims; повний граф зберігається локально.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ClaimCard(
    claim: EvidenceInspectorClaim
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "${claim.effectiveState} · ${claim.claimKey}",
                style = MaterialTheme.typography.titleSmall
            )

            if (
                claim.storedState !=
                claim.effectiveState
            ) {
                Text(
                    "Stored: ${claim.storedState} → effective: ${claim.effectiveState}",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Text(
                claim.statement,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                claim.explanation,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Evidence IDs: ${claim.evidenceCount} · project relevance ${claim.projectRelevancePercent}% · outcome ${claim.outcome} · outcome proofs ${claim.outcomeEvidenceCount}",
                style = MaterialTheme.typography.labelSmall
            )

            claim.projectId
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        "Project: $it",
                        style =
                            MaterialTheme.typography.labelSmall
                    )
                }

            SourceSection(
                title = "Supports",
                sources = claim.supportSources
            )
            SourceSection(
                title = "Contradicts",
                sources = claim.contradictionSources
            )
            SourceSection(
                title = "Mentions",
                sources = claim.mentionSources
            )
            SemanticLinkSection(
                links = claim.semanticLinks
            )
        }
    }
}

@Composable
private fun SemanticLinkSection(
    links: List<EvidenceInspectorSemanticLink>
) {
    if (links.isEmpty()) return

    Text(
        "Grounded model proposals · ${links.size}",
        style = MaterialTheme.typography.labelSmall
    )
    Text(
        "Quote grounded in verified source excerpt; semantic interpretation remains advisory and does not promote verification.",
        style = MaterialTheme.typography.bodySmall
    )

    links
        .take(MAX_VISIBLE_EVIDENCE_SOURCES)
        .forEach { link ->
            Text(
                buildString {
                    append("↳ ")
                    append(link.relation)
                    append(" · ")
                    append(link.status)
                    append(" · model=")
                    append(link.extractorModelId)
                    append(" · sourceEvidence=")
                    append(link.sourceEvidenceCount)
                    append("\nquote=“")
                    append(link.quotedFragment)
                    append("”")
                    link.source?.let { source ->
                        append("\nsource=")
                        append(source.uri)
                    }
                },
                style =
                    MaterialTheme.typography.labelSmall
            )
        }

    if (
        links.size >
        MAX_VISIBLE_EVIDENCE_SOURCES
    ) {
        Text(
            "↳ +${links.size - MAX_VISIBLE_EVIDENCE_SOURCES} more grounded proposals",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun SourceSection(
    title: String,
    sources: List<EvidenceInspectorSource>
) {
    if (sources.isEmpty()) return

    Text(
        "$title · ${sources.size}",
        style = MaterialTheme.typography.labelSmall
    )

    sources
        .take(MAX_VISIBLE_EVIDENCE_SOURCES)
        .forEach { source ->
            Text(
                buildString {
                    append("↳ ")
                    append(source.kind)
                    append(" · ")
                    append(source.retrievalMethod)
                    append(" · evidence=")
                    append(source.evidenceCount)
                    append("\n")
                    append(source.uri)
                    append("\nlast=")
                    append(
                        DateFormat.getDateTimeInstance(
                            DateFormat.SHORT,
                            DateFormat.SHORT
                        ).format(
                            Date(
                                source.lastObservedAt
                            )
                        )
                    )
                },
                style =
                    MaterialTheme.typography.labelSmall
            )
        }

    if (
        sources.size >
        MAX_VISIBLE_EVIDENCE_SOURCES
    ) {
        Text(
            "↳ +${sources.size - MAX_VISIBLE_EVIDENCE_SOURCES} more",
            style = MaterialTheme.typography.labelSmall
        )
    }
}
