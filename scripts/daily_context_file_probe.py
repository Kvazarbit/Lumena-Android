"""A fresh-process file-CAS mutant must fail its named assertion while a read control passes."""
from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'termux/bridge.py'
OUT = ROOT / 'app/build/daily-context-file-probe'
TARGET = 'test_file_tools.FileReadRangeTest.test_observed_hash_cas_rejects_external_change_and_allows_fresh_retry'
CONTROL = 'test_file_tools.FileReadRangeTest.test_read_range_is_one_based_and_inclusive'
RUNNER = '''import sys,unittest,xml.etree.ElementTree as E
sys.path.insert(0,'termux')
suite=unittest.defaultTestLoader.loadTestsFromNames(sys.argv[2:])
result=unittest.TestResult(); suite.run(result)
root=E.Element('testsuite',tests=str(result.testsRun))
failed={t.id():s for t,s in result.failures}; errors={t.id():s for t,s in result.errors}
for name in sys.argv[2:]:
 c=E.SubElement(root,'testcase',name=name)
 if name in failed:E.SubElement(c,'failure',message=failed[name]).text=failed[name]
 if name in errors:E.SubElement(c,'error',message=errors[name]).text=errors[name]
E.ElementTree(root).write(sys.argv[1],encoding='utf-8')
sys.exit(not result.wasSuccessful())
'''


def run(label):
    report = OUT / (label + '.xml')
    with tempfile.TemporaryDirectory(prefix='lumena-fresh-import-') as cache:
        env = dict(os.environ, PYTHONPYCACHEPREFIX=cache, PYTHONDONTWRITEBYTECODE='1')
        result = subprocess.run([sys.executable, '-c', RUNNER, str(report), TARGET, CONTROL], cwd=ROOT, env=env,
                                capture_output=True, text=True, timeout=60)
    assert report.exists(), ('NO_REPORT', result.stderr)
    root = E.parse(report).getroot()
    assert int(root.get('tests', 0)) == 2 and len(root.findall('testcase')) == 2
    cases = {c.get('name'): list(c) for c in root.findall('testcase')}
    assert all(not c.findall('error') for c in root.findall('testcase')), 'SETUP_ERROR'
    return result.returncode, cases


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    code, baseline = run('baseline')
    assert code == 0 and all(not marks for marks in baseline.values()), 'INVALID_BASELINE'
    original = SOURCE.read_bytes()
    anchor = "    expected = args.get('expected_sha256')"
    raw = original.decode('utf-8')
    assert raw.count(anchor) == 1
    try:
        SOURCE.write_text(raw.replace(anchor, '    return  # injected semantic fault\n' + anchor), encoding='utf-8', newline='')
        code, actual = run('disabled-cas')
        assert code != 0 and not actual[CONTROL], 'UNRELATED_CONTROL_FAILED'
        assert len(actual[TARGET]) == 1 and actual[TARGET][0].tag == 'failure'
        assert 'ValueError not raised' in actual[TARGET][0].get('message', ''), 'UNEXPECTED_ORACLE_FAILURE'
    finally:
        SOURCE.write_bytes(original)
    (OUT / 'report.json').write_text(json.dumps({'schema': 'lumena.file-cas-probe.v1',
        'source_sha': os.environ.get('GITHUB_SHA', 'LOCAL'), 'baseline': 'PASS', 'mutations': [
            {'name': 'disabled-file-cas', 'verdict': 'KILLED_EXPECTED', 'case': TARGET,
             'reason': 'ValueError not raised', 'control': CONTROL}]}, indent=2), encoding='utf-8')
    print('KILLED_EXPECTED disabled-file-cas; unrelated read control PASS')


if __name__ == '__main__':
    main()
