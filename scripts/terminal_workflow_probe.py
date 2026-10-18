"""Prove that the real terminal lesson catches stale imported Python bytecode.

Only disposable source copies are mutated. Compilation/infra errors earn no credit.
"""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(directory):
    return subprocess.run([sys.executable, '-B', '-m', 'unittest', '-v', 'test_terminal_workflow'],
        cwd=directory, capture_output=True, text=True, timeout=90)


def main():
    source = (ROOT / 'termux/bridge.py').read_bytes()
    anchor = b'env["PYTHONPYCACHEPREFIX"] = str(home / "bytecode" / secrets.token_hex(16))'
    if source.count(anchor) != 1:
        raise RuntimeError('Fresh-bytecode source anchor changed')
    with tempfile.TemporaryDirectory(prefix='lumena-terminal-probe-') as path:
        directory = Path(path)
        shutil.copy2(ROOT / 'termux/test_terminal_workflow.py', directory)
        subject = directory / 'bridge.py'
        for label, raw in [('baseline', source), ('unrelated-control', source + b'\n# Unrelated terminal probe control.\n')]:
            subject.write_bytes(raw)
            result = run(directory)
            if result.returncode:
                raise RuntimeError(label + ': ' + result.stderr[-3000:])
        subject.write_bytes(source.replace(anchor, b'env.pop("PYTHONPYCACHEPREFIX", None)', 1))
        result = run(directory)
        if result.returncode == 0 or 'FAIL: test_inspect_edit_verify_and_git_commit_with_real_processes' not in result.stderr or 'AssertionError' not in result.stderr or 'ERROR:' in result.stderr:
            raise RuntimeError('No expected stale-code assertion: ' + result.stderr[-3000:])
    out = ROOT / 'app/build/terminal-workflow-probe/results.json'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({'schema': 'lumena.terminal.probe.v1', 'baseline': True,
        'unrelated_control': True, 'killed': 'stale-import-bytecode',
        'test': 'test_inspect_edit_verify_and_git_commit_with_real_processes'}, indent=2), encoding='utf-8')
    print('KILLED stale-import-bytecode: real patched import must execute fresh source')


if __name__ == '__main__':
    main()
