import json, sys, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, HTTPServer
from threading import Thread
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
import b232

class Cases(unittest.TestCase):
    def test_delivery(self):
        calls=[]
        class H(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                calls.append((self.path,self.headers['Idempotency-Key'],self.headers['X-Service-Key'],self.rfile.read(int(self.headers['Content-Length']))))
                self.send_response(status.pop(0));self.end_headers();self.wfile.write(b'{"id":9}')
        with HTTPServer(('127.0.0.1',0),H) as srv:
            t=Thread(target=srv.serve_forever,daemon=True); t.start()
            try:
                post=lambda:b232.transmit(dict(deviceId=1,aggregationType='HOURLY',periodStart='a',periodEnd='b'),f'http://127.0.0.1:{srv.server_port}/api/v1/internal/ai/count-logs','demo-test')
                with patch('src.api_client.time.sleep'):
                    status=[503,201,200]; self.assertEqual(post(),(201,9));self.assertEqual(post(),(200,9))
                    self.assertEqual(len(calls),3); self.assertEqual(len(set(calls)),1)
                    for code in (400,401,403,409):
                        status=[code]; n=len(calls)
                        with self.assertRaises(b232.requests.HTTPError): post()
                        self.assertEqual(len(calls),n+1)
            finally: srv.shutdown();t.join()
    def test_aggregate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);clips=[]
            for i in (1,2):
                d=root/str(i); d.mkdir()
                (d/'result.json').write_text(json.dumps(dict(clip=str(i),sha256=str(i),entry=i,exit=1,tracks=4)))
                (d/'config.json').write_text('{"device_id":1}')
                clips.append(dict(id=str(i),start=f'2026-01-01T08:0{i}:00+07:00',end=f'2026-01-01T08:0{i}:45+07:00'))
            m=dict(deviceId=1,aggregationType='HOURLY',periodStart='2026-01-01T08:00:00+07:00',periodEnd='2026-01-01T09:00:00+07:00',clips=clips)
            now=b232.utc(m['periodEnd']);p=b232.prepare(m,root,now)
            self.assertEqual((p['entryCount'],p['exitCount'],p['detectCount']),(3,2,8))
            clips[1]['start']=clips[0]['start']
            with self.assertRaises(ValueError): b232.prepare(m,root,now)
if __name__=='__main__':unittest.main()
