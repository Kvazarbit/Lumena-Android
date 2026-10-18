#!/usr/bin/env python3
"""Classify real assertion failures on unchanged ci2296; never call them fixes.

The opt-in JUnit tests assert desired behavior. Only named assertion failures
with their exact marker count as reproduction. Compilation, JNI, skipped tests,
unexpected assertions, and absent XML fail this diagnostic workflow.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BASELINE = "437592cac33ec76d018afe05aec7be1ca4686547"
CORE = "com.lumena.android.diagnostics.PhoneReport2296DiagnosticTest"
UI = "com.lumena.android.diagnostics.EmbeddingLifecycleDiagnosticTest"
CANDIDATES = {
    (CORE, "unquotedAuditMustNotRequireWrites"): "AUDIT_MENTION_BECAME_REQUIRED_TOOL",
    (CORE, "quotedAuditMustNotBecomeCodeWork"): "QUOTED_AUDIT_BECAME_CODE_WORK",
    (CORE, "pastActionsMustNotBecomeCurrentCodeWork"): "PAST_ACTION_BECAME_CODE_WORK",
    (CORE, "auditContractMustNotDemandMutation"): "AUDIT_CONTRACT_DEMANDS_MUTATION",
    (UI, "closingSettingsMustRetainSelectedLiteRtBackend"): "BACKEND_RESET_ON_SETTINGS_CLOSE",
}
CONTROLS = {
    (CORE, "quotedMentionsDoNotCreateRequiredTools"),
    (CORE, "positiveCommandStillRequiresItsTool"),
    (CORE, "negativeCommandStillBlocksWritesAndExecution"),
    (CORE, "identicalSearchWithIdenticalReceiptsIsDetected"),
    (CORE, "distinctUsefulQueriesAreNotBlockedBySameRepositoryDigest"),
    (CORE, "variedQueriesWithNoNewResultsAreNotMistakenForExactDuplicateProof"),
    (UI, "retainedPanelRestoresBackendThroughSavedState"),
    (UI, "selectedFileAndOptInSurviveNewContextAndProviderClose"),
    (UI, "missingFileIsReportedWithoutDeletingSelectionMetadata"),
}


def classify(reports: Path) -> list[dict]:
    found = {}
    for suite in (CORE, UI):
        path = reports / f"TEST-{suite}.xml"
        if not path.is_file():
            raise RuntimeError(f"Missing JUnit report: {suite}; build failure is not reproduction")
        xml = ET.parse(path).getroot()
        for case in xml.iter("testcase"):
            key = (suite, case.get("name"))
            if key in found or key not in CANDIDATES and key not in CONTROLS:
                raise RuntimeError(f"Unexpected or duplicate diagnostic case: {key}")
            if case.find("skipped") is not None or case.find("error") is not None:
                raise RuntimeError(f"Skipped/error case does not establish behavior: {key}")
            failure = case.find("failure")
            record = {"suite": suite, "case": key[1]}
            if failure is None:
                record["verdict"] = "NOT_REPRODUCED" if key in CANDIDATES else "CONTROL_PASS"
            else:
                marker = CANDIDATES.get(key)
                failure_type = failure.get("type", "")
                detail = failure.get("message", "") + "\n" + (failure.text or "")
                if not marker or marker not in detail or failure_type not in (
                    "java.lang.AssertionError", "org.junit.ComparisonFailure"
                ):
                    raise RuntimeError(f"Infrastructure/unexpected assertion, not reproduction: {key}: {detail[:600]}")
                record.update(verdict="REPRODUCED", marker=marker)
            found[key] = record
    if set(found) != set(CANDIDATES) | CONTROLS:
        raise RuntimeError(f"Missing cases: {(set(CANDIDATES) | CONTROLS) - set(found)}")
    return [found[key] for key in sorted(found)]


def main() -> None:
    subprocess.run(["git", "diff", "--exit-code", BASELINE, "--", "app/src/main"],
        cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tree = subprocess.check_output(["git", "rev-parse", "HEAD:app/src/main"], cwd=ROOT, text=True).strip()
    results = ROOT / "app/build/test-results/testDebugUnitTest"
    checks = classify(results)
    out = ROOT / "app/build/phone-report-diagnostics"
    out.mkdir(parents=True, exist_ok=True)
    for suite in (CORE, UI):
        shutil.copy2(results / f"TEST-{suite}.xml", out)
    reproduced = sum(c["verdict"] == "REPRODUCED" for c in checks)
    report = {
        "schema": "lumena.phone-report-diagnostic.v1",
        "status": "DIAGNOSIS_COMPLETE_NOT_A_FIX",
        "production_baseline": BASELINE,
        "test_commit": sha,
        "production_source_tree": tree,
        "production_sources_unchanged": True,
        "reproduced": reproduced,
        "checks": checks,
        "limits": [
            "Robolectric Compose and JVM; no physical Android device",
            "Model selection fixture only; no real SAF import or native embedding inference",
            "Does not establish Android/OEM process-kill, RAM, thermal or battery cause",
            "Distinct search requests in owner report are unknown; no identical-args claim",
            "Correctly quoted mentions may avoid required tools while still affecting intent routing",
        ],
    }
    (out / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# ci2296 diagnostic — production code unchanged", "",
        f"Confirmed desired-behavior failures: **{reproduced}**. This is diagnosis, not a fix.", "",
        "| Case | Result |", "| --- | --- |"]
    lines += [f"| `{c['case']}` | {c['verdict']} |" for c in checks]
    lines += ["", "No phone inference or background process survival is certified."]
    summary = "\n".join(lines) + "\n"
    (out / "summary.md").write_text(summary, encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write(summary)
    for check in checks:
        if check["verdict"] == "REPRODUCED":
            print(f"::warning title=Existing ci2296 defect reproduced::{check['case']}: {check['marker']}")
    print(f"DIAGNOSTIC_CASES={len(checks)} REPRODUCED={reproduced}; NOT A FIX")


if __name__ == "__main__":
    main()
