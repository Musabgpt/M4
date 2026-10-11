"""Self-contained no-key/no-server test of the *actual embedded* M4 worker.

The installed NewAl ZIP is simulated by a local HTTP server. No credentials,
public network or Android device is needed to verify the bootstrap contract.
"""
import base64
import http.server
import io
import json
import os
from pathlib import Path
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import zipfile
from test_termux_newal_contract import embedded_worker


STUB_SERVER = r'''
import http.server
import json
import os
from pathlib import Path
import sys

home=Path(os.environ["HOME"])
(home / ".newal-code" / "termux" / "fixture-server.pid").write_text(str(os.getpid()))
KEY=os.environ["NEWAL_SERVER_KEY"]
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        if self.headers.get("X-NewAl-Key") != KEY:
            self.send_error(401); return
        data=json.dumps({"status":"ready"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
class Server(http.server.ThreadingHTTPServer):
    allow_reuse_address=True
Server(("127.0.0.1",8791),Handler).serve_forever()
'''

class PinnedZipServer(http.server.BaseHTTPRequestHandler):
    zipped=b""
    def log_message(self,*args): pass
    def do_GET(self):
        if self.path != "/pinned-source.zip":
            self.send_error(404); return
        self.send_response(200)
        self.send_header("Content-Length", str(len(self.zipped)))
        self.end_headers()
        self.wfile.write(self.zipped)

def mock_zip():
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,"w",zipfile.ZIP_DEFLATED) as z:
        z.writestr("NewAl-fixed/desktop/newal_code/__init__.py","")
        z.writestr("NewAl-fixed/desktop/newal_code/__main__.py",STUB_SERVER)
    return buffer.getvalue()

class RealTermuxBootstrapContract(unittest.TestCase):
    def test_missing_key_installs_starts_and_reuses_agent(self):
        # Avoid colliding with a different job's localhost port.
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            try: probe.bind(("127.0.0.1",8791))
            except OSError as error: self.skipTest(str(error))
        handler=type("FixtureZip",(PinnedZipServer,),{"zipped":mock_zip()})
        server=http.server.ThreadingHTTPServer(("127.0.0.1",0),handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True)
        thread.start()
        try:
            pinned="https://codeload.github.com/Musabgpt/NewAl/zip/abfa91ee0644b27ba1957567ab484d4073d71efd"
            script=embedded_worker()
            self.assertEqual(script.count(pinned),1)
            script=script.replace(pinned,
                "http://127.0.0.1:%d/pinned-source.zip"%server.server_port)
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                env=dict(os.environ, HOME=tmp)
                try:
                    cmd=[sys.executable,"-c",script,"test-owner","","plan_only","setup"]
                    res=subprocess.run(cmd,env=env,capture_output=True,text=True,timeout=22)
                    self.assertEqual(res.returncode,0,res.stderr[-1200:])
                    row=json.loads(res.stdout.strip())
                    self.assertEqual(row["status"],"succeeded")
                    key=root/".newal-code"/"termux"/"key"
                    self.assertTrue(key.is_file(),"M4 must create its own private pairing key")
                    self.assertGreater(len(key.read_text()),30)
                    if os.name!="nt":
                        self.assertEqual(stat.S_IMODE(key.stat().st_mode),0o600)
                    installed=root/".newal-code"/"termux"/"app"/"newal_code"/"__main__.py"
                    self.assertTrue(installed.is_file(),"Pinned NewAl code must be installed")
                    res2=subprocess.run(cmd,env=env,capture_output=True,text=True,timeout=8)
                    self.assertEqual(res2.returncode,0,res2.stderr[-1000:])
                    self.assertEqual(json.loads(res2.stdout)["status"],"succeeded")
                finally:
                    pid=root/".newal-code"/"termux"/"fixture-server.pid"
                    if pid.is_file():
                        try: os.kill(int(pid.read_text()),signal.SIGTERM)
                        except ProcessLookupError: pass
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

if __name__=="__main__": unittest.main()
