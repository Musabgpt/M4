"""Tool schemas + dispatcher tying fs tools, shell and permissions together."""
import json

from .fs_tools import ToolError, Workspace
from .permissions import decide
from .shell import ShellManager


def _fn(name, desc, props, required=()):
    return {"type": "function", "function": {"name": name, "description": desc,
            "parameters": {"type": "object", "properties": props, "required": list(required)}}}


S, I, B = {"type": "string"}, {"type": "integer"}, {"type": "boolean"}
TOOL_SCHEMAS = [
    _fn("Read", "Read a text file with line numbers (offset/limit optional).",
        {"file_path": S, "offset": I, "limit": I}, ["file_path"]),
    _fn("Write", "Create or overwrite a file (existing files must be read first).",
        {"file_path": S, "content": S}, ["file_path", "content"]),
    _fn("Edit", "Exact string replacement; fails if old_string is not unique.",
        {"file_path": S, "old_string": S, "new_string": S, "replace_all": B},
        ["file_path", "old_string", "new_string"]),
    _fn("MultiEdit", "Several exact replacements in one file, atomically.",
        {"file_path": S, "edits": {"type": "array"}}, ["file_path", "edits"]),
    _fn("Glob", "Find files by glob pattern, newest first.", {"pattern": S, "path": S}, ["pattern"]),
    _fn("Grep", "Regex search (ripgrep).",
        {"pattern": S, "path": S, "glob": S, "ignore_case": B, "context": I}, ["pattern"]),
    _fn("ListDir", "List a directory.", {"path": S}),
    _fn("Bash", "Run a shell command in the project. background=true returns a shell_id.",
        {"command": S, "timeout": I, "background": B}, ["command"]),
    _fn("BashOutput", "Read new output of a background shell.", {"shell_id": S}, ["shell_id"]),
    _fn("KillShell", "Kill a background shell.", {"shell_id": S}, ["shell_id"]),
    _fn("TodoWrite", "Replace the live todo list (one in_progress max).",
        {"todos": {"type": "array"}}, ["todos"]),
    _fn("Finish", "Request independent verification of the goal.", {"summary": S}, ["summary"]),
]
TOOL_NAMES = {t["function"]["name"] for t in TOOL_SCHEMAS}


class ToolRegistry:
    def __init__(self, root, state=None, mode="ask"):
        self.ws = Workspace(root, state)
        self.shell = ShellManager(self.ws.root)
        self.mode = mode
        self.touched = set()

    def execute(self, name, args, approve=lambda tool, args, reason: False):
        """Return a string result. `approve` is called when permissions say 'ask'."""
        if name not in TOOL_NAMES:
            return "Unknown tool " + str(name)
        if not isinstance(args, dict):
            return "Tool error: arguments must be an object"
        verdict, reason = decide(name, args, self.mode)
        if verdict == "deny":
            return "Denied: " + reason
        if verdict == "ask" and not approve(name, args, reason):
            return "Not approved by user: " + reason
        try:
            return self._run(name, args)
        except ToolError as e:
            return "Tool error: " + str(e)
        except (KeyError, TypeError, ValueError) as e:
            return "Tool error: bad arguments (%s)" % e

    def _run(self, name, a):
        ws = self.ws
        if name == "Read":
            return ws.read(a["file_path"], a.get("offset", 1), a.get("limit", 2000))
        if name == "Write":
            r = ws.write(a["file_path"], a["content"]); self.touched.add(a["file_path"]); return r
        if name == "Edit":
            r = ws.edit(a["file_path"], a["old_string"], a["new_string"], a.get("replace_all", False))
            self.touched.add(a["file_path"]); return r
        if name == "MultiEdit":
            r = ws.multiedit(a["file_path"], a["edits"]); self.touched.add(a["file_path"]); return r
        if name == "Glob":
            return ws.glob(a["pattern"], a.get("path", "."))
        if name == "Grep":
            return ws.grep(a["pattern"], a.get("path", "."), a.get("glob"),
                           a.get("ignore_case", False), a.get("context", 0))
        if name == "ListDir":
            return ws.listdir(a.get("path", "."))
        if name == "Bash":
            return json.dumps(self.shell.run(a["command"], a.get("timeout", 120),
                                             a.get("background", False)), ensure_ascii=False)
        if name == "BashOutput":
            return json.dumps(self.shell.output(a["shell_id"]), ensure_ascii=False)
        if name == "KillShell":
            return json.dumps(self.shell.kill(a["shell_id"]), ensure_ascii=False)
        if name == "TodoWrite":
            return ws.todo_write(a["todos"])
        if name == "Finish":
            return "VERIFY"
        raise ToolError("unreachable")
