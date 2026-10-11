"""Hot, tested data tools and instruction skills never expand host grants."""
import ast
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import uuid
from .registry import tool

ALLOWED_IMPORTS = {"math", "statistics", "json", "re", "datetime", "collections"}
FORBIDDEN = {"open", "eval", "exec", "compile", "getattr", "setattr", "globals", "locals", "vars", "input", "__import__", "help", "breakpoint"}
def validate_source(source):
    if not isinstance(source, str) or len(source) > 30000: raise ValueError("Data source is too large")
    tree = ast.parse(source)
    if not any(isinstance(n, ast.FunctionDef) and n.name == "run" for n in tree.body): raise ValueError("Tool must define run(arguments)")
    for n in ast.walk(tree):
        if isinstance(n, (ast.ClassDef, ast.AsyncFunctionDef, ast.Global, ast.Nonlocal)): raise ValueError("Classes and global state are unavailable")
        if isinstance(n, ast.Name) and (n.id in FORBIDDEN or n.id.startswith("__")): raise ValueError("Forbidden capability: " + n.id)
        if isinstance(n, ast.Attribute) and n.attr.startswith("_"): raise ValueError("Private attributes are unavailable")
        if isinstance(n, (ast.Import, ast.ImportFrom)):
            names = [n.module] if isinstance(n, ast.ImportFrom) else [x.name for x in n.names]
            if any(x not in ALLOWED_IMPORTS for x in names): raise ValueError("Only computation modules are available")
    return tree

class Extensions:
    def __init__(self, runtime): self.runtime, self.base, self.loaded = runtime, runtime.root / ".m4", set()
    def name(self, value):
        if not isinstance(value, str) or not re.fullmatch(r"[a-z][a-z0-9_]{1,50}", value): raise ValueError("Invalid extension name")
        return value
    def directory(self, category, name):
        relative = ".m4/" + category + "/" + self.name(name)
        folder = self.runtime._path(self.runtime.root, relative)
        if folder != self.runtime.root / relative: raise ValueError("Extension directory cannot be a symlink")
        return folder
    def skill_list(self, args):
        base = self.base / "skills"
        return [{"name": p.parent.name, "summary": p.read_text()[:400], "untrusted": True} for p in sorted(base.glob("*/SKILL.md"))[:100]
                if p.is_file() and p.stat().st_size <= 50000 and not (p.parent / "disabled").exists() and p.resolve().is_relative_to(base.resolve())]
    def skill_read(self, args):
        folder = self.directory("skills", args["name"]); source = folder / "SKILL.md"
        if (folder / "disabled").exists() or not source.is_file() or source.stat().st_size > 50000: raise ValueError("Skill unavailable")
        return {"name": args["name"], "content": source.read_text(), "untrusted": True}
    def skill_create(self, args):
        self.runtime._permission(mutation=True, execution=True); folder = self.directory("skills", args["name"])
        if folder.exists(): raise ValueError("Skill already exists")
        if len(args["content"]) > 50000 or len(args["script"]) > 50000: raise ValueError("Skill too large")
        argv = shlex.split(args["test_command"])
        if not argv or argv[0] not in self.runtime.shells.commands or "/" in argv[0]: raise ValueError("Unapproved test executable")
        staging = folder.with_name("staging_" + uuid.uuid4().hex); staging.mkdir(parents=True)
        try:
            (staging / "SKILL.md").write_text(args["content"]); (staging / "script.py").write_text(args["script"])
            from .runtime import child_environment
            result = subprocess.run(argv, cwd=staging, env=child_environment(), capture_output=True, text=True, timeout=30)
            receipt = {"command": args["test_command"], "exit_code": result.returncode, "stdout": result.stdout[-8000:], "stderr": result.stderr[-4000:]}
            if result.returncode: raise ValueError("Skill test failed: " + receipt["stderr"])
            (staging / "test-receipt.json").write_text(json.dumps(receipt)); staging.replace(folder)
            return {"name": args["name"], "status": "activated", "test": receipt}
        finally:
            if staging.exists():
                import shutil; shutil.rmtree(staging)
    def invoke(self, folder, arguments):
        path = folder / "source.py"; data = json.loads((folder / "manifest.json").read_text())
        if (folder / "disabled").exists() or hashlib.sha256(path.read_bytes()).hexdigest() != data["sha256"]: raise ValueError("Disabled or modified untested tool")
        from .registry import Registry
        Registry._validate(arguments, tool("user__" + data["name"], data["description"], data["properties"], data["required"])["function"]["parameters"], data["name"])
        from .runtime import child_environment
        result = subprocess.run(["python3", "-I", str(Path(__file__).with_name("data_tool_worker.py")), str(path)], input=json.dumps(arguments),
            cwd=self.runtime.root, env=child_environment(), capture_output=True, text=True, timeout=4)
        if result.returncode or len(result.stdout) > 64000: raise ValueError("Data tool failed: " + result.stderr[-1500:])
        return json.loads(result.stdout)
    def refresh(self):
        candidates = {}
        base = self.base / "tools"
        for path in sorted(base.glob("*/manifest.json"))[:100]:
            folder = path.parent
            if (folder / "disabled").exists() or not folder.resolve().is_relative_to(base.resolve()): continue
            try:
                if path.stat().st_size > 30000: continue
                data = json.loads(path.read_text())
                if data.get("tested") is not True: continue
                name = self.name(data["name"]); source = folder / "source.py"; validate_source(source.read_text())
                if hashlib.sha256(source.read_bytes()).hexdigest() != data["sha256"]: continue
                candidates["user__" + name] = (data, folder)
            except (OSError, ValueError, KeyError, SyntaxError): continue
        for name in self.loaded - set(candidates): self.runtime.registry.unregister(name)
        for name, (data, folder) in candidates.items():
            if name not in self.runtime.registry.entries: self.runtime.registry.register(tool(name, data["description"], data["properties"], data["required"]),
                lambda a, f=folder: self.invoke(f, a), {"risk_level": "compute", "capabilities": ["pure_data"], "timeout": 4})
        self.loaded = set(candidates)
    def factory(self, args):
        self.runtime._permission(mutation=True); validate_source(args["source"]); folder = self.directory("tools", args["name"])
        if folder.exists(): raise ValueError("Tool already exists")
        if not 1 <= len(args["tests"]) <= 20: raise ValueError("Expected-output tests are required")
        data = {k: args[k] for k in ("name", "description", "properties", "required")}; data["sha256"] = hashlib.sha256(args["source"].encode()).hexdigest()
        data["tested"] = False
        folder.mkdir(parents=True); (folder / "source.py").write_text(args["source"]); (folder / "manifest.json").write_text(json.dumps(data))
        try:
            for case in args["tests"]:
                if self.invoke(folder, case["arguments"]) != case["expected"]: raise ValueError("Expected-output test failed")
            (folder / "tests.json").write_text(json.dumps(args["tests"]))
            data["tested"] = True; (folder / "manifest.json").write_text(json.dumps(data)); self.refresh()
        except BaseException:
            (folder / "disabled").touch(); self.refresh(); raise
        return {"status": "activated", "tool": "user__" + args["name"], "sha256": data["sha256"], "tests_passed": len(args["tests"])}
    def disable(self, args):
        self.runtime._permission(mutation=True); name = self.name(args["name"]); found = False
        for category in ("tools", "skills"):
            folder = self.directory(category, name)
            if folder.is_dir(): (folder / "disabled").touch(); found = True
        self.refresh()
        if not found: raise ValueError("Unknown extension")
        return {"status": "disabled", "name": name}
