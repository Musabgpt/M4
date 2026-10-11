"""Bash / BashOutput / KillShell: foreground and background command sessions.

Every command runs in its own process group so a timeout or KillShell takes the
whole tree down. Provider tokens/secrets are scrubbed from the child env.
This is NOT a sandbox.
"""
import os
import shutil
import signal
import subprocess
import threading
import time

SECRET_SUFFIXES = ("_KEY", "_TOKEN", "_SECRET", "_PASSWORD")
SECRET_NAMES = {"AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "GH_TOKEN", "GITHUB_TOKEN"}
SECRET_PREFIXES = ("M4_KEY_",)   # provider keys injected by the app
MAX_BUFFER = 400_000


def scrub_env(env=None):
    env = dict(os.environ if env is None else env)
    return {k: v for k, v in env.items()
            if not (k.upper().endswith(SECRET_SUFFIXES) or k.upper() in SECRET_NAMES
                    or k.upper().startswith(SECRET_PREFIXES))}


def truncate(text, limit=12000):
    """Keep head and tail; say how much was dropped."""
    if len(text) <= limit:
        return text
    half = limit // 2
    dropped = text[half:-half].count("\n")
    return text[:half] + "\n... [%d lines truncated] ...\n" % dropped + text[-half:]


class _Session:
    def __init__(self, proc, command):
        self.proc, self.command = proc, command
        self.buf, self.cursor = [], 0
        self.size, self.started = 0, time.time()
        self.lock = threading.Lock()
        self.reader = threading.Thread(target=self._pump, daemon=True)
        self.reader.start()

    def _pump(self):
        for line in iter(self.proc.stdout.readline, ""):
            with self.lock:
                if self.size < MAX_BUFFER:
                    self.buf.append(line)
                    self.size += len(line)
        self.proc.stdout.close()

    def read_new(self):
        with self.lock:
            out = "".join(self.buf[self.cursor:])
            self.cursor = len(self.buf)
        return out


class ShellManager:
    def __init__(self, cwd, shell=None):
        self.cwd = str(cwd)
        self.shell = shell or shutil.which("bash") or shutil.which("sh") or "/bin/sh"
        self.sessions, self._n = {}, 0

    def _spawn(self, command):
        return subprocess.Popen([self.shell, "-c", command], cwd=self.cwd, text=True,
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, env=scrub_env(),
                                start_new_session=True)

    @staticmethod
    def _kill(proc):
        try:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(2)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass

    def run(self, command, timeout=120, background=False):
        if not isinstance(command, str) or not command.strip():
            return {"error": "empty command"}
        timeout = max(1, min(int(timeout or 120), 1800))
        proc = self._spawn(command)
        s = _Session(proc, command)
        if background:
            self._n += 1
            sid = "sh%d" % self._n
            self.sessions[sid] = s
            return {"shell_id": sid, "status": "running"}
        try:
            proc.wait(timeout)
            timed_out = False
        except subprocess.TimeoutExpired:
            self._kill(proc)
            timed_out = True
        s.reader.join(2)
        out = truncate(s.read_new())
        return {"exit_code": None if timed_out else proc.returncode,
                "timed_out": timed_out, "output": out}

    def output(self, shell_id):
        s = self.sessions.get(shell_id)
        if not s:
            return {"error": "unknown shell_id"}
        code = s.proc.poll()
        return {"shell_id": shell_id, "status": "running" if code is None else "exited",
                "exit_code": code, "new_output": truncate(s.read_new())}

    def kill(self, shell_id):
        s = self.sessions.get(shell_id)
        if not s:
            return {"error": "unknown shell_id"}
        self._kill(s.proc)
        return {"shell_id": shell_id, "status": "killed"}

    def close_all(self):
        for s in self.sessions.values():
            self._kill(s.proc)
