"""Owner-bound persistent shell handles; stale PIDs are never signaling targets."""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid
import threading

class ShellPool:
    def __init__(self, root, commands, state):
        self.root, self.commands = root, commands
        self.folder = state/'shells'; self.folder.mkdir(parents=True, exist_ok=True)
        self.owner = os.environ.get('M4_OWNER_SESSION', 'terminal')
        self.jobs, self.cursors = {}, {}
    def start(self, args):
        from .runtime import child_environment
        argv = shlex.split(args['command'])
        if not argv or '/' in argv[0] or argv[0] not in self.commands: raise ValueError('Executable not allowed')
        identifier = uuid.uuid4().hex; folder = self.folder/identifier; folder.mkdir(mode=0o700)
        timeout = args.get('timeout',90)
        (folder/'request.json').write_text(json.dumps(dict(owner=self.owner, root=str(self.root), argv=argv, timeout=timeout, stdin=args.get('stdin'))))
        worker = Path(__file__).with_name('shell_worker.py')
        process = subprocess.Popen([sys.executable, str(worker), str(folder)], cwd=self.root,
            env=child_environment(), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True, close_fds=True)
        self.jobs[identifier] = (process, args.get('background',False))
        if args.get('background', False): threading.Thread(target=process.wait, daemon=True).start()
        end = time.monotonic()+3
        while not (folder/'state.json').is_file() and process.poll() is None and time.monotonic()<end: time.sleep(.02)
        if not args.get('background',False):
            try: process.wait(timeout=timeout+5)
            except subprocess.TimeoutExpired: (folder/'cancel').touch()
        return self.output(identifier)
    def _folder(self, identifier):
        if not re.fullmatch('[a-f0-9]{32}', identifier): raise ValueError('Unknown shell session')
        folder = self.folder/identifier
        if not (folder/'request.json').is_file(): raise ValueError('Unknown shell session')
        config = json.loads((folder/'request.json').read_text())
        if config['owner'] != self.owner: raise ValueError('Shell belongs to another conversation')
        return folder
    @staticmethod
    def alive(row):
        from .shell_worker import start_ticks
        return row.get('worker_start') is not None and start_ticks(row['worker_pid']) == row['worker_start']
    def output(self, identifier):
        folder = self._folder(identifier)
        state = folder/'state.json'
        if not state.is_file(): raise ValueError('Shell worker failed before readiness')
        row = json.loads(state.read_text())
        if row['status'] == 'running' and not self.alive(row): row.update(status='interrupted', error='Command monitor is no longer alive; no automatic replay', ended_at=time.time())
        result = {k:v for k,v in row.items() if k not in {'owner','worker_pid','worker_start'}}
        cursors = self.cursors.setdefault(identifier, {'stdout':0,'stderr':0})
        for field in ('stdout','stderr'):
            path = folder/field
            data = path.read_bytes() if path.is_file() else b''
            end = len(data)
            # Do not split an incomplete UTF-8 sequence while the process runs.
            tail = data[cursors[field]:]
            try: decoded = tail.decode('utf-8')
            except UnicodeDecodeError as error:
                if row['status'] == 'running' and error.reason == 'unexpected end of data':
                    end = cursors[field]+error.start; decoded = data[cursors[field]:end].decode('utf-8','replace')
                else: decoded = tail.decode('utf-8','replace')
            result[field] = decoded; cursors[field] = end
        process = self.jobs.get(identifier, (None,))[0]
        if process and row['status'] != 'running':
            try: process.wait(timeout=.1)
            except subprocess.TimeoutExpired: pass
        return dict(result, shell_id=identifier, command_id=identifier, backend='alpine' if Path('/etc/alpine-release').exists() else 'host')
    def kill(self, identifier):
        folder = self._folder(identifier); row = json.loads((folder/'state.json').read_text())
        if row['status'] == 'running' and self.alive(row):
            # The authenticated monitor reads a cancel marker and signals only
            # its own live subprocess group. Never kill a recovered naked PID.
            (folder/'cancel').touch(); end=time.monotonic()+4
            while time.monotonic()<end and json.loads((folder/'state.json').read_text())['status']=='running': time.sleep(.03)
        return self.output(identifier)
    def close(self):
        for identifier, (process, background) in self.jobs.items():
            if not background and process.poll() is None: self.kill(identifier)
        # Background monitors retain their pipes and receipts after a turn ends.
