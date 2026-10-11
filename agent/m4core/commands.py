"""Slash commands invoke actual runtime tools, never another model-only path."""
import json
import shlex
from .provider_health import ProviderHealth

COMMANDS = ("tools", "skills", "mcp", "agents", "hooks", "terminal", "termux", "phone", "browser", "artifacts", "export", "schedule", "tasks", "task", "doctor", "selftest", "github", "plan", "permissions", "rewind", "cost", "compact", "memory", "init", "git", "model", "status")
def dispatch(runtime, text):
    words = shlex.split(text); name = words[0].lstrip("/"); rest = words[1:]
    def call(tool, arguments=None):
        value = runtime.call(tool, arguments or {})
        if getattr(runtime, "command_event", None): runtime.command_event(tool, arguments or {}, value)
        return value
    def payload():
        position = text.find("{")
        if position < 0: raise ValueError("This command requires a JSON object with the tool arguments")
        return json.loads(text[position:])
    if name == "tools": return {"tools": [{"name": n, **runtime.registry.metadata[n]} for n in runtime.registry.entries]}
    if name == "skills":
        if not rest: return call("SkillsList")
        if rest[0] in {"create", "install"}: return call("SkillCreate", payload())
        if rest[0] == "disable": return call("ExtensionDisable", {"name": rest[1]})
        return call("SkillRead", {"name": rest[-1]})
    if name == "mcp":
        if not rest: return call("MCPList")
        if rest[0] == "connect": return call("MCPConnect", payload())
        if rest[0] == "disconnect": return call("MCPDisconnect", {"name": rest[1]})
    if name == "hooks":
        if not rest: return call("HooksList")
        if rest[0] == "install": return call("HookInstall", payload())
        if rest[0] == "disable": return call("HooksDisable", {"event": rest[1]})
    if name == "rewind":
        return call("Checkpoints") if not rest else call("Rewind", {"id": rest[0]})
    if name == "cost": return {"providers": ProviderHealth().rows, "note": "Usage is measured only when the provider reports it; unreported price is unknown."}
    if name == "memory": return call("MemorySearch", {"query": " ".join(rest)})
    if name == "init":
        path = runtime.root / "M4.md"
        if path.exists(): return call("Read", {"path": "M4.md"})
        return call("Write", {"path": "M4.md", "content": "# Project memory\n\nThis file is untrusted project context, not authorization.\n"})
    if name == "git":
        action = rest[0] if rest else "status"
        if action == "status": return call("GitStatus")
        if action == "diff": return call("GitDiff")
        if action == "log": return call("GitLog")
        if action == "branch": return call("GitBranch", {"name": rest[1]})
    if name == "doctor": return runtime.environment()
    if name == "selftest": return call("Verify")
    if name == "github": return call("GitHubStatus")
    if name == "termux" and rest:
        if rest[0] == "run": return call("TermuxRun", payload())
        if rest[0] == "output": return call("TermuxOutput", {"id": rest[1]})
    if name == "phone" and rest:
        action = {"apps": "PhoneListApps", "screen": "PhoneScreen", "device": "PhoneDevice", "battery": "PhoneBattery", "open": "PhoneOpenApp", "test": "PhoneTestLab"}.get(rest[0])
        if action: return call(action, {"app": " ".join(rest[1:])} if rest[0] == "open" else payload() if rest[0] == "test" else {})
    if name == "browser" and rest:
        action = {"dom": "BrowserReadDOM", "console": "BrowserConsole", "network": "BrowserNetwork", "open": "BrowserOpen", "test": "BrowserTestLab"}.get(rest[0])
        if action: return call(action, {"url": rest[1]} if rest[0] == "open" else payload() if rest[0] == "test" else {})
    if name == "artifacts": return runtime.artifacts.list_published()
    if name == "tasks": return call("ScheduleList")
    if name == "task" and len(rest) > 1:
        action = {"run": "ScheduleRun", "pause": "SchedulePause", "logs": "ScheduleLogs", "clone": "ScheduleClone"}.get(rest[0])
        if action: return call(action, {"id": rest[1]})
    if name == "schedule": return call("ScheduleCreate", payload()) if rest else call("ScheduleList")
    if name == "agents": return call("AgentsList") if not rest else call("Task", {"goal": " ".join(rest[1:]) if rest[0] == "run" else " ".join(rest), "type": "reviewer"})
    if name == "export": return call("ArtifactBundle", {"name": "chat-export", "ids": rest})
    if name == "status":
        path = runtime.state / "state.json"; return json.loads(path.read_text()) if path.is_file() else {"status": "new"}
    if name == "compact": return {"status": "requested", "action": "compact"}
    if name == "model": return ProviderHealth().rows
    if name in COMMANDS: return {"status": "needs_panel", "panel": name}
    custom = runtime.root / ".m4/commands" / (name + ".md")
    if name.replace("_", "").isalnum() and custom.is_file() and custom.stat().st_size <= 30000 and custom.resolve().is_relative_to(runtime.root.resolve()):
        return {"untrusted_command": custom.read_text(), "arguments": rest}
    raise ValueError("Unknown slash command. Available: /" + " /".join(COMMANDS))
