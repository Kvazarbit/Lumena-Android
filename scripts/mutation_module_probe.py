#!/usr/bin/env python3
"""Mutation gate for Lumena MCP, modules and listing safeguards.

Each mutant is introduced alone, the targeted real tests are run, and the
original source bytes are restored even when a check fails. Only actual
JUnit/Python assertion failures count as killed mutants; compiling, timing
out or losing SDK packages cannot earn credit.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CORE = Path("app/src/main/java/com/lumena/android/agent/core")
MOD = Path("app/src/main/java/com/lumena/android/modules")
LISTING = Path("app/src/main/java/com/lumena/android/listing")
RESULTS = ROOT / "app/build/test-results/testDebugUnitTest"

# (name, path, old, new, junit class or "python")
MUTANTS = (
    ("mcp-typo", MOD / "McpSearchCue.kt",
     "[mм][cс][pрп]", "mcp", "*McpSearchCueTest"),
    ("mcp-negation", MOD / "McpSearchCue.kt",
     "negative.containsMatchIn(prefix)",
     "false", "*McpSearchCueTest"),
    ("mcp-evidence", MOD / "McpModule.kt",
     'if (McpSearchCue.searchRequested(goal)) listOf("mcp.search") else null',
     'null', "*GoalContractPolicyTest"),
    ("bridge-unknown", CORE / "BridgeCompatibility.kt",
     "val seen = observed ?: return false",
     "val seen = observed ?: return true", "*BridgeCompatibilityTest"),
    ("bridge-stale-cache", CORE / "BridgeCompatibility.kt",
     "fun invalidateObservation() { observed = null }",
     "fun invalidateObservation() { }", "*BridgeCompatibilityTest"),
    ("module-duplicate", CORE / "LumenaModule.kt",
     "if (!localNames.add(name))",
     "if (false)", "*ModuleKernelBoundaryTest"),
    ("pracuj-redirect", LISTING / "PracujWatch.kt",
     ".followRedirects(false)", ".followRedirects(true)", "*PracujClientTest"),
    ("pracuj-bound", LISTING / "PracujWatch.kt",
     'check(out.size().toLong() + n <= limit) { "сторінка завелика" }',
     'check(true) { "сторінка завелика" }', "*PracujClientTest"),
    ("pracuj-city", LISTING / "PracujSource.kt",
     'require(!hasNonLatinLetters(cityRaw)) {',
     'require(true) {', "*PracujSourceTest"),
    ("job-url-dedupe", LISTING / "ListingAttention.kt",
     "if (canonicalUrl != null && retained.any {",
     "if (false && retained.any {", "*ListingAttentionPolicyTest"),
    ("olx-package", LISTING / "ListingAttention.kt",
     'packageName == OLX_PL_PACKAGE\n',
     'packageName == OLX_PL_PACKAGE || packageName.lowercase().contains("olx")\n',
     "*ListingAttentionPolicyTest"),
    ("mcp-readonly", Path("termux/bridge.py"),
     'if not isinstance(annotations, dict) or annotations.get("readOnlyHint") is not True:',
     'if not isinstance(annotations, dict) or False:', "python"),
    ("mcp-required", Path("termux/bridge.py"),
     "        if missing:\n            return None\n    return args",
     "        if False:\n            return None\n    return args", "python"),
)


def kill_one(name, path_rel, old_str, new_str, selector, dry_run=False):
    path = ROOT / path_rel
    saved = path.read_bytes()
    old = old_str.encode("utf-8")
    matches = saved.count(old)
    if matches != 1:
        raise RuntimeError(f"{name}: expected one exact source anchor, got {matches}")
    if dry_run:
        print(f"READY {name}: {path_rel}")
        return
    if selector == "python":
        command = [sys.executable, "-m", "unittest", "discover",
                   "-s", "termux", "-p", "test_mcp_search.py", "-v"]
    else:
        command = ["gradle", "--max-workers=2", "--no-build-cache",
                   ":app:testDebugUnitTest", "--tests", selector]
        for xml in RESULTS.glob("TEST-*.xml"):
            xml.unlink()

    try:
        path.write_bytes(saved.replace(old, new_str.encode("utf-8"), 1))
        try:
            run = subprocess.run(command, cwd=ROOT, capture_output=True,
                                 text=True, check=False, timeout=420)
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(f"{name}: timeout is not mutation proof") from exc
        if run.returncode == 0:
            raise RuntimeError(f"{name}: SURVIVED")
        if selector == "python":
            output = run.stdout + "\n" + run.stderr
            if "FAIL: test_" not in output:
                raise RuntimeError(f"{name}: import/infra error, not a test assertion: {output[-1000:]}")
            print(f"KILLED {name}: Python assertion failure")
            return

        count, failing = 0, []
        for xml in RESULTS.glob("TEST-*.xml"):
            suite = ET.parse(xml).getroot()
            count += int(suite.attrib.get("tests", "0"))
            for case in suite.iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failing.append(case.attrib.get("name", "(unnamed)"))
        if not failing:
            tail = (run.stdout + "\n" + run.stderr)[-1400:]
            raise RuntimeError(f"{name}: compile/infrastructure failure, not killed:\n{tail}")
        print(f"KILLED {name}: {count} JUnit tests; counterexample={failing[0]}")
    finally:
        path.write_bytes(saved)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--only", action="append")
    args = parser.parse_args()
    cases = [m for m in MUTANTS if not args.only or m[0] in args.only]
    if args.only and len(cases) != len(set(args.only)):
        parser.error("unknown --only mutant")
    try:
        for mutant in cases:
            kill_one(*mutant, dry_run=args.dry_run)
    except (OSError, RuntimeError) as exc:
        print(f"FAIL {exc}", file=sys.stderr)
        return 1
    print(f"MCP_MODULE_MUTATION_GATE={len(cases)} "
          + ("READY" if args.dry_run else "KILLED"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
