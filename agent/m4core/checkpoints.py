"""Grouped file rewind adapted from NewAl sessions, with conflict verification."""
import hashlib
import json
import os
from pathlib import Path
import uuid
import time

class Checkpoints:
    def __init__(self, runtime):
        self.runtime = runtime; self.folder = runtime.state / "checkpoints"
        self.identifier = os.environ.get('M4_TURN_ID') or 'terminal-' + uuid.uuid4().hex
    def group(self):
        value = self.identifier
        if not value.replace("-", "").isalnum() or len(value) > 80: raise ValueError("Invalid checkpoint group")
        return value
    def path(self, identifier):
        if not identifier.replace("-", "").isalnum() or len(identifier) > 80: raise ValueError("Invalid checkpoint id")
        return self.folder / identifier
    @staticmethod
    def hash(path): return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
    def begin(self):
        folder=self.path(self.group()); folder.mkdir(parents=True,exist_ok=True)
        if not (folder/'meta.json').exists(): (folder/'meta.json').write_text(json.dumps({'owner':os.environ.get('M4_OWNER_SESSION','terminal'),'created_at':time.time()}))
        if not (folder/'manifest.json').exists(): (folder/'manifest.json').write_text('{}')
    def before(self, path, relative):
        self.begin()
        folder = self.path(self.group()); folder.mkdir(parents=True, exist_ok=True)
        manifest = folder / "manifest.json"
        rows = json.loads(manifest.read_text()) if manifest.exists() else {}
        if relative not in rows:
            identifier = uuid.uuid4().hex
            if path.is_file():
                if path.stat().st_size > 5000000: raise ValueError("Checkpoint file exceeds 5MB")
                (folder / identifier).write_bytes(path.read_bytes())
            rows[relative] = {"blob": identifier if path.exists() else None, "before": self.hash(path), "after": self.hash(path), "mode": (path.stat().st_mode & 0o777) if path.exists() else 0o600,
                              "owner": os.environ.get("M4_OWNER_SESSION", "terminal")}
            temporary = manifest.with_suffix(".tmp"); temporary.write_text(json.dumps(rows)); temporary.replace(manifest)
    def after(self, path, relative):
        manifest = self.path(self.group()) / "manifest.json"; rows = json.loads(manifest.read_text())
        rows[relative]["after"] = self.hash(path)
        temporary = manifest.with_suffix(".tmp"); temporary.write_text(json.dumps(rows)); temporary.replace(manifest)
    def listing(self, args):
        owner=os.environ.get('M4_OWNER_SESSION','terminal')
        result=[]
        for p in sorted(self.folder.glob('*/manifest.json'),key=lambda p:p.stat().st_mtime,reverse=True):
            rows=json.loads(p.read_text())
            meta=json.loads((p.parent/'meta.json').read_text()) if (p.parent/'meta.json').exists() else {'owner':next(iter(rows.values()),{}).get('owner','terminal')}
            if meta.get('owner')==owner: result.append({'checkpoint_id':p.parent.name,'paths':list(rows)})
        return result[:50]
    def rewind(self, args):
        self.runtime._permission(mutation=True)
        folder = self.path(args["id"]); manifest = folder / "manifest.json"
        if not manifest.is_file(): raise ValueError("Checkpoint not found")
        owner=os.environ.get('M4_OWNER_SESSION','terminal')
        def meta(p):
            file=p.parent/'meta.json'
            return json.loads(file.read_text()) if file.exists() else {'owner':'terminal','created_at':p.stat().st_mtime}
        selected=meta(manifest)
        if selected['owner']!=owner: raise ValueError('Checkpoint belongs to another conversation')
        chain=sorted([p for p in self.folder.glob('*/manifest.json') if meta(p)['owner']==owner and meta(p)['created_at']>=selected['created_at']], key=lambda p:meta(p)['created_at'],reverse=True)
        final, expected, blobs = {}, {}, {}
        # Build the complete owner timeline first. A later M4 write is a valid
        # link; any unmatched edit is a conflict and causes no partial restore.
        for item in chain:
            rows=json.loads(item.read_text())
            for relative,row in rows.items():
                if relative not in expected:
                    expected[relative]=row['after']
                elif final[relative]['before'] != row['after']: raise ValueError('Checkpoint chain conflict: '+relative)
                final[relative]=row; blobs[relative]=item.parent
        targets=[]
        for relative, row in final.items():
            path = self.runtime._path(self.runtime.root, relative, write=True)
            if self.hash(path) != expected[relative]: raise ValueError("Rewind conflict: " + relative + " was edited outside this checkpoint")
            blob = row["blob"]
            if blob is not None:
                if not isinstance(blob, str) or not all(c in "0123456789abcdef" for c in blob) or len(blob) != 32: raise ValueError("Invalid checkpoint blob")
                original = blobs[relative] / blob
                if self.hash(original) != row["before"]: raise ValueError("Checkpoint hash mismatch")
            targets.append((path, row, relative))
        # Verify every conflict before restoring the first file.
        for path, row, relative in targets:
            self.runtime.backup(self.runtime.state, path, relative)
            if row["blob"] is None: path.unlink(missing_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True); temporary = path.with_name(".rewind-" + uuid.uuid4().hex)
                temporary.write_bytes((blobs[relative] / row["blob"]).read_bytes()); temporary.chmod(row.get("mode", 0o600)); temporary.replace(path)
            self.runtime.reads.pop(str(path), None)
        for item in chain: item.rename(item.with_name('rewound.json'))
        return {"status": "rewound", "checkpoint_id": args["id"], "paths": list(final), 'groups':[p.parent.name for p in chain]}
