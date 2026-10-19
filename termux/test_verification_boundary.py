"""Real bounded file snapshots around synthetic process results; no owner files."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
from unittest.mock import patch


class VerificationBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='lumena-verifier-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.project = self.root / 'workspace' / 'demo'
        (self.project / 'tests').mkdir(parents=True)
        (self.project / 'tests/test_calc.py').write_text('assert 1 == 1\n')
        self.env = patch.dict(os.environ, LUMENA_WORKSPACE=str(self.project.parent),
            LUMENA_BRIDGE_TOKEN='synthetic-test-token', LUMENA_READONLY_ROOTS='', LUMENA_READ_ROOTS_FILE=str(self.root/'none'))
        self.env.start(); self.addCleanup(self.env.stop)
        spec = importlib.util.spec_from_file_location('verification_bridge', Path(__file__).with_name('bridge.py'))
        self.bridge = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.bridge)
        self.bridge.STATE_DIR = self.root/'state'
        self.bridge.PYTHON_CHILD_ROOT = self.root/'python-child'

    def run_fixture(self, change=None, failed=False, classname='tests.test_calc', empty=False, argv='-q', skipped=False):
        def process(command, *args, **kwargs):
            report = next(x.split('=', 1)[1] for x in command if x.startswith('--junitxml='))
            if change: change()
            filename = 'tests/test_calc.py' if classname in ('tests.test_calc', 'test_calc') else 'other/test_calc.py'
            body = '' if empty else ('<testcase file="'+filename+'" classname="'+classname+'" name="test_one">' +
                ('<failure type="AssertionError" message="expected 1, got 2"/>' if failed else '<skipped/>' if skipped else '') + '</testcase>')
            Path(report).write_text('<testsuites><testsuite>'+body+'</testsuite></testsuites>')
            return dict(ok=not failed, exitCode=1 if failed else 0, stdout='fixture', stderr='')
        with patch.object(self.bridge, 'run_process', side_effect=process):
            return self.bridge.execute_tool('python.tests', dict(cwd='demo', argv=argv,
                project_receipt='true', verification_paths='["tests"]'))

    def test_test_edit_during_run_cannot_be_unchanged_verification(self):
        result = self.run_fixture(lambda: (self.project/'tests/test_calc.py').write_text('assert True\n'))
        self.assertFalse(result['verification']['boundaryUnchanged'], 'CHANGED_VERIFIER_ACCEPTED')

    def test_product_edit_preserves_verifier_but_config_edit_changes_it(self):
        first = self.run_fixture()['verification']
        (self.project/'calc.py').write_text('fixed = True\n')
        second = self.run_fixture()['verification']
        self.assertEqual(first, second)
        (self.project/'pytest.ini').write_text('[pytest]\naddopts=-x\n')
        self.assertNotEqual(first['boundaryHash'], self.run_fixture()['verification']['boundaryHash'])

    def test_failed_run_keeps_real_case_identity_and_failure_signature(self):
        fail = self.run_fixture(failed=True)['verification']
        passed = self.run_fixture()['verification']
        self.assertEqual(1, fail['testsCount'])
        self.assertTrue(fail['complete'] and fail['boundaryUnchanged'])
        self.assertEqual(fail['selectedTestsHash'], passed['selectedTestsHash'])
        self.assertEqual(fail['specHash'], passed['specHash'])
        self.assertEqual(64, len(fail['failureSignature']))

    def test_different_command_and_uncovered_tests_cannot_share_proof(self):
        self.assertNotEqual(self.run_fixture()['verification']['specHash'],
                            self.run_fixture(argv='-q -x')['verification']['specHash'])
        self.assertFalse(self.run_fixture(classname='other.test_calc')['verification']['complete'])
        self.assertTrue(self.run_fixture(classname='test_calc')['verification']['complete'])

    def test_empty_report_and_undeclared_legacy_boundary_have_no_proof(self):
        self.assertNotIn('verification', self.run_fixture(empty=True))
        self.assertIsNone(self.bridge.verification_boundary(self.project, '[]'))

    def test_skipped_case_never_becomes_complete_repair_evidence(self):
        self.assertFalse(self.run_fixture(skipped=True)['verification']['complete'])

    def test_real_pytest_failure_then_product_repair_preserves_check_identity(self):
        (self.project/'calc.py').write_text('def add(a, b): return a - b\n')
        (self.project/'tests/test_calc.py').write_text('from calc import add\ndef test_add(): assert add(2, 3) == 5\n')
        def process(command, cwd, timeout, **kwargs):
            env = dict(kwargs.get('env') or {}, PYTEST_DISABLE_PLUGIN_AUTOLOAD='1')
            result = subprocess.run([sys.executable, *command[1:]], cwd=cwd, timeout=timeout,
                env=env, capture_output=True, text=True)
            return dict(ok=result.returncode == 0, exitCode=result.returncode, stdout=result.stdout, stderr=result.stderr)
        args = dict(cwd='demo', argv='-q', project_receipt='true', verification_paths='["tests"]')
        with patch.object(self.bridge, 'run_process', side_effect=process):
            failed = self.bridge.execute_tool('python.tests', args)
            (self.project/'calc.py').write_text('def add(a, b): return a + b\n')
            passed = self.bridge.execute_tool('python.tests', args)
        self.assertFalse(failed['ok'], failed)
        self.assertTrue(passed['ok'], passed)
        for result in (failed, passed):
            self.assertTrue(result['verification']['complete'], result)
            self.assertTrue(result['verification']['boundaryUnchanged'], result)
        for name in ('boundaryHash', 'specHash', 'selectedTestsHash'):
            self.assertEqual(failed['verification'][name], passed['verification'][name])
        self.assertIn('AssertionError', failed['verification']['failureSummary'])

    def test_escape_symlink_and_oversized_boundary_fail_closed(self):
        with self.assertRaises(ValueError): self.bridge.verification_boundary(self.project, '["../outside"]')
        (self.project/'tests/large.bin').write_bytes(b'x'*(2*1024*1024+1))
        self.assertFalse(self.bridge.verification_boundary(self.project, '["tests"]')['complete'])
        try: (self.project/'link').symlink_to(self.project/'tests', target_is_directory=True)
        except OSError: return
        with self.assertRaises(ValueError): self.bridge.verification_boundary(self.project, '["link"]')


if __name__ == '__main__': unittest.main()
