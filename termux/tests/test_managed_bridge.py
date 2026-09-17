import json, os, sys, tempfile, threading, unittest, urllib.request, urllib.error
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
class BridgeHTTPTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.env={k:os.environ.get(k) for k in ('HOME','LUMENA_WORKSPACE','LUMENA_BRIDGE_TOKEN')}
        os.environ.update(HOME=cls.tmp.name,LUMENA_WORKSPACE=cls.tmp.name+'/workspace',LUMENA_BRIDGE_TOKEN='test-control-token')
        import managed_bridge
        cls.module=managed_bridge
        cls.server=managed_bridge.ThreadingHTTPServer(('127.0.0.1',0),managed_bridge.Handler)
        cls.url='http://127.0.0.1:'+str(cls.server.server_port)
        cls.worker=threading.Thread(target=cls.server.serve_forever,daemon=True);cls.worker.start()
    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown();cls.server.server_close();cls.worker.join(2);cls.tmp.cleanup()
        for k,v in cls.env.items():
            if v is None:os.environ.pop(k,None)
            else:os.environ[k]=v
    def request(self,path,payload,authorized=True):
        headers={'Content-Type':'application/json'}
        if authorized:headers['Authorization']='Bearer test-control-token'
        req=urllib.request.Request(self.url+path,data=json.dumps(payload).encode(),headers=headers)
        with urllib.request.urlopen(req,timeout=3) as response:return json.load(response)
    def test_cancel_requires_authentication(self):
        with self.assertRaises(urllib.error.HTTPError) as caught:self.request('/jobs/cancel',{'requestId':'unauthorized-job-123'},False)
        self.assertEqual(401,caught.exception.code)
        self.assertEqual('unknown',self.request('/jobs/status',{'requestId':'unauthorized-job-123'})['status'])
    def test_legacy_health_still_works(self):
        result=self.request('/tool',{'tool':'health','args':{}})
        self.assertTrue(result['ok']);self.assertIn('version=0.8.1',result['stdout'])
    def test_root_excludes_secret(self):
        with urllib.request.urlopen(self.url,timeout=3) as response:body=response.read().decode()
        self.assertNotIn('test-control-token',body)
if __name__=='__main__':unittest.main()
