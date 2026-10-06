#!/usr/bin/env python3
"""Run bounded source mutations against the real Kotlin recovery tests.

Each mutant is installed one at a time, tested, and restored byte-for-byte.
Only an actual JUnit assertion failure counts as a killed mutant. A compile,
infrastructure, or timeout failure fails the probe instead of earning credit.
"""

import argparse
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
CORE = Path("app/src/main/java/com/lumena/android/agent/core")
TEST_RESULTS = ROOT / "app/build/test-results/testDebugUnitTest"
MUTANTS = (
    ("unknown-effect-or", CORE / "ConstitutionKernel.kt",
     "event.outcomeUnknown || event.failureClass == FailureClass.UNKNOWN_EFFECT",
     "event.outcomeUnknown && event.failureClass == FailureClass.UNKNOWN_EFFECT"),
    ("policy-denial-or", CORE / "ConstitutionKernel.kt",
     "event.failureClass == FailureClass.POLICY_DENIED || event.source == FailureSource.POLICY",
     "event.failureClass == FailureClass.POLICY_DENIED && event.source == FailureSource.POLICY"),
    ("semantic-budget-boundary", CORE / "ConstitutionKernel.kt",
     "state.semanticRecoverySpent >= state.maxSemanticRecoveries",
     "state.semanticRecoverySpent > state.maxSemanticRecoveries"),
    ("reflex-all-candidates", CORE / "ReflexKernel.kt",
     "scores.all { it.option in candidates.allowed }",
     "scores.any { it.option in candidates.allowed }"),
    ("workspace-undeclared-effect", CORE / "ContextKernel.kt",
     "WorkspaceMutationEffect.UNDECLARED,\n                    null -> true",
     "WorkspaceMutationEffect.UNDECLARED,\n                    null -> false"),
    ("workspace-declared-target-effect", CORE / "ContextKernel.kt",
     "event.target == target",
     "event.target != target"),
    ("capability-denial-check", CORE / "EffectiveTaskPolicy.kt",
     ".firstOrNull { it in policy.deniedCapabilities }",
     ".firstOrNull { false }"),
    ("python-run-write-capability", CORE / "ToolRegistry.kt",
     "ToolCapability.EXECUTE_CODE,\n            ToolCapability.WRITE_WORKSPACE,\n            ToolCapability.NETWORK",
     "ToolCapability.EXECUTE_CODE,\n            ToolCapability.NETWORK"),
)
TEST_NAMES = (
    "*RecoveryAdversarialSearchTest",
    "*ConstitutionKernelTest",
    "*ReflexKernelTest",
    "*ContextKernelTest",
    "*ToolCapabilityPolicyTest",
)


def run_one(name: str, relative: Path, old: str, new: str, dry_run: bool) -> bool:
    path = ROOT / relative
    original = path.read_bytes()
    needle = old.encode("utf-8")
    if original.count(needle) != 1:
        raise RuntimeError(f"{name}: expected exactly one source anchor, got {original.count(needle)}")
    if dry_run:
        print(f"READY {name}: {relative}")
        return True

    command = ["gradle", "--max-workers=2", "--no-build-cache", ":app:testDebugUnitTest"]
    for test_name in TEST_NAMES:
        command.extend(["--tests", test_name])
    for xml_file in TEST_RESULTS.glob("TEST-*.xml"):
        xml_file.unlink()

    try:
        path.write_bytes(original.replace(needle, new.encode("utf-8"), 1))
        try:
            result = subprocess.run(command, cwd=ROOT, capture_output=True,
                                    text=True, timeout=480, check=False)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(f"{name}: Gradle timeout; mutant outcome unknown") from error

        failing_tests = []
        executed = 0
        for xml_file in TEST_RESULTS.glob("TEST-*.xml"):
            suite = ET.parse(xml_file).getroot()
            executed += int(suite.attrib.get("tests", "0"))
            for case in suite.iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failing_tests.append(f"{case.attrib.get('classname')}.{case.attrib.get('name')}")
        if result.returncode == 0:
            raise RuntimeError(f"{name}: SURVIVED ({executed} tests passed)")
        if not failing_tests:
            tail = "\n".join((result.stdout + "\n" + result.stderr).splitlines()[-20:])
            raise RuntimeError(f"{name}: invalid mutant or infrastructure failure, no JUnit failure:\n{tail}")
        print(f"KILLED {name}: {executed} tests; counterexample={failing_tests[0]}")
        return True
    finally:
        path.write_bytes(original)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="Check exact source anchors without Gradle")
    args = parser.parse_args()
    try:
        for mutant in MUTANTS:
            run_one(*mutant, dry_run=args.dry_run)
    except RuntimeError as error:
        print(f"FAIL {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
