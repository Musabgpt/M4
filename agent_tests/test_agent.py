#!/usr/bin/env python3
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

location=Path(__file__).resolve().parents[1]/"agent"/"agent.py"
spec=importlib.util.spec_from_file_location("term_agent",location)
agent=importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)

class TestTermAgent(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root,self.state=agent.workspace(self.temp.name)

    def test_outside_paths_and_private_paths(self):
        for name in ("../escape","/etc/passwd",".git/config",".term-agent/state.json"):
            with self.assertRaises(ValueError):
                agent.safe_path(self.root,name,write=True)

    def test_list_root(self):
        result=agent.execute(self.root,self.state,"list_files",{"path":"."},False)
        self.assertIsInstance(result,str)

    def test_symlink_escape(self):
        (self.root/"link").symlink_to(self.root.parent,target_is_directory=True)
        with self.assertRaises(ValueError):
            agent.safe_path(self.root,"link/file",write=True)

    def test_read_write_rollback(self):
        (self.root/"test.txt").write_text("old")
        result=agent.execute(self.root,self.state,"write_file",
                             {"path":"test.txt","content":"new"},False)
        self.assertIn("Wrote",result)
        self.assertEqual((self.root/"test.txt").read_text(),"new")
        agent.rollback(self.root,self.state,"test.txt")
        self.assertEqual((self.root/"test.txt").read_text(),"old")

    def test_new_file_rollback(self):
        agent.execute(self.root,self.state,"write_file",
                      {"path":"nested/new.txt","content":"hello"},False)
        agent.rollback(self.root,self.state,"nested/new.txt")
        self.assertFalse((self.root/"nested/new.txt").exists())

    def test_exec_opt_in_and_allowlist(self):
        self.assertIn("blocked",agent.command(self.root,"python3 -V",False).lower())
        self.assertIn("not allowed",agent.command(self.root,"rm -rf ./",True))
        self.assertEqual(json.loads(agent.command(self.root,"python3 -V",True))["exit_code"],0)

    def test_key_scrubbed_from_child_process(self):
        with patch.dict(os.environ, {"TERM_AGENT_API_KEY":"SHOULD_NOT_BE_EXPOSED","GITHUB_TOKEN":"ALSO_SECRET"}):
            result=agent.command(self.root,
                "python3 -c 'import os; print(os.getenv(\"TERM_AGENT_API_KEY\", \"removed\")); print(os.getenv(\"GITHUB_TOKEN\", \"removed\"))'", True)
        data=json.loads(result)
        self.assertEqual(data["exit_code"],0)
        self.assertEqual(data["stdout"].strip().splitlines(),["removed","removed"])

    def test_check_required(self):
        ok,message=agent.verify(self.root,self.state,True)
        self.assertFalse(ok)
        self.assertIn("No checks",message)

    def test_checks_pass(self):
        agent.save(self.state/"checks.json",["python3 -V"])
        ok,_=agent.verify(self.root,self.state,True)
        self.assertTrue(ok)

    def test_provider_rejects_insecure_http(self):
        with patch.dict(os.environ,{"TERM_AGENT_API_BASE":"http://evil.example/v1",
                                     "TERM_AGENT_MODEL":"mock"}):
            with self.assertRaises(ValueError):
                agent.endpoint()

    def test_loop_finish_verified_and_persisted(self):
        agent.save(self.state/"checks.json",["python3 -V"])
        sample={"tool_calls":[{"function":{"name":"finish",
                                          "arguments":'{"summary":"good"}'}}]}
        with patch.object(agent,"endpoint",return_value=("https://localhost/v1/chat/completions","mock")), \
             patch.object(agent,"ask",return_value=sample):
            self.assertEqual(agent.loop(self.root,self.state,"my goal",1,True),0)
        self.assertEqual(agent.load(self.state/"state.json",{})["phase"],"complete")
        with patch.object(agent,"endpoint",return_value=("https://localhost/v1/chat/completions","mock")), \
             patch.object(agent,"ask",return_value=sample):
            self.assertEqual(agent.loop(self.root,self.state,None,1,True),0)

if __name__=="__main__":
    unittest.main()
