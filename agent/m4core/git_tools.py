"""Explicit repository operations; no force pushes or automatic main writes."""
import re
import subprocess

class GitTools:
    def __init__(self, runtime): self.runtime = runtime
    def git(self, *argv):
        from .runtime import child_environment
        result = subprocess.run(["git", *argv], cwd=self.runtime.root, env={**child_environment(), "GIT_TERMINAL_PROMPT": "0"}, capture_output=True, text=True, timeout=60)
        if result.returncode: raise ValueError("Git error: " + result.stderr[-2000:])
        return result.stdout[:30000]
    def status(self, args): return {"branch": self.git("branch", "--show-current").strip(), "changes": self.git("status", "--porcelain=v1"), "head": self.git("rev-parse", "HEAD").strip()}
    def diff(self, args):
        argv = ["diff", "--no-ext-diff"]
        if args.get("staged"): argv.append("--cached")
        if args.get("path"): self.runtime._path(self.runtime.root, args["path"]); argv += ["--", args["path"]]
        return {"diff": self.git(*argv)}
    def branch(self, args):
        self.runtime._permission(mutation=True)
        name = args["name"]
        if not re.fullmatch(r"(?:m4|newal|codex)/[A-Za-z0-9][A-Za-z0-9._/-]{0,100}", name) or ".." in name or name.endswith("/"): raise ValueError("Work must use an m4/, newal/ or codex/ branch")
        self.git("switch", "-c", name)
        return self.status({})
    def commit(self, args):
        self.runtime._permission(mutation=True, execution=True)
        branch = self.git("branch", "--show-current").strip()
        if branch in {"main", "master", ""}: raise ValueError("Commit requires a work branch")
        paths = args["paths"]
        if not paths or len(paths) > 50: raise ValueError("Explicit commit paths required")
        for path in paths: self.runtime._path(self.runtime.root, path, write=True)
        self.git("add", "--", *paths)
        self.git("-c", "user.name=M4", "-c", "user.email=m4@users.noreply.github.com", "commit", "--only", "-m", args["message"][:1000], "--", *paths)
        return self.status({})
    def log(self, args): return {"commits": self.git("log", "-n", str(args.get("limit", 10)), "--format=%H %s")}
