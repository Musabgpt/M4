import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.request
import urllib.error

SOURCE = Path(__file__).resolve().parents[1]/'app/src/main/assets/termux_engine.py'
spec = importlib.util.spec_from_file_location('termux_engine', SOURCE)
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)

class TermuxEngineTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        self.engine = engine.Engine(self.root, ['node',os.environ.get('M4_FILESYSTEM_SERVER','missing')])
    def tearDown(self):
        self.engine.close(); self.folder.cleanup()
    def call(self, action, **args): return self.engine.call('owner',action,args)
    def test_real_exit_and_separate_streams(self):
        row=self.call('exec_command',command='printf hello; printf problem >&2; exit 7',yield_time_ms=1000)
        self.assertEqual((row['exit_code'],row['stdout'],row['stderr']),(7,'hello','problem'))
    def test_persistent_input_and_incremental_output(self):
        row=self.call('exec_command',command='printf ready; read value; printf "got:%s" "$value"',yield_time_ms=100)
        self.assertIsNone(row['exit_code']); self.assertEqual(row['stdout'],'ready')
        final=self.call('write_stdin',session_id=row['session_id'],chars='M4\n',cursor=row['next_cursor'],yield_time_ms=1000)
        self.assertEqual(final['exit_code'],0); self.assertEqual(final['stdout'],'got:M4')
        self.assertEqual(self.call('read_output',session_id=row['session_id'],cursor=final['next_cursor'])['stdout'],'')
    def test_pty_and_ctrl_c(self):
        row=self.call('exec_command',command='test -t 0 && printf pty-ready; exec sleep 60',tty=True,yield_time_ms=300)
        self.assertIn('pty-ready',row['stdout'])
        final=self.call('write_stdin',session_id=row['session_id'],chars='\x03',yield_time_ms=1000)
        self.assertIsNotNone(final['exit_code']); self.assertNotEqual(final['exit_code'],0)
    def test_cross_conversation_access_is_rejected(self):
        row=self.call('exec_command',command='sleep 60',yield_time_ms=0)
        with self.assertRaises(ValueError): self.engine.call('other','terminate_process',{'session_id':row['session_id']})
        self.assertIsNone(self.call('read_output',session_id=row['session_id'])['exit_code'])
        self.assertIsNotNone(self.call('terminate_process',session_id=row['session_id'])['exit_code'])
    def test_workdir_escape_and_symlink_rejected(self):
        (self.root/'outside').symlink_to(self.root.parent,target_is_directory=True)
        for path in ('../', 'outside'):
            with self.assertRaises(ValueError): self.call('exec_command',command='pwd',workdir=path)
    def test_apply_patch_checks_before_mutation(self):
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        target=self.root/'file.txt'; target.write_text('before\n')
        patch='--- a/file.txt\n+++ b/file.txt\n@@ -1 +1 @@\n-before\n+after\n'
        self.assertEqual(self.call('apply_patch',patch=patch)['exit_code'],0)
        self.assertEqual(target.read_text(),'after\n')
        self.assertNotEqual(self.call('apply_patch',patch=patch)['exit_code'],0)
        self.assertEqual(target.read_text(),'after\n')
    def test_output_budget_preserves_cursor(self):
        row=self.call('exec_command',command="python3 -c 'print(\"x\" * 90000)'",yield_time_ms=1000)
        self.assertTrue(row['has_more']); self.assertLessEqual(len(row['stdout']),64000)
        tail=self.call('read_output',session_id=row['session_id'],cursor=row['next_cursor'])
        self.assertEqual(len(row['stdout']+tail['stdout']),90001)
    def test_loopback_auth_and_browser_origin(self):
        http=engine.server(self.engine,'test-secret',0)
        thread=threading.Thread(target=http.serve_forever,daemon=True); thread.start()
        try:
            url='http://127.0.0.1:%s/rpc'%http.server_port
            body=json.dumps({'owner':'owner','action':'status'}).encode()
            for headers in ({},{'Authorization':'Bearer wrong'},{'Authorization':'Bearer test-secret','Origin':'https://example.com'}):
                with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(urllib.request.Request(url,body,headers))
                self.assertEqual(error.exception.code,403)
            with urllib.request.urlopen(urllib.request.Request(url,body,{'Authorization':'Bearer test-secret'})) as response:
                self.assertEqual(json.load(response)['result']['status'],'ready')
        finally: http.shutdown(); http.server_close(); thread.join()
    @unittest.skipUnless(os.environ.get('M4_FILESYSTEM_SERVER'),'official npm Filesystem MCP required')
    def test_official_mcp_write_edit_read_and_escape(self):
        names={x['name'] for x in self.call('filesystem_tools')['tools']}
        self.assertTrue({'write_file','read_text_file','edit_file'}.issubset(names))
        def fs(name, **args): return self.call('filesystem_call',name=name,arguments=args)
        self.assertFalse(fs('write_file',path='demo.txt',content='hello M4').get('isError'))
        self.assertFalse(fs('edit_file',path='demo.txt',edits=[{'oldText':'hello','newText':'welcome'}]).get('isError'))
        self.assertIn('welcome M4',json.dumps(fs('read_text_file',path='demo.txt')))
        with self.assertRaises(ValueError): fs('read_text_file',path='../outside')
        self.call('close_owner')
        self.assertEqual(self.engine.mcp,{})

if __name__=='__main__': unittest.main()
