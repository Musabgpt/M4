"""Grouped file rewind adapted from NewAl sessions, with conflict verification."""
import hashlib
import json
import os
from pathlib import Path
import uuid

class Checkpoints:
    def __init__(self, runtime): self.runtime = runtime; self.folder = runtime.state / "checkpoints"
    def group(self):
        value = os.environ.get("M4_TURN_ID", "terminal")
        if not value.replace("-", "").isalnum() or len(value) > 80: raise ValueError("Invalid checkpoint group")
        return value
    def path(self, identifier):
        if not identifier.replace("-", "").isalnum() or len(identifier) > 80: raise ValueError("Invalid checkpoint id")
        return self.folder / identifier
    @staticmethod
    def hash(path): return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
    def before(self, path, relative):
        folder = self.path(self.group()); folder.mkdir(parents=True, exist_ok=True)
        manifest = folder / "manifest.json"
        rows = json.loads(manifest.read_text()) if manifest.exists() else {}
        if relative not in rows:
            identifier = uuid.uuid4().hex
            if path.is_file():
                if path.stat().st_size > 5000000: raise ValueError("Checkpoint file exceeds 5MB")
                (folder / identifier).write_bytes(path.read_bytes())
            rows[relative] = {"blob": identifier if path.exists() else None, "before": self.hash(path), "after": self.hash(path)}
            temporary = manifest.with_suffix(".tmp"); temporary.write_text(json.dumps(rows)); temporary.replace(manifest)
    def after(self, path, relative):
        manifest = self.path(self.group()) / "manifest.json"; rows = json.loads(manifest.read_text())
        rows[relative]["after"] = self.hash(path)
        temporary = manifest.with_suffix(".tmp"); temporary.write_text(json.dumps(rows)); temporary.replace(manifest)
    def listing(self, args):
        return [{"checkpoint_id": p.parent.name, "paths": list(json.loads(p.read_text()))} for p in sorted(self.folder.glob("*/manifest.json"), key=lambda p:p.stat().st_mtime, reverse=True)[:50]]
    def rewind(self, args):
        self.runtime._permission(mutation=True)
        folder = self.path(args["id"]); manifest = folder / "manifest.json"
        if not manifest.is_file(): raise ValueError("Checkpoint not found")
        rows = json.loads(manifest.read_text()); targets = []
        for relative, row in rows.items():
            path = self.runtime._path(self.runtime.root, relative, write=True)
            if self.hash(path) != row["after"]: raise ValueError("Rewind conflict: " + relative + " was edited outside this checkpoint")
            blob = row["blob"]
            if blob is not None:
                if not isinstance(blob, str) or not all(c in "0123456789abcdef" for c in blob) or len(blob) != 32: raise ValueError("Invalid checkpoint blob")
                original = folder / blob
                if self.hash(original) != row["before"]: raise ValueError("Checkpoint hash mismatch")
            targets.append((path, row, relative))
        # Verify every conflict before restoring the first file.
        for path, row, relative in targets:
            self.runtime.backup(self.runtime.state, path, relative)
            if row["blob"] is None: path.unlink(missing_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True); temporary = path.with_name(".rewind-" + uuid.uuid4().hex)
                temporary.write_bytes((folder / row["blob"]).read_bytes()); temporary.replace(path)
            self.runtime.reads.pop(str(path), None)
        manifest.rename(folder / "rewound.json")
        return {"status": "rewound", "checkpoint_id": args["id"], "paths": list(rows)}
