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
import com.lumena.android.settings.EvidenceInspectorApplication
import com.lumena.android.settings.EvidenceInspectorCandidate
import com.lumena.android.settings.EvidenceInspectorClaim
import com.lumena.android.settings.EvidenceInspectorSnapshot
import com.lumena.android.settings.EvidenceInspectorSource
import java.text.DateFormat
import java.util.Date

private const val MAX_VISIBLE_EVIDENCE_CLAIMS = 10
private const val MAX_VISIBLE_EVIDENCE_CANDIDATES = 8
private const val MAX_VISIBLE_EVIDENCE_APPLICATIONS = 8
private const val MAX_VISIBLE_EVIDENCE_SOURCES = 5

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
            "Read-only view. TOOL_RESULT-backed evidence, semantic candidates and project-application proof remain separate. Nothing here grants permission or executes a tool.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Claims ${snapshot.totalClaims} · Sources ${snapshot.totalSources} · Discovered ${snapshot.discovered} · Retrieved ${snapshot.retrieved} · Corroborated ${snapshot.corroborated} · Contested ${snapshot.contested} · Stale ${snapshot.staleEffective}",
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            "Candidates ${snapshot.totalCandidates} · pending ${snapshot.pendingCandidates} · promoted ${snapshot.promotedCandidates} · rejected ${snapshot.rejectedCandidates}",
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            "Project bindings ${snapshot.totalApplications} · pending ${snapshot.pendingApplications} · applied ${snapshot.appliedApplications} · verified ${snapshot.verifiedApplications} · rejected ${snapshot.rejectedApplications}",
            style = MaterialTheme.typography.labelSmall
        )

        Text(
            "Verified claims",
            style = MaterialTheme.typography.titleSmall
        )

        if (snapshot.claims.isEmpty()) {
            Text(
                "No verified/source-level claims yet.",
                style = MaterialTheme.typography.bodySmall
            )
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
                "Showing $MAX_VISIBLE_EVIDENCE_CLAIMS of ${snapshot.claims.size} claims; full graph remains local.",
                style = MaterialTheme.typography.labelSmall
            )
        }

        Text(
            "Semantic candidates",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            "A PENDING candidate is a grounded proposal, not verified evidence.",
            style = MaterialTheme.typography.bodySmall
        )
        if (snapshot.candidates.isEmpty()) {
            Text(
                "No semantic candidates.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        snapshot.candidates
            .take(MAX_VISIBLE_EVIDENCE_CANDIDATES)
            .forEach { candidate ->
                CandidateCard(candidate)
            }
        if (
            snapshot.candidates.size >
            MAX_VISIBLE_EVIDENCE_CANDIDATES
        ) {
            Text(
                "Showing $MAX_VISIBLE_EVIDENCE_CANDIDATES of ${snapshot.candidates.size} candidates.",
                style = MaterialTheme.typography.labelSmall
            )
        }

        Text(
            "Project applications",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            "PENDING is only a binding. APPLIED needs mutation proof; VERIFIED additionally needs local test proof.",
            style = MaterialTheme.typography.bodySmall
        )
        if (snapshot.applications.isEmpty()) {
            Text(
                "No evidence-to-project bindings.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        snapshot.applications
            .take(MAX_VISIBLE_EVIDENCE_APPLICATIONS)
            .forEach { binding ->
                ApplicationCard(binding)
            }
        if (
            snapshot.applications.size >
            MAX_VISIBLE_EVIDENCE_APPLICATIONS
        ) {
            Text(
                "Showing $MAX_VISIBLE_EVIDENCE_APPLICATIONS of ${snapshot.applications.size} bindings.",
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
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: EvidenceInspectorCandidate
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "${candidate.status} · ${candidate.claimKey}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                candidate.statement,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                candidate.explanation,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Provenance ${candidate.provenance} · source grounding ${candidate.lexicalCoveragePercent}% · relevance ${candidate.projectRelevancePercent}% · resolution evidence ${candidate.resolutionEvidenceCount}",
                style = MaterialTheme.typography.labelSmall
            )
            candidate.projectId
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        "Project: $it",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            if (candidate.sourceUris.isNotEmpty()) {
                Text(
                    "Bound sources",
                    style = MaterialTheme.typography.labelSmall
                )
                candidate.sourceUris
                    .take(MAX_VISIBLE_EVIDENCE_SOURCES)
                    .forEach { uri ->
                        Text(
                            "↳ $uri",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
            }
            Text(
                "Proposed " + formatEvidenceTime(candidate.proposedAt),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ApplicationCard(
    binding: EvidenceInspectorApplication
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "${binding.status} · ${binding.claimKey}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                binding.explanation,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Project ${binding.projectId} · target ${binding.target}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Artifact proofs ${binding.artifactEvidenceCount} · test proofs ${binding.testEvidenceCount}",
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                "Created " + formatEvidenceTime(binding.createdAt) +
                    " · updated " + formatEvidenceTime(binding.updatedAt),
                style = MaterialTheme.typography.labelSmall
            )
        }
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

private fun formatEvidenceTime(
    timestamp: Long
): String =
    DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT
    ).format(Date(timestamp))
