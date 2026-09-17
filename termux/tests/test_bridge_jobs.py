import sys, threading, time, unittest, tempfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bridge_jobs import JobRegistry
class JobsTest(unittest.TestCase):
    def test_live_output_cancel_and_replay(self):
        r=JobRegistry(); rid='test-live-output-123'; result=[]; payload={'tool':'python.run'}
        with tempfile.TemporaryDirectory() as d:
            action=lambda:r.run_process([sys.executable,'-c','import time;print("READY",flush=True);time.sleep(30)'],Path(d))
            worker=threading.Thread(target=lambda:result.append(r.execute(rid,payload,action)));worker.start();until=time.monotonic()+5
            while 'READY' not in r.status(rid).get('stdout','') and time.monotonic()<until:time.sleep(.02)
            self.assertIn('READY',r.status(rid)['stdout']);r.cancel(rid);worker.join(6)
            self.assertFalse(worker.is_alive());self.assertEqual('cancelled',r.status(rid)['status']);self.assertFalse(result[0]['ok'])
            self.assertEqual(result[0],r.execute(rid,payload,lambda:self.fail('replayed execution')))
    def test_cancel_before_request(self):
        r=JobRegistry();rid='cancel-before-arrival'
        self.assertEqual('cancel_pending',r.cancel(rid)['status'])
        self.assertFalse(r.execute(rid,{},lambda:self.fail('cancel lost'))['ok'])
        self.assertEqual('cancelled',r.status(rid)['status'])
    def test_unknown_cancel_is_not_confirmation_after_restart(self):
        r=JobRegistry();s=r.cancel('old-process-request-123')
        self.assertEqual('cancel_pending',s['status']);self.assertIsNone(s['result'])
    def test_completed_cancel_is_not_rollback(self):
        r=JobRegistry();rid='completed-request-123';r.execute(rid,{},lambda:{'ok':True})
        self.assertEqual('completed',r.cancel(rid)['status'])
    def test_timeout(self):
        r=JobRegistry()
        with tempfile.TemporaryDirectory() as d:
            result=r.execute('timeout-request-123',{},lambda:r.run_process([sys.executable,'-c','import time;time.sleep(10)'],Path(d),1))
        self.assertEqual('Command timed out',result['error'])
    def test_isolation(self):
        r=JobRegistry();r.cancel('one-request-123456')
        self.assertTrue(r.execute('other-request-123456',{},lambda:{'ok':True})['ok'])
    def test_id_conflict_and_invalid(self):
        r=JobRegistry();rid='same-request-123456';r.execute(rid,{'a':1},lambda:{'ok':True})
        self.assertFalse(r.execute(rid,{'a':2},lambda:self.fail('conflict'))['ok'])
        with self.assertRaises(ValueError):r.cancel('../../something')
    def test_sigterm_ignored(self):
        r=JobRegistry();rid='ignored-term-request';result=[]
        with tempfile.TemporaryDirectory() as d:
            action=lambda:r.run_process([sys.executable,'-c','import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);print("READY",flush=True);time.sleep(30)'],Path(d))
            t=threading.Thread(target=lambda:result.append(r.execute(rid,{},action)));t.start();until=time.monotonic()+5
            while 'READY' not in r.status(rid).get('stdout','') and time.monotonic()<until:time.sleep(.02)
            r.cancel(rid);t.join(7);self.assertFalse(t.is_alive());self.assertFalse(result[0]['ok'])
    def test_capacity_keeps_tombstone(self):
        r=JobRegistry(capacity=1);r.cancel('keep-this-request-123')
        with self.assertRaises(ValueError):r.cancel('different-request-123')
        self.assertEqual('cancel_pending',r.status('keep-this-request-123')['status'])
if __name__=='__main__':unittest.main()
