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
    selected = sorted({m[4] for m in MUTANTS} | {m[6] for m in MUTANTS})
    code, baseline = run(selected, "baseline")
    assert code == 0 and set(baseline) == set(selected) and all(not marks for marks in baseline.values()), "INVALID_BASELINE"
    results = []
    for name, file, old, new, target, reason, control in MUTANTS:
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
    (OUT / "report.json").write_text(json.dumps({"schema": "lumena.collaboration-probe.v1", "source_sha": os.environ.get("GITHUB_SHA", "UNKNOWN"), "baseline": "PASS", "mutants": results}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
