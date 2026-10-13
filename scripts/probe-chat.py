#!/usr/bin/env python3
"""Live provider evidence. Network failure is BLOCKED, never a fake PASS."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("m4_live_probe", root / "agent/agent.py")
host = importlib.util.module_from_spec(spec); sys.modules[spec.name] = host; spec.loader.exec_module(host)
destination = Path(sys.argv[1] if len(sys.argv) > 1 else "qa-provider")
destination.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory() as directory:
    workspace, state = host.workspace(directory)
    os.environ.update(M4_PROVIDER_HEALTH=str(state / "health.json"), M4_PROVIDER_DEADLINE_SECONDS="40", NEWAL_TASK_STATE_HOME=str(state / "newal"))
    request = {"owner_session_id": "live-ci", "turn_id": "live-html", "messages": [{"role": "user", "content": "Create and attach probe.html, a valid HTML file containing the Arabic text اختبار حي M4. Use ArtifactCreate, then Finish. Do not provide desktop shell instructions."}]}
    try:
        report = host.chat_turn(workspace, state, request, approved=True, permission_mode="auto_project", max_steps=3)
    except Exception as error:
        report = {"status": "blocked", "content": str(error)[:1000], "tools": [], "artifacts": []}
    verified = []
    for artifact in report.get("artifacts", []):
        file = Path(artifact["verified_path"])
        if file.is_file() and hashlib.sha256(file.read_bytes()).hexdigest() == artifact["sha256"]:
            verified.append(artifact)
            (destination / artifact["display_name"]).write_bytes(file.read_bytes())
    status = "PASS" if report["status"] == "completed" and any("اختبار حي M4" in Path(a["verified_path"]).read_text() for a in verified) else "BLOCKED"
    evidence = {"status": status, "boundary": "actual public providers and real Python artifact bytes on CI Linux; Android Alpine not exercised", "report": report, "health": host.load(state / "health.json", {})}
    (destination / "live-provider.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
    for name in ("events.jsonl", "ENV.md"):
        if (state / name).is_file(): (destination / name).write_bytes((state / name).read_bytes())
    print("LIVE PROVIDER:", status, report.get("route", ""), report.get("content", "")[:500], flush=True)
