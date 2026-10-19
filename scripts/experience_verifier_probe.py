"""A changed-test oracle must kill a disabled boundary check; empty-report control passes."""
import json
import os
import daily_context_file_probe as probe


def main():
    probe.OUT = probe.ROOT / 'app/build/experience-verifier-probe'
    probe.TARGET = 'test_verification_boundary.VerificationBoundaryTest.test_test_edit_during_run_cannot_be_unchanged_verification'
    probe.CONTROL = 'test_verification_boundary.VerificationBoundaryTest.test_empty_report_and_undeclared_legacy_boundary_have_no_proof'
    probe.OUT.mkdir(parents=True, exist_ok=True)
    code, baseline = probe.run('baseline')
    assert code == 0 and all(not marks for marks in baseline.values()), 'INVALID_BASELINE'
    original = probe.SOURCE.read_bytes()
    old = "boundaryUnchanged=before['hash'] == after['hash'] and spec_hash == verification_spec(cwd, extra, declared)"
    text = original.decode('utf-8'); assert text.count(old) == 1
    try:
        probe.SOURCE.write_text(text.replace(old, 'boundaryUnchanged=True'), encoding='utf-8', newline='')
        code, actual = probe.run('disabled-boundary')
        assert code != 0 and not actual[probe.CONTROL], 'UNRELATED_CONTROL_FAILED'
        assert len(actual[probe.TARGET]) == 1 and actual[probe.TARGET][0].tag == 'failure'
        assert 'CHANGED_VERIFIER_ACCEPTED' in actual[probe.TARGET][0].get('message', ''), 'WRONG_ORACLE'
    finally:
        probe.SOURCE.write_bytes(original)
    (probe.OUT/'report.json').write_text(json.dumps({'schema': 'lumena.verifier-probe.v1',
        'source_sha': os.environ.get('GITHUB_SHA', 'LOCAL'), 'baseline': 'PASS', 'mutations': [
            {'name': 'disabled-verification-boundary', 'verdict': 'KILLED_EXPECTED', 'case': probe.TARGET,
             'control': probe.CONTROL, 'reason': 'CHANGED_VERIFIER_ACCEPTED'}]}, indent=2), encoding='utf-8')
    print('KILLED_EXPECTED disabled-verification-boundary; empty-report control PASS')


if __name__ == '__main__': main()
