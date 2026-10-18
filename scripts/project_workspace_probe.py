#!/usr/bin/env python3
"""Falsify project-context guarantees with exact semantic regressions.

Python subjects are disposable copies. Kotlin subjects are restored byte for
byte in finally. A compile error, skipped test, or unrelated failure never earns
credit. The reference Context OS is never imported, edited or executed here.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CORE = Path('app/src/main/java/com/lumena/android/agent/core')
RESULTS = ROOT / 'app/build/test-results/testDebugUnitTest'
PYTHON_MUTANTS = (
    ('secret-index', 'name not in PROJECT_SECRET_NAMES', 'True',
     'test_recognised_secret_files_and_dependency_trees_never_enter_index', 'SECRET_INDEXED'),
    ('stale-content', "digest.update(source_hash.encode('ascii'))", 'pass',
     'test_same_mtime_content_change_changes_snapshot_digest', 'STALE_CONTEXT_REUSED'),
    ('hidden-truncation', "'truncated': truncated, 'coverage':", "'truncated': False, 'coverage':",
     'test_truncation_is_explicit_and_never_absence_proof', 'TRUNCATION_HIDDEN'),
    ('empty-tests', "if result.get('ok') and not verified:", "if False:",
     'test_project_tests_require_nonempty_real_junit_receipt', 'EMPTY_TEST_SUCCESS_ACCEPTED'),
)
KOTLIN_MUTANTS = (
    ('wrong-target', CORE / 'ProjectVerificationPolicy.kt',
     'return actual == clean || actual.endsWith("/$clean")', 'return true',
     'com.lumena.android.settings.ProjectWorkspacePolicyTest',
     'unrelatedReadAndSyntaxCheckCannotSatisfyRequestedTargetOrExecution'),
    ('empty-receipt', CORE / 'ProjectVerificationPolicy.kt',
     '((decoded["tests_count"] as? Number)?.toInt() ?: 0) > 0',
     '((decoded["tests_count"] as? Number)?.toInt() ?: 0) >= 0',
     'com.lumena.android.settings.ProjectWorkspacePolicyTest',
     'projectVerificationRequiresFreshFullTestsWithNonemptyReceipt'),
    ('lost-unknown', CORE / 'ProjectWorkspace.kt',
     'priorOrigin != null && priorOrigin != task.id', 'priorOrigin != null && priorOrigin == task.id',
     'com.lumena.android.settings.ProjectWorkspacePolicyTest',
     'knownResultClosesOnlyItsOwnInflightMarkerAndUnknownSurvivesNextEpoch'),
    ('lost-mandatory-context', CORE / 'RequiredProjectContext.kt',
     'return before.take(head) + required + after.takeLast(tail)',
     'return before.take(head) + after.takeLast(tail)',
     'com.lumena.android.agent.core.ProjectContextBudgetTest',
     'ollamaCompactionRetainsEntireMandatoryBlockAndLatestTurn'),
    ('ignored-argument-url-grant', CORE / 'TrustGate.kt',
     'if (child in fetchedUrlTools) childArgs["url"]?.let(::add)',
     'if (true) childArgs["url"]?.let(::add)',
     'com.lumena.android.agent.core.TrustGateTest',
     'ignoredUrlArgumentsCannotGrantEgressForLocalReadsOrSearch'),
)


def mutate(raw, old, new, name):
    before = old.encode()
    if raw.count(before) != 1:
        raise RuntimeError(f'{name}: exact source anchor changed')
    return raw.replace(before, new.encode(), 1)


def run_python(directory):
    return subprocess.run([sys.executable, '-B', '-m', 'unittest', '-v', 'test_project_workspace'],
        cwd=directory, capture_output=True, text=True, timeout=90)


def python_probe():
    raw = (ROOT/'termux/bridge.py').read_bytes()
    with tempfile.TemporaryDirectory(prefix='lumena-context-probe-') as directory:
        directory = Path(directory)
        shutil.copy2(ROOT/'termux/test_project_workspace.py', directory)
        subject = directory/'bridge.py'
        subject.write_bytes(raw)
        baseline = run_python(directory)
        if baseline.returncode:
            raise RuntimeError('Baseline failed: ' + baseline.stderr[-4000:])
        subject.write_bytes(raw + b'\n# Unrelated control: project-context probe.\n')
        control = run_python(directory)
        if control.returncode:
            raise RuntimeError('Unrelated control failed: ' + control.stderr[-4000:])
        records = [{'name': 'baseline', 'passed': True}, {'name': 'unrelated-control', 'passed': True}]
        for name, old, new, case, marker in PYTHON_MUTANTS:
            subject.write_bytes(mutate(raw, old, new, name))
            result = run_python(directory)
            output = result.stdout + result.stderr
            if result.returncode == 0 or f'FAIL: {case}' not in output or marker not in output or 'ERROR:' in output:
                raise RuntimeError(f'{name}: no expected semantic assertion: {output[-4000:]}')
            records.append({'name': name, 'killed_by': case, 'assertion': marker})
            print(f'KILLED {name}: {case} / {marker}')
        return records


def kotlin_probe():
    records = []
    for name, relative, old, new, suite, case in KOTLIN_MUTANTS:
        path = ROOT/relative
        saved = path.read_bytes()
        report = RESULTS/f'TEST-{suite}.xml'
        report.unlink(missing_ok=True)
        try:
            path.write_bytes(mutate(saved, old, new, name))
            result = subprocess.run(['gradle', '--max-workers=2', '--no-build-cache',
                ':app:testDebugUnitTest', '--tests', suite], cwd=ROOT,
                capture_output=True, text=True, timeout=420)
            if result.returncode == 0 or not report.is_file():
                raise RuntimeError(f'{name}: survived or infrastructure error: {(result.stdout+result.stderr)[-2200:]}')
            xml = ET.parse(report).getroot()
            cases = list(xml.iter('testcase'))
            expected = next((c for c in cases if c.get('name') == case), None)
            if expected is None or expected.find('failure') is None or any(c.find('error') is not None for c in cases):
                raise RuntimeError(f'{name}: expected JUnit assertion absent; compile/error is not proof')
            records.append({'name': name, 'killed_by': f'{suite}.{case}'})
            print(f'KILLED {name}: {suite}.{case}')
        finally:
            path.write_bytes(saved)
    return records


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--kotlin', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    for name, old, new, _, _ in PYTHON_MUTANTS:
        mutate((ROOT/'termux/bridge.py').read_bytes(), old, new, name)
    for name, path, old, new, _, _ in KOTLIN_MUTANTS:
        mutate((ROOT/path).read_bytes(), old, new, name)
    if args.dry_run:
        print('READY: 4 Python + 5 Kotlin semantic mutants; baseline and unrelated control')
        return
    records = python_probe()
    if args.kotlin:
        records += kotlin_probe()
    out = ROOT/'app/build/project-workspace-probe/results.json'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({'schema': 'lumena.project.probe.v1', 'checks': records}, indent=2), encoding='utf-8')
    print(f'VERIFIED_PROJECT_CONTEXT_CHECKS={len(records)}')


if __name__ == '__main__':
    main()
