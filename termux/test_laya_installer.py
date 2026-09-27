"""Exercise the installer transaction with fake downloads/compiler, never a real model."""
import ast
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.state = self.root / '.lumena/laya'
        self.state.mkdir(parents=True)
        (self.state / 'laya').write_text('previous binary')
        (self.state / 'laya').chmod(0o700)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        script = Path(__file__).with_name('install_laya_system1.sh').read_text()
        patch = script.split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        old = next(n.value.value for n in ast.parse(patch).body
                   if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'old' for t in n.targets))
        source = '#include <setjmp.h>\n/* laya_load laya_predict */\n' + old
        (self.root / 'source').write_text(source)
        # Substitute only the fixture source pin. All patch/build/backup logic remains production code.
        script = script.replace('2cb905156d775f25c4a1b1403085a029392a71fd984e209cbaf73ad9d66f03a9', hashlib.sha256(source.encode()).hexdigest())
        self.script = self.root / 'installer.sh'
        self.script.write_text(script)
        self.make('curl', '''#!/bin/bash
while [ "$1" != "-o" ]; do shift; done
case "$2" in */laya.c) cp "$HOME/source" "$2" ;; *) echo 'Apache License' > "$2" ;; esac
''')
        self.make('clang', '''#!/bin/bash
[ "${FAIL_BUILD:-0}" = 0 ] || exit 42
case " $* " in *' -fopenmp -static-openmp '*) ;; *) exit 43 ;; esac
while [ "$1" != "-o" ]; do shift; done
printf 'new binary' > "$2"
''')
        (self.root / 'lib').mkdir()
        (self.root / 'lib/libomp.a').touch()
        self.env = dict(os.environ, HOME=str(self.root), PREFIX=str(self.root),
                        PATH=str(self.bin) + ':' + os.environ['PATH'], LUMENA_LAYA_THREADS='2')

    def make(self, name, text):
        p = self.bin / name
        p.write_text(text)
        p.chmod(0o700)

    def run_script(self, action='runtime', **env):
        return subprocess.run(['bash', str(self.script), action], env=dict(self.env, **env), capture_output=True, text=True, timeout=10)

    def test_failed_compile_preserves_installed_binary_and_cleans_lock(self):
        result = self.run_script(FAIL_BUILD='1')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('previous binary', (self.state / 'laya').read_text())
        self.assertFalse((self.state / 'previous').exists())
        self.assertFalse((self.state / 'install.lock').exists())
        self.assertEqual([], list(self.state.glob('build-*')))

    def test_install_backs_up_and_rollback_restores_missing_config(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('new binary', (self.state / 'laya').read_text())
        backup = Path((self.state / 'previous').read_text().strip())
        self.assertEqual('previous binary', (backup / 'laya').read_text())
        self.assertEqual('2', (self.state / 'threads').read_text().strip())
        # Process lifecycle is separate; exercise rollback's real filesystem transaction.
        body = self.script.read_text().rsplit('\ncase "${1:-status}"', 1)[0]
        self.script.write_text(body + '\nstop() { :; }\nstart() { test "$THREADS" = 1; }\nrollback\n')
        result = self.run_script('rollback')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('previous binary', (self.state / 'laya').read_text())
        self.assertFalse((self.state / 'threads').exists())

    def test_invalid_threads_and_concurrent_install_fail_before_mutation(self):
        self.assertNotEqual(0, self.run_script(LUMENA_LAYA_THREADS='99').returncode)
        (self.state / 'install.lock').mkdir()
        self.assertNotEqual(0, self.run_script().returncode)
        self.assertEqual('previous binary', (self.state / 'laya').read_text())

    def test_failed_start_automatically_rolls_back(self):
        body = self.script.read_text().rsplit('\ncase "${1:-status}"', 1)[0]
        self.script.write_text(body + '''
stop() { :; }
start() { test "$(cat "$BIN")" = 'previous binary'; }
upgrade
''')
        result = self.run_script('upgrade')
        self.assertEqual(9, result.returncode, result.stderr)
        self.assertEqual('previous binary', (self.state / 'laya').read_text())


if __name__ == '__main__':
    unittest.main()
