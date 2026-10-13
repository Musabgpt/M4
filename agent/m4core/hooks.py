"""NewAl-compatible lifecycle hooks can narrow permissions, never grant them."""
import json
import re
import shlex
import subprocess

EVENTS = {"PreToolUse", "PostToolUse", "UserPromptSubmit", "Stop", "SubagentStop", "SessionStart", "PreCompact"}
class Hooks:
    def __init__(self, runtime): self.runtime = runtime; self.path = runtime.state / "hooks.json"
    def listing(self, args):
        try: return json.loads(self.path.read_text())
        except (OSError, ValueError): return {}
    def install(self, args):
        self.runtime._permission(mutation=True, execution=True)
        if args["event"] not in EVENTS: raise ValueError("Unsupported lifecycle event")
        command = shlex.split(args["command"])
        if not command or command[0] not in self.runtime.shells.commands or "/" in command[0]: raise ValueError("Hook executable unavailable")
        if len(args["matcher"]) > 100: raise ValueError("Hook matcher too long")
        # Matcher is a set of exact tool names. Arbitrary regular expressions
        # from external instructions must not consume unbounded CPU.
        names = args["matcher"].split("|")
        if any(x != "*" and x not in self.runtime.registry.entries for x in names): raise ValueError("Hook matcher names an unregistered tool")
        hooks = self.listing({}); hooks.setdefault(args["event"], []).append({"command": command, "matcher": names})
        if self.runtime.capabilities.get("permission_broker") and not self.runtime.bridge.request("approve", {"tool": "Install persistent hook", "arguments": args}).get("approved"): raise ValueError("User denied hook installation")
        if sum(len(x) for x in hooks.values()) > 20: raise ValueError("Hook quota exceeded")
        self.path.write_text(json.dumps(hooks)); return {"status": "installed", "event": args["event"]}
    def run(self, event, name="", arguments=None, result=None):
        from .runtime import child_environment
        observed = []
        if self.runtime.mode == "plan_only": return observed
        for hook in self.listing({}).get(event, [])[:4]:
            if name and "*" not in hook["matcher"] and name not in hook["matcher"]: continue
            previous = self.runtime.active_call
            try:
                if previous is None: self.runtime.active_call = ("Hook " + event, {"command": hook["command"]})
                self.runtime._permission(execution=True)
                data = json.dumps({"event": event, "tool": name, "arguments": arguments, "result": result}, ensure_ascii=False)
                executed = self.runtime.shells.start({"command": shlex.join(hook["command"]), "stdin": data[:500000], "timeout": 5})
            finally: self.runtime.active_call = previous
            try: answer = json.loads(executed["stdout"][:10000]) if executed["stdout"].strip() else {}
            except ValueError: answer = {"message": executed["stdout"][:1000]}
            if executed["status"] == "timed_out": raise ValueError("Lifecycle hook timed out")
            if executed["exit_code"] == 2 or answer.get("decision") == "block" or answer.get("hookSpecificOutput", {}).get("permissionDecision") == "deny":
                raise ValueError("Hook blocked action: " + str(answer.get("reason", executed["stderr"]))[:1000])
            observed.append({"exit_code": executed["exit_code"], "output": answer, "untrusted": True})
        return observed
