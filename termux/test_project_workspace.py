"""Project context contracts. Every source/DB/process lives in a temporary fixture.

Inspired by the reference Context OS's known-good / semantic mutant / unrelated
control discipline. No reference folder, owner project or model is executed.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


class ProjectWorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='lumena-project-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.workspace = self.root / 'workspace'
        self.project = self.workspace / 'demo'
        self.project.mkdir(parents=True)
        self.external = self.root / 'external'
        self.external.mkdir()
        self.env = patch.dict(os.environ, LUMENA_WORKSPACE=str(self.workspace),
            LUMENA_BRIDGE_TOKEN='test', LUMENA_READONLY_ROOTS='',
            LUMENA_READ_ROOTS_FILE=str(self.root/'none'))
        self.env.start()
        self.addCleanup(self.env.stop)
        spec = importlib.util.spec_from_file_location('project_test_bridge', Path(__file__).with_name('bridge.py'))
        self.bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.bridge)
        self.bridge.STATE_DIR = self.root / 'private-state'

    def context(self, tool='project.inspect', **args):
        result = self.bridge.execute_tool(tool, dict(cwd='demo', **args))
        self.assertTrue(result['ok'])
        return json.loads(result['stdout'])

    def test_symbols_sources_and_git_revision_are_observed_without_execution(self):
        marker = self.project / 'must-not-exist'
        (self.project/'main.py').write_text("from pathlib import Path\nPath('must-not-exist').write_text('bad')\ndef calculate_total(value):\n    return value + 1\n", encoding='utf-8')
        data = self.context('project.search', query='calculate_total')
        hit = next(x for x in data['results'] if x['symbol'] == 'calculate_total')
        self.assertEqual(3, hit['line'])
        self.assertEqual('demo/main.py', hit['path'])
        self.assertEqual(64, len(hit['source_sha256']))
        self.assertFalse(marker.exists(), 'INDEX_EXECUTED_SOURCE')
        self.assertIsNone(data['git']['head'])

    def test_recognised_secret_files_and_dependency_trees_never_enter_index(self):
        for name in ('.env', '.env.production', 'credentials.json', 'private.key'):
            (self.project/name).write_text('MARKER_PRIVATE_SECRET', encoding='utf-8')
        deps = self.project/'node_modules'
        deps.mkdir()
        (deps/'payload.py').write_text('MARKER_PRIVATE_SECRET', encoding='utf-8')
        data = self.context('project.search', query='MARKER_PRIVATE_SECRET')
        self.assertEqual([], data['results'], 'SECRET_INDEXED')
        self.assertEqual(0, data['files_count'])

    def test_same_mtime_content_change_changes_snapshot_digest(self):
        file = self.project/'main.py'
        file.write_text('VALUE = 1', encoding='utf-8')
        first = self.context()
        stat = file.stat()
        file.write_text('VALUE = 2', encoding='utf-8')
        os.utime(file, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        second = self.context()
        self.assertNotEqual(first['source_digest'], second['source_digest'], 'STALE_CONTEXT_REUSED')

    def test_truncation_is_explicit_and_never_absence_proof(self):
        for n in range(3):
            (self.project/f'f{n}.py').write_text('VALUE = 1', encoding='utf-8')
        with patch.object(self.bridge, 'PROJECT_MAX_FILES', 1):
            data = self.context('project.search', query='unfindable')
        self.assertTrue(data['truncated'], 'TRUNCATION_HIDDEN')
        self.assertFalse(data['absence_is_proof'])
        self.assertEqual(1, data['files_count'])

    def test_project_path_escape_and_another_alias_are_not_implicit(self):
        for cwd in ('demo/../../external', '../external', str(self.external)):
            with self.assertRaises(ValueError):
                self.bridge.execute_tool('project.inspect', {'cwd': cwd})
        self.bridge.READONLY_ALIASES = {'reference': self.external}
        self.bridge.READONLY_ROOTS = (self.external,)
        (self.external/'guide.md').write_text('reference guide', encoding='utf-8')
        result = self.bridge.execute_tool('project.inspect', {'cwd': '@reference'})
        self.assertEqual('@reference', json.loads(result['stdout'])['cwd'])

    def test_new_project_can_be_inspected_before_owner_approved_creation(self):
        result = self.bridge.execute_tool('project.inspect', {'cwd':'not-created-yet'})
        self.assertTrue(result['ok'])
        data = json.loads(result['stdout'])
        self.assertFalse(data['exists'])
        self.assertEqual(0, data['files_count'])
        self.assertFalse((self.workspace/'not-created-yet').exists())

    def test_symlink_sources_are_excluded(self):
        secret = self.external/'secret.py'
        secret.write_text('FORBIDDEN_REFERENCE', encoding='utf-8')
        link = self.project/'linked.py'
        try:
            link.symlink_to(secret)
        except OSError:
            # Windows may forbid symlink creation. Other path isolation cases still run.
            return
        data = self.context('project.search', query='FORBIDDEN_REFERENCE')
        self.assertEqual([], data['results'], 'LINKED_SOURCE_EXPOSED')

    def test_project_tests_require_nonempty_real_junit_receipt(self):
        def run(argv, *args, **kwargs):
            report = next(x.split('=', 1)[1] for x in argv if x.startswith('--junitxml='))
            Path(report).write_text('<testsuites><testsuite><testcase name="real"/></testsuite></testsuites>', encoding='utf-8')
            return dict(ok=True, exitCode=0, stdout='1 passed', stderr='', error=None)
        with patch.object(self.bridge, 'run_process', side_effect=run):
            result = self.bridge.execute_tool('python.tests', {'cwd':'demo', 'project_receipt':'true'})
        receipt = json.loads(result['stdout'])
        self.assertTrue(receipt['verified'])
        self.assertEqual(1, receipt['tests_count'])
        with patch.object(self.bridge, 'run_process', return_value=dict(ok=True, exitCode=0, stdout='all done')):
            missing = self.bridge.execute_tool('python.tests', {'cwd':'demo', 'project_receipt':'true'})
        self.assertFalse(missing['ok'], 'EMPTY_TEST_SUCCESS_ACCEPTED')

    def test_project_tests_reject_failed_or_only_skipped_receipts(self):
        for inner in ('<testcase><skipped/></testcase>', '<testcase><failure/></testcase>', ''):
            def run(argv, *args, **kwargs):
                report = next(x.split('=', 1)[1] for x in argv if x.startswith('--junitxml='))
                Path(report).write_text('<testsuites><testsuite>'+inner+'</testsuite></testsuites>', encoding='utf-8')
                return dict(ok=True, exitCode=0, stdout='fixture', stderr='', error=None)
            with patch.object(self.bridge, 'run_process', side_effect=run):
                result = self.bridge.execute_tool('python.tests', {'cwd':'demo', 'project_receipt':'true'})
            self.assertFalse(result['ok'], 'INVALID_TEST_RECEIPT_ACCEPTED')

    def test_project_commit_refuses_staged_files_outside_selected_subdirectory(self):
        subprocess.run(['git', 'init', str(self.project)], check=True, capture_output=True)
        (self.project/'sub').mkdir()
        (self.project/'outside.py').write_text('outside', encoding='utf-8')
        subprocess.run(['git', 'add', '.'], cwd=self.project, check=True, capture_output=True)
        with self.assertRaisesRegex(ValueError, 'staged changes outside'):
            self.bridge.execute_tool('git.commit', {'cwd':'demo/sub', 'message':'fixture', 'project_scope':'true'})


if __name__ == '__main__':
    unittest.main()
