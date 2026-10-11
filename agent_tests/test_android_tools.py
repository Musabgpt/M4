"""The model can only select probed capabilities and host-owned consent."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "agent"))
from m4core.runtime import ToolRuntime
from m4core.bridge import AndroidBridge


class AndroidToolsTests(unittest.TestCase):
    def runtime(self, capabilities):
        directory = tempfile.TemporaryDirectory(); self.addCleanup(directory.cleanup)
        root = Path(directory.name); state = root / ".term-agent"; state.mkdir()
        with patch.object(AndroidBridge, "capabilities", return_value=capabilities):
            runtime = ToolRuntime(root, state, lambda r, p, write=False: r / p,
                                  lambda *a: None, {"python3"}, lambda *a: "", approved=True)
        self.addCleanup(runtime.close)
        return runtime

    def test_phone_tools_follow_runtime_probe(self):
        self.assertNotIn("PhoneOpenApp", self.runtime({}).registry.entries)
        runtime = self.runtime({"phone": True})
        calls = []
        with patch.object(runtime.bridge, "request", side_effect=lambda action, args: calls.append((action, args)) or {"status": "dispatched", "screen_verified": False}):
            observed = json.loads(runtime.call("PhoneOpenApp", {"app": "واتساب"}))
        self.assertEqual(calls, [("phone", {"action": "open_app", "app": "واتساب"})])
        self.assertFalse(observed["screen_verified"])
        for args in ({"app": "x", "owner_session_id": "forged"}, {"app": "x", "approved": True}):
            with self.assertRaises(ValueError): runtime.call("PhoneOpenApp", args)

    def test_read_only_plan_rejects_phone_mutation_before_bridge(self):
        runtime = self.runtime({"phone": True}); runtime.mode = "plan_only"
        with patch.object(runtime.bridge, "request") as request:
            with self.assertRaises(ValueError): runtime.call("PhoneOpenApp", {"app": "x"})
            request.assert_not_called()

    def test_ask_always_requires_actual_host_decision(self):
        runtime = self.runtime({"permission_broker": True}); runtime.mode = "ask_always"
        with patch.object(runtime.bridge, "request", return_value={"approved": False}):
            with self.assertRaises(ValueError): runtime.call("Write", {"path": "denied.txt", "content": "bad"})
        self.assertFalse((runtime.root / "denied.txt").exists())
        with patch.object(runtime.bridge, "request", return_value={"approved": True}) as decision:
            runtime.call("Write", {"path": "allowed.txt", "content": "good"})
        self.assertEqual((runtime.root / "allowed.txt").read_text(), "good")
        self.assertEqual(decision.call_args.args[1]["tool"], "Write")

    def test_metadata_is_present_without_polluting_model_schemas(self):
        runtime = self.runtime({"phone": True})
        self.assertIn("risk_level", runtime.registry.metadata["PhoneOpenApp"])
        self.assertNotIn("risk_level", runtime.registry.schemas[0])


if __name__ == "__main__": unittest.main()
