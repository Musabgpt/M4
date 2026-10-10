#!/usr/bin/env python3
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

AGENT = Path(__file__).resolve().parents[1] / "agent" / "agent.py"
spec=importlib.util.spec_from_file_location("m4agent", AGENT)
agent=importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)

class NewAlIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root, self.state = agent.workspace(self.temp.name)
        self.patch=patch.dict(os.environ, {"NEWAL_TASK_STATE_HOME": str(self.state/"newal_tasks")})
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_persistent_task_checkpoint(self):
        row=agent.task_state.checkpoint(str(self.root),objective="Build a Kotlin app",
            progress="Starting",next_step="Write tests")
        restored=agent.task_state.resume(str(self.root),row["id"])
        self.assertEqual(restored["objective"],"Build a Kotlin app")
        agent.task_state.complete(str(self.root),row["id"],"Tests passed")
        self.assertEqual(agent.task_state.resume(str(self.root),row["id"])["status"],"complete")

    def test_model_cannot_erase_original_goal(self):
        self.assertTrue(agent.continuity.lost_task_reply("ما في مهمة", "Build app"))
        self.assertFalse(agent.continuity.lost_task_reply("Here is the next tool result","Build app"))
        self.assertIn("Build app",agent.continuity.correction("Build app"))

    def test_verified_finish_completes_newal_checkpoint(self):
        agent.save(self.state/"checks.json",["python3 -V"])
        tool={"tool_calls":[{"function":{"name":"finish",
                                          "arguments":'{"summary":"verified"}'}}]}
        with patch.object(agent,"endpoint",return_value=("https://example.com/v1/chat/completions","mock")), \
             patch.object(agent,"ask",return_value=tool):
            self.assertEqual(agent.loop(self.root,self.state,"Build safely",1,True),0)
        state=agent.load(self.state/"state.json",{})
        self.assertTrue(state.get("newal_checkpoint"))
        row=agent.task_state.resume(str(self.root),state["newal_checkpoint"])
        self.assertEqual(row["status"],"complete")

if __name__=="__main__":
    unittest.main()
