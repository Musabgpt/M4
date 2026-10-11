import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
HERE=Path(__file__).resolve().parents[1]; sys.path.insert(0,str(HERE/'agent'))
spec=importlib.util.spec_from_file_location('checkpoint_host',HERE/'agent/agent.py'); host=importlib.util.module_from_spec(spec);sys.modules[spec.name]=host;spec.loader.exec_module(host)
from m4core.runtime import ToolRuntime
class CheckpointHookTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root,self.state=host.workspace(self.temp.name)
        self.env=patch.dict(os.environ,{'M4_TURN_ID':'test-turn'});self.env.start();self.addCleanup(self.env.stop)
        self.runtime=ToolRuntime(self.root,self.state,host.safe_path,host.backup,host.COMMANDS,host.command,approved=True);self.addCleanup(self.runtime.close)
    def test_rewind_restores_group_but_never_erases_external_changes(self):
        (self.root/'old.txt').write_text('old');self.runtime.call('Read',{'path':'old.txt'})
        self.runtime.call('Write',{'path':'old.txt','content':'new'})
        self.runtime.call('Write',{'path':'added.txt','content':'added'})
        (self.root/'old.txt').write_text('human edit')
        with self.assertRaises(ValueError):self.runtime.call('Rewind',{'id':'test-turn'})
        self.assertEqual((self.root/'added.txt').read_text(),'added')
        (self.root/'old.txt').write_text('new')
        receipt=json.loads(self.runtime.call('Rewind',{'id':'test-turn'}))
        self.assertEqual(receipt['status'],'rewound');self.assertEqual((self.root/'old.txt').read_text(),'old');self.assertFalse((self.root/'added.txt').exists())
    def test_real_pretool_hook_blocks_write_and_cannot_allow_plan_mutations(self):
        (self.root/'hook.py').write_text("import json,sys; print(json.dumps({'decision':'block','reason':'expected guard'}))")
        self.runtime.call('HookInstall',{'event':'PreToolUse','matcher':'Write','command':'python3 hook.py'})
        with self.assertRaises(ValueError): self.runtime.call('Write',{'path':'blocked.txt','content':'bad'})
        self.assertFalse((self.root/'blocked.txt').exists())
        (self.root/'hook.py').write_text("import json; print(json.dumps({'decision':'approve'}))")
        self.runtime.mode='plan_only'
        with self.assertRaises(ValueError): self.runtime.call('Write',{'path':'blocked.txt','content':'bad'})
        self.assertFalse((self.root/'blocked.txt').exists())
if __name__=='__main__':unittest.main()
