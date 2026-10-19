package com.lumena.android.collaboration

import com.lumena.android.agent.core.WorkspaceProject

/** Narrow check of positive test claims; controller/GoalContract still decides completion. */
object CompletionClaimReview {
    fun problem(text: String, entries: List<CollaborationEntry>, project: WorkspaceProject,
                appSource: String, current: SourceObservation): String? {
        val findings = ClaimCheck.check(text, entries, project, appSource, current)
            .filter { it.claim.kind == ClaimCheck.Kind.PASS }
        if (findings.isEmpty()) return null
        val bad = findings.filter { it.verdict != ClaimCheck.Verdict.SUPPORTED }
        if (bad.isNotEmpty()) return "DONE відхилено: заява про тести не підтверджена.\n" + ClaimCheck.report(bad)
        if (current.digest == null) return "DONE відхилено: поточний стан файлів для заяви про тести не вдалося перевірити."
        if (findings.any { finding -> entries.firstOrNull { it.id == finding.receiptId }?.let {
                CollaborationProtocol.freshness(it, project, appSource, current) == "SAME_COVERED_SOURCE" && it.scope.appSource == appSource
            } != true }) return "DONE відхилено: квитанція не підтверджує поточну версію файлів і застосунку."
        return null
    }
}
