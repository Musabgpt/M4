"""Use a real local JSON-RPC subprocess, not a pretend MCP tool registry."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "agent"))
from m4core.runtime import ToolRuntime

SERVER = '''import json,sys
for line in sys.stdin:
    request=json.loads(line)
    if 'id' not in request: continue
    method=request['method']
    if method=='initialize': result={'protocolVersion':'2025-06-18','capabilities':{'tools':{}},'serverInfo':{'name':'fixture','version':'1'}}
    elif method=='tools/list': result={'tools':[{'name':'sum','description':'Add fixture numbers','inputSchema':{'type':'object','properties':{'a':{'type':'integer'},'b':{'type':'integer'}},'required':['a','b'],'additionalProperties':False}}]}
    elif method=='tools/call': result={'content':[{'type':'text','text':str(request['params']['arguments']['a']+request['params']['arguments']['b'])}]}
    else: result={}
    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}),flush=True)
'''

class MCPTests(unittest.TestCase):
    def test_stdio_negotiates_executes_and_disconnect_removes_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); state=root/'.term-agent'; state.mkdir(); (root/'server.py').write_text(SERVER)
            runtime=ToolRuntime(root,state,lambda r,p,write=False:r/p,lambda *a:None,{'python3'},lambda *a:'',approved=True)
            try:
                receipt=json.loads(runtime.call('MCPConnect',{'name':'fixture','transport':'stdio','command':['python3','-u','server.py']}))
                self.assertEqual(receipt['protocol'],'2025-06-18')
                self.assertIn('mcp__fixture__sum',runtime.registry.entries)
                observed=json.loads(runtime.call('mcp__fixture__sum',{'a':17,'b':4}))
                self.assertEqual(observed['result']['content'][0]['text'],'21')
                self.assertTrue(observed['untrusted'])
                with self.assertRaises(ValueError): runtime.call('mcp__fixture__sum',{'a':1,'b':2,'approved':True})
                runtime.call('MCPDisconnect',{'name':'fixture'})
                with self.assertRaises(ValueError): runtime.call('mcp__fixture__sum',{'a':1,'b':2})
            finally: runtime.close()

if __name__ == '__main__': unittest.main()
