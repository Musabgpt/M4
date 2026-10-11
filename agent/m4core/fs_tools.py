"""Project-scoped file tools: Read, Write, Edit, MultiEdit, Glob, Grep, ListDir, TodoWrite.

Path rules mirror agent.py: no absolute paths, no "..", no .git/.term-agent,
no symlink/dir overwrite. Backups use the same backups.jsonl ledger so the
existing `rollback` command keeps working.
"""
import fnmatch
import json
import subprocess
import uuid
from pathlib import Path

STATE = ".term-agent"
MAX_READ_BYTES = 1_000_000
MAX_WRITE_BYTES = 500_000
MAX_BACKUP_BYTES = 5_000_000


class ToolError(Exception):
    pass


class Workspace:
    def __init__(self, root, state=None):
        self.root = Path(root).expanduser().resolve()
        if not self.root.is_dir():
            raise ToolError("Missing project directory: %s" % self.root)
        self.state = Path(state) if state else self.root / STATE
        self.state.mkdir(exist_ok=True, mode=0o700)
        self.read_files = set()      # files read this session (read-before-write)
        self.todos = []

    # ---- paths -----------------------------------------------------
    def path(self, value, write=False):
        if not isinstance(value, str) or not value or "\x00" in value:
            raise ToolError("Invalid path")
        rel = Path(value)
        if rel.is_absolute() or ".." in rel.parts or (
                rel.parts and rel.parts[0] in (STATE, ".git")):
            raise ToolError("Restricted project path")
        cand = (self.root / rel).resolve()
        if not cand.is_relative_to(self.root) or (write and cand == self.root):
            raise ToolError("Path escapes project")
        for bad in (self.root / STATE, self.root / ".git"):
            if cand == bad or cand.is_relative_to(bad):
                raise ToolError("Restricted project metadata")
        if write and (cand.is_symlink() or cand.is_dir()):
            raise ToolError("Cannot overwrite symlink or directory")
        return cand

    def _backup(self, path, rel):
        folder = self.state / "backups"
        folder.mkdir(exist_ok=True)
        name = uuid.uuid4().hex
        exists = path.exists()
        if exists:
            if path.stat().st_size > MAX_BACKUP_BYTES:
                raise ToolError("Large file: manual backup required (>5 MB)")
            (folder / name).write_bytes(path.read_bytes())
        with (self.state / "backups.jsonl").open("a", encoding="utf-8") as s:
            s.write(json.dumps({"path": rel, "name": name, "exists": exists}) + "\n")

    # ---- Read ------------------------------------------------------
    def read(self, file_path, offset=1, limit=2000):
        p = self.path(file_path)
        if not p.is_file():
            raise ToolError("Not a file: %s" % file_path)
        if p.stat().st_size > MAX_READ_BYTES:
            raise ToolError("File too large; use Grep or offset/limit")
        try:
            lines = p.read_text(encoding="utf-8").splitlines()
        except UnicodeError:
            raise ToolError("Binary or non-UTF-8 file")
        offset = max(1, int(offset or 1))
        limit = max(1, min(int(limit or 2000), 5000))
        chunk = lines[offset - 1: offset - 1 + limit]
        self.read_files.add(str(p))
        out = "\n".join("%6d\t%s" % (offset + i, l[:2000]) for i, l in enumerate(chunk))
        more = len(lines) - (offset - 1 + len(chunk))
        if more > 0:
            out += "\n... %d more lines (use offset=%d)" % (more, offset + len(chunk))
        return out or "(empty file)"

    # ---- Write / Edit ---------------------------------------------
    def write(self, file_path, content):
        p = self.path(file_path, write=True)
        if not isinstance(content, str) or len(content.encode("utf-8")) > MAX_WRITE_BYTES:
            raise ToolError("Invalid content or larger than 500KB")
        if p.exists() and str(p) not in self.read_files:
            raise ToolError("Existing file must be read first: " + file_path)
        self._backup(p, file_path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        self.read_files.add(str(p))
        return "Wrote %s (%d bytes)" % (file_path, len(content.encode("utf-8")))

    @staticmethod
    def _apply(text, old, new, replace_all):
        if not isinstance(old, str) or not old:
            raise ToolError("old_string must be non-empty")
        if old == new:
            raise ToolError("old_string and new_string are identical")
        n = text.count(old)
        if n == 0:
            raise ToolError("old_string not found")
        if n > 1 and not replace_all:
            raise ToolError("old_string matches %d places; add context or set replace_all" % n)
        return text.replace(old, new) if replace_all else text.replace(old, new, 1)

    def edit(self, file_path, old_string, new_string, replace_all=False):
        return self.multiedit(file_path, [{"old_string": old_string,
                                           "new_string": new_string,
                                           "replace_all": replace_all}])

    def multiedit(self, file_path, edits):
        p = self.path(file_path, write=True)
        if not p.is_file():
            raise ToolError("Not a file: " + file_path)
        if str(p) not in self.read_files:
            raise ToolError("File must be read first: " + file_path)
        if not isinstance(edits, list) or not edits:
            raise ToolError("edits must be a non-empty list")
        text = p.read_text(encoding="utf-8")
        for e in edits:                       # atomic: all or nothing
            text = self._apply(text, e.get("old_string"), e.get("new_string", ""),
                               bool(e.get("replace_all")))
        if len(text.encode("utf-8")) > MAX_WRITE_BYTES:
            raise ToolError("Result larger than 500KB")
        self._backup(p, file_path)
        p.write_text(text, encoding="utf-8")
        return "Edited %s (%d edit%s)" % (file_path, len(edits), "" if len(edits) == 1 else "s")

    # ---- Glob / Grep / ListDir ------------------------------------
    def _walk(self, base):
        for p in sorted(base.rglob("*")):
            parts = p.relative_to(self.root).parts
            if parts[0] in (".git", STATE) or "node_modules" in parts:
                continue
            yield p

    def glob(self, pattern, path="."):
        base = self.path(path)
        if not isinstance(pattern, str) or not pattern:
            raise ToolError("Invalid pattern")
        hits = []
        for p in self._walk(base):
            rel = str(p.relative_to(base))
            if p.is_file() and (fnmatch.fnmatch(rel, pattern) or fnmatch.fnmatch(p.name, pattern)):
                hits.append((p.stat().st_mtime, str(p.relative_to(self.root))))
        hits.sort(reverse=True)
        return "\n".join(h for _, h in hits[:250]) or "(no matches)"

    def grep(self, pattern, path=".", glob=None, ignore_case=False, context=0, max_count=50):
        base = self.path(path)
        if not isinstance(pattern, str) or not pattern or len(pattern) > 500:
            raise ToolError("Invalid pattern")
        cmd = ["rg", "-n", "--max-count", str(min(int(max_count), 200)),
               "-C", str(min(int(context), 5))]
        if ignore_case:
            cmd.append("-i")
        if glob:
            cmd += ["-g", str(glob)]
        cmd += ["--", pattern, str(base)]
        try:
            r = subprocess.run(cmd, cwd=self.root, capture_output=True, text=True, timeout=25)
        except FileNotFoundError:
            return self._py_grep(pattern, base, ignore_case)
        except subprocess.TimeoutExpired:
            raise ToolError("Search timed out")
        if r.returncode not in (0, 1):
            raise ToolError("Search error: " + r.stderr[:250])
        return r.stdout[:12000] or "(no matches)"

    def _py_grep(self, pattern, base, ignore_case):
        import re
        rx = re.compile(pattern, re.I if ignore_case else 0)
        out = []
        for p in self._walk(base):
            if not p.is_file() or p.stat().st_size > MAX_READ_BYTES:
                continue
            try:
                for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
                    if rx.search(line):
                        out.append("%s:%d:%s" % (p.relative_to(self.root), i, line[:300]))
            except UnicodeError:
                continue
            if len(out) >= 200:
                break
        return "\n".join(out)[:12000] or "(no matches)"

    def listdir(self, path="."):
        p = self.path(path)
        if not p.is_dir():
            raise ToolError("Not a directory")
        names = sorted(x.name + ("/" if x.is_dir() else "") for x in p.iterdir()
                       if x.name not in (".git", STATE))
        return "\n".join(names[:250]) or "(empty)"

    # ---- TodoWrite -------------------------------------------------
    def todo_write(self, todos):
        if not isinstance(todos, list) or len(todos) > 50:
            raise ToolError("todos must be a list of at most 50 items")
        clean = []
        for t in todos:
            status = t.get("status", "pending")
            if status not in ("pending", "in_progress", "completed"):
                raise ToolError("Invalid todo status: %s" % status)
            clean.append({"content": str(t.get("content", ""))[:300], "status": status})
        if sum(1 for t in clean if t["status"] == "in_progress") > 1:
            raise ToolError("Only one todo may be in_progress")
        self.todos = clean
        (self.state / "todos.json").write_text(json.dumps(clean, ensure_ascii=False, indent=2),
                                               encoding="utf-8")
        done = sum(1 for t in clean if t["status"] == "completed")
        return "Todos updated (%d/%d done)" % (done, len(clean))
