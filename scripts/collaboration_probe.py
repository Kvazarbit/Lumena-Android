"""Exact-case semantic mutation checks; source restored even after a failed oracle."""
from pathlib import Path
import json
import os
import subprocess
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/java/com/lumena/android/collaboration"
REPORTS = ROOT / "app/build/test-results/testDebugUnitTest"
OUT = ROOT / "app/build/collaboration-probe"
MUTANTS = [
    ("generic-message-joins-tests", "RepairCases.kt",
     '(c.testId.isEmpty() || episode.testId.isEmpty()) -> 2', 'true -> 2',
     "com.lumena.android.collaboration.RepairCasePolicyTest.aGenericMessageInAnotherTestFileNoLongerJoinsUnrelatedCases", "UNRELATED_TEST_JOINED",
     "com.lumena.android.collaboration.RepairCasePolicyTest.realObservedRepairRetainsProofAndCausalChange"),
    ("same-file-unrelated-message", "RepairCases.kt",
     'meaningfulOverlap(c.failure, episode.failure) >= 0.35 -> 1', 'true -> 1',
     "com.lumena.android.collaboration.RepairCasePolicyTest.siblingFailedTestNeedsMeaningfulFailureOverlap", "SIBLING_DIAGNOSTIC_MISMATCH",
     "com.lumena.android.collaboration.RepairCasePolicyTest.realObservedRepairRetainsProofAndCausalChange"),
    ("reads-consume-repair-steps", "RepairCases.kt",
     'if (e.tool in effectful) o.steps++ else if', 'if (true) o.steps++ else if',
     "com.lumena.android.collaboration.RepairCasePolicyTest.readsBeforeAPatchDoNotConsumeEffectfulSteps", "CAREFUL_READING_PENALIZED",
     "com.lumena.android.collaboration.RepairCasePolicyTest.realObservedRepairRetainsProofAndCausalChange"),
    ("invalid-episode-redraw", "RepairCases.kt",
     'if (k in blocked) {', 'if (false) {',
     "com.lumena.android.collaboration.RepairCasePolicyTest.invalidIntervalCannotRedrawAnEpisodeUntilARealPass", "INVALID_INTERVAL_REDRAWN",
     "com.lumena.android.collaboration.RepairCasePolicyTest.realObservedRepairRetainsProofAndCausalChange"),
    ("changed-verifier-learned", "RepairCases.kt",
     '!it.complete || !it.boundaryUnchanged || e.sourceDigest == null', '!it.complete || e.sourceDigest == null',
     "com.lumena.android.collaboration.RepairCasePolicyTest.changedVerifierCannotBecomeALearnedRepair", "CHANGED_VERIFIER_LEARNED",
     "com.lumena.android.collaboration.RepairCasePolicyTest.realObservedRepairRetainsProofAndCausalChange"),
    ("imported-unknown-barrier", "ImportedContextArchive.kt",
     'if (!CollaborationProtocol.writer(request.tool)) return@synchronized false', 'return@synchronized false',
     "com.lumena.android.diagnostics.ExperienceLoopStorageTest.importedUnknownBlocksWritersWithCaptureOffAndOwnerAckNeverChangesHistory", "IMPORTED_UNKNOWN_EXECUTED",
     "com.lumena.android.diagnostics.CollaborationLedgerTest.disabledLayerKeepsOriginalSingleModelTransportAndCreatesNoDatabase"),
    ("private-project-learning", "../ollama/WorkflowRunner.kt",
     "if (experienceIsolation(task)) return", "if (false) return",
     "com.lumena.android.ollama.WorkflowRunnerCollaborationAdapterTest.isolatedProjectObservationNeverPublishesIntoGlobalLearning", "PRIVATE_LEARNING_EGRESS",
     "com.lumena.android.ollama.WorkflowRunnerCollaborationAdapterTest.ordinaryUnscopedChatStillRecordsKnownExperience"),
    ("nested-private-inference", "ProjectToolPrivacy.kt",
     "ownerAllowsRemote || (", "true || (",
     "com.lumena.android.collaboration.ProjectContextClientTest.delegatedInferenceCannotUseACloudAliasOrRemoteBridgeWithoutConsent", "NESTED_CLOUD_EGRESS",
     "com.lumena.android.collaboration.CollaborationReviewTest.embeddedWorksOfflineWithoutOllamaAttestation"),
    ("private-derived-history", "ProjectContextClient.kt",
     "if (privateProjects().all(ownerAllowsRemote)) null else", "if (true) null else",
     "com.lumena.android.collaboration.ProjectContextClientTest.derivedPrivateHistoryCannotLeaveOnRouteChangeOrDeselection", "PRIVATE_HISTORY_EGRESS",
     "com.lumena.android.collaboration.CollaborationReviewTest.embeddedWorksOfflineWithoutOllamaAttestation"),
    ("stale-owner-goal", "CollaborationProtocol.kt",
     'e.scope.goalRevision != goalRevision(project)', 'false',
     "com.lumena.android.collaboration.CollaborationStateCardTest.changedOwnerGoalInvalidatesReceiptEvenWhenCoveredBytesMatch", "STALE_GOAL",
     "com.lumena.android.collaboration.CollaborationProtocolTest.sameProjectNameCannotBindOtherRootAndRevisionUsesUnambiguousFraming"),
    ("archived-delivery-replay", "CollaborationLedger.kt",
     'require(key !in old.deliveries)', 'require(true)',
     "com.lumena.android.diagnostics.CollaborationLedgerTest.archivedReceiptsKeepChainAndDeliveryIdentityAcrossColdOpen", "ARCHIVED_REPLAY_EXECUTED",
     "com.lumena.android.diagnostics.CollaborationLedgerTest.disabledLayerKeepsOriginalSingleModelTransportAndCreatesNoDatabase"),
    ("companion-content-is-not-delivery", "../companion/CompanionTaskGrantStore.kt",
     "return deliveryId", "return fingerprint",
     "com.lumena.android.diagnostics.CollaborationLedgerTest.legitimateCompanionRetestHasNewDurableDeliveryButOldDeliveryStillCannotReplay", "NEW_EXECUTION_CONFLATED_WITH_REDELIVERY",
     "com.lumena.android.diagnostics.CollaborationLedgerTest.disabledLayerKeepsOriginalSingleModelTransportAndCreatesNoDatabase"),
    ("duplicate-delivery", "CollaborationLedger.kt",
     'error("Delivery already recorded: ${it.id}; consult historical receipt, no re-execution")', "Unit",
     "com.lumena.android.diagnostics.CollaborationLedgerTest.redeliveryDoesNotExecuteOrInventANewReceipt", "REPLAY_EXECUTED",
     "com.lumena.android.diagnostics.CollaborationLedgerTest.disabledLayerKeepsOriginalSingleModelTransportAndCreatesNoDatabase"),
    ("unknown-writer", "CollaborationLedger.kt",
     "require(CollaborationProtocol.unresolved(all, scope.bridgeBinding).isEmpty())", "require(true)",
     "com.lumena.android.diagnostics.CollaborationLedgerTest.unknownWriterBlocksOtherProjectAndConnectionChangeButAllowsReading", "UNKNOWN_WRITER_EXECUTED",
     "com.lumena.android.diagnostics.CollaborationLedgerTest.disabledLayerKeepsOriginalSingleModelTransportAndCreatesNoDatabase"),
    ("private-cloud", "CollaborationReview.kt",
     "require(localRoute(backend, url, model, ownerAttestsLocal))", "require(true)",
     "com.lumena.android.collaboration.CollaborationReviewTest.cloudPrivacyCanaryNeverReachesClient", "CLOUD_EGRESS",
     "com.lumena.android.collaboration.CollaborationReviewTest.embeddedWorksOfflineWithoutOllamaAttestation"),
    ("private-card-cloud", "CollaborationStateCard.kt",
     "if (!CollaborationReview.localRoute(backend, url, model, ownerAttestsLocal)) return null", "if (false) return null",
     "com.lumena.android.collaboration.CollaborationStateCardTest.cloudRouteNeverOpensTheJournalOrReceivesTheCard", "CLOUD_EGRESS",
     "com.lumena.android.collaboration.CollaborationStateCardTest.localRouteGetsCardOnlyForTheSameProject"),
    ("stale-build", "CollaborationProtocol.kt",
     '&& e.scope.appSource != appSource -> "STALE_BUILD"', '&& false -> "STALE_BUILD"',
     "com.lumena.android.collaboration.CollaborationProtocolTest.historicalReceiptSurvivesGoalAndBuildChangesWithoutProvingCurrentCode", "STALE_PROOF_ACCEPTED",
     "com.lumena.android.collaboration.CollaborationProtocolTest.sameProjectNameCannotBindOtherRootAndRevisionUsesUnambiguousFraming"),
]


def cases():
    found = {}
    for path in REPORTS.glob("TEST-*.xml"):
        root = E.parse(path).getroot()
        for case in root.iter("testcase"):
            key = case.get("classname") + "." + case.get("name")
            marks = [x for x in case if x.tag in ("failure", "error", "skipped")]
            assert key not in found, key
            found[key] = [(x.tag, x.get("message", "")) for x in marks]
    return found


def run(selected, label):
    for path in REPORTS.glob("TEST-*.xml"):
        path.unlink()
    cmd = ["gradle", "--max-workers=2", "-PphoneDiagnostics=true", ":app:testDebugUnitTest", "--rerun-tasks"]
    for identity in selected:
        cmd += ["--tests", identity]
    with (OUT / (label + ".log")).open("w", encoding="utf-8") as log:
        result = subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=900)
    return result.returncode, cases()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    requested = {x.strip() for x in os.environ.get("LUMENA_MUTANT_ONLY", "").split(",") if x.strip()}
    if requested:
        assert requested <= {m[0] for m in MUTANTS}, "UNKNOWN_MUTANT_FILTER"
    mutants = [m for m in MUTANTS if not requested or m[0] in requested]
    assert mutants, "EMPTY_MUTANT_SELECTION"
    selected = sorted({m[4] for m in mutants} | {m[6] for m in mutants})
    code, baseline = run(selected, "baseline")
    assert code == 0 and set(baseline) == set(selected) and all(not marks for marks in baseline.values()), "INVALID_BASELINE"
    results = []
    for name, file, old, new, target, reason, control in mutants:
        path = BASE / file
        original = path.read_bytes()
        text = original.decode("utf-8")
        assert text.count(old) == 1, (name, "MUTATION_NOT_UNIQUE")
        try:
            path.write_text(text.replace(old, new), encoding="utf-8", newline="")
            code, actual = run([target, control], name)
            assert code != 0 and set(actual) == {target, control}, (name, "MISSING_CASE_OR_COMPILE_ERROR")
            assert actual[control] == [], (name, "UNRELATED_CONTROL_FAILED")
            assert len(actual[target]) == 1 and actual[target][0][0] == "failure" and reason in actual[target][0][1], (name, actual[target])
            results.append({"mutation": name, "verdict": "KILLED_EXPECTED", "case": target, "reason": reason, "control": control})
            print("KILLED_EXPECTED", name, flush=True)
        finally:
            path.write_bytes(original)
    (OUT / "report.json").write_text(json.dumps({"schema": "lumena.collaboration-probe.v1", "source_sha": os.environ.get("GITHUB_SHA", "UNKNOWN"), "baseline": "PASS", "requested_mutants": sorted(requested), "mutants": results}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
