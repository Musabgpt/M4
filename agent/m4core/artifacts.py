"""Immutable real files, format validation and persistent published manifests."""
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import zipfile

TEXT_FORMATS = {"md", "txt", "html", "htm", "py", "sh", "json", "jsonl", "yaml",
                "yml", "xml", "css", "js", "ts", "tsx", "jsx", "java", "kt", "csv",
                "sql", "svg", "log", "diff", "patch", "ipynb"}
MIME = {"md": "text/markdown", "py": "text/x-python", "sh": "text/x-shellscript",
        "json": "application/json", "html": "text/html", "txt": "text/plain",
        "zip": "application/zip", "pdf": "application/pdf"}
MAX_SIZE = 25_000_000


class ArtifactStore:
    def list_published(self):
        return [self.inspect(p.parent.name) for p in self.folder.glob("*/manifest.json")
                if json.loads(p.read_text()).get("published") and json.loads(p.read_text()).get("owner_session_id") == os.environ.get("M4_OWNER_SESSION", "terminal")]
    def __init__(self, root, state, safe_path, bridge=None):
        self.root, self.state, self.safe_path, self.bridge = root, state, safe_path, bridge
        self.folder = state / "artifacts"
        self.folder.mkdir(exist_ok=True, mode=0o700)

    @staticmethod
    def valid_name(name, extension):
        if not isinstance(name, str) or not name or len(name) > 160 or any(
                c in name for c in ("/", "\\", "\x00")) or name.startswith("."):
            raise ValueError("Invalid artifact name")
        if not name.lower().endswith("." + extension):
            name += "." + extension
        if name == "manifest.json":
            raise ValueError("Reserved artifact metadata filename")
        return name

    def _target(self, name, extension):
        name = self.valid_name(name, extension)
        identifier = uuid.uuid4().hex
        directory = self.folder / identifier
        directory.mkdir(mode=0o700)
        return identifier, directory / name

    def _record(self, identifier, path, extension, validation, published=False):
        data = path.read_bytes()
        if len(data) > MAX_SIZE or not data:
            raise ValueError("Artifact must contain 1–25 MB of real data")
        path.chmod(0o600)
        row = {"artifact_id": identifier, "owner_session_id": os.environ.get("M4_OWNER_SESSION", "terminal"),
               "task_id": os.environ.get("M4_TASK_ID", ""), "display_name": path.name,
               "mime": MIME.get(extension, mimetypes.guess_type(path.name)[0] or "text/plain"),
               "size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
               "created_at": time.time(), "source_path": str(path.relative_to(self.root)),
               "verified_path": str(path), "validation": validation, "published": published,
               "preview_available": extension in TEXT_FORMATS or extension in {"pdf", "zip"}}
        self._save(row)
        return row

    def _save(self, row):
        path = self.folder / row["artifact_id"] / "manifest.json"
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(row, ensure_ascii=False), encoding="utf-8")
        temporary.chmod(0o600)
        temporary.replace(path)

    def inspect(self, identifier):
        if not isinstance(identifier, str) or not re.fullmatch(r"[a-f0-9]{32}", identifier):
            raise ValueError("Invalid artifact id")
        directory = self.folder / identifier
        row = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        path = directory / row["display_name"]
        if path.is_symlink() or path.resolve().parent != directory.resolve():
            raise ValueError("Unsafe artifact path")
        if not path.is_file() or path.stat().st_size != row["size_bytes"] or path.stat().st_size > MAX_SIZE:
            raise ValueError("Artifact size mismatch")
        if hashlib.sha256(path.read_bytes()).hexdigest() != row["sha256"]:
            raise ValueError("Artifact hash mismatch")
        return row

    def publish(self, identifier):
        row = self.inspect(identifier)
        row["published"] = True
        self._save(row)
        return row

    def create(self, args):
        extension = args["format"].lower().lstrip(".")
        if extension not in TEXT_FORMATS | {"pdf"}:
            raise ValueError("Unsupported creator; use ArtifactBundle for ZIP")
        if ("content" in args) == ("source_path" in args):
            raise ValueError("Provide content or source_path, exactly one")
        content = args.get("content")
        if "source_path" in args:
            source = self.safe_path(self.root, args["source_path"])
            if source.stat().st_size > 500000:
                raise ValueError("Source text too large")
            content = source.read_text(encoding="utf-8")
        if not isinstance(content, str) or not content or "\x00" in content or len(content.encode()) > 500000:
            raise ValueError("Invalid artifact text")
        identifier, path = self._target(args["name"], extension)
        try:
            validation = {"status": "passed", "checks": ["utf8", "size", "sha256"]}
            if extension == "pdf":
                if self.bridge is None:
                    raise ValueError("Arabic PDF requires the registered Android PDF renderer")
                report = self.bridge.request("artifact_pdf", {"id": identifier, "name": path.name,
                           "content": content, "owner_session_id": os.environ.get("M4_OWNER_SESSION", "terminal")})
                if not path.is_file() or not path.read_bytes().startswith(b"%PDF-") or report.get("pages", 0) < 1:
                    raise ValueError("Android PDF result failed independent validation")
                validation["checks"] = ["pdf_magic", "android_pdf_renderer", "sha256"]
                validation["pages"] = report["pages"]
            else:
                if extension in {"json", "ipynb"}:
                    json.loads(content)
                    validation["checks"].append("json_parse")
                elif extension == "jsonl":
                    for line in content.splitlines():
                        json.loads(line)
                    validation["checks"].append("jsonl_parse")
                elif extension == "py":
                    compile(content, path.name, "exec")
                    validation["checks"].append("python_compile")
                path.write_text(content, encoding="utf-8")
                if extension == "sh":
                    result = subprocess.run(["sh", "-n", str(path)], capture_output=True, timeout=10)
                    if result.returncode:
                        raise ValueError("Shell syntax failed: " + result.stderr.decode(errors="replace")[:240])
                    validation["checks"].append("sh_syntax")
            return self._record(identifier, path, extension, validation, args.get("publish_to_chat", True))
        except BaseException:
            path.unlink(missing_ok=True)
            path.parent.rmdir()
            raise

    def bundle(self, args):
        identifier, path = self._target(args["name"], "zip")
        try:
            return self._bundle(args, identifier, path)
        except BaseException:
            path.unlink(missing_ok=True)
            path.parent.rmdir()
            raise

    def _bundle(self, args, identifier, path):
        sources = []
        for relative in args.get("paths", []):
            source = self.safe_path(self.root, relative)
            if source.is_file() and not source.is_symlink():
                sources.append((source, relative))
            else:
                raise ValueError("Bundle explicit regular files only")
        for artifact_id in args.get("ids", []):
            row = self.inspect(artifact_id)
            sources.append((Path(row["verified_path"]), row["display_name"]))
        if not sources or len(sources) > 100 or sum(p.stat().st_size for p, _ in sources) > MAX_SIZE:
            raise ValueError("Invalid bundle size")
        names = set()
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for source, name in sources:
                name = str(Path(name))
                if name in names or Path(name).is_absolute() or ".." in Path(name).parts:
                    raise ValueError("Unsafe or duplicate ZIP member")
                names.add(name)
                archive.write(source, name)
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError("ZIP integrity check failed")
        return self._record(identifier, path, "zip", {"status": "passed", "checks": ["zip_crc", "sha256"],
                            "members": sorted(names)}, args.get("publish_to_chat", True))
