"""Detached command monitor: owns pipes, deadlines, cancellation and receipts."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time

CAP = 64000
def save(path, value):
    temporary = path.with_suffix('.tmp'); temporary.write_text(json.dumps(value)); temporary.replace(path)
def start_ticks(pid):
    try: return Path('/proc/' + str(pid) + '/stat').read_text().rsplit(')', 1)[1].split()[19]
    except (OSError, IndexError): return None
def main(folder):
    config = json.loads((folder/'request.json').read_text())
    row = {"owner": config["owner"], "worker_pid": os.getpid(), "worker_start": start_ticks(os.getpid()),
        "status": "running", "started_at": time.time(), "ended_at": None, "exit_code": None, "truncated": False}
    process = None
    readers = []
    def stopped(*_): (folder/'cancel').touch()
    signal.signal(signal.SIGTERM, stopped); signal.signal(signal.SIGINT, stopped)
    def terminate():
        if process is None: return
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=1)
        except subprocess.TimeoutExpired: os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=1)
        except ProcessLookupError: pass
    try:
        process = subprocess.Popen(config["argv"], cwd=config["root"], stdin=subprocess.PIPE if config.get('stdin') is not None else subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, start_new_session=True)
        if config.get('stdin') is not None:
            def write_input():
                try: process.stdin.write(config['stdin'].encode()); process.stdin.flush()
                except (BrokenPipeError, OSError): pass
                finally: process.stdin.close()
            threading.Thread(target=write_input, daemon=True).start()
        save(folder/'state.json', row)
        def reader(stream, field):
            count = 0
            with (folder/field).open('wb') as output:
                while True:
                    data = os.read(stream.fileno(), 4096)
                    if not data: break
                    remaining = max(0, CAP-count); output.write(data[:remaining]); output.flush(); count += len(data)
                    if count > CAP: row['truncated'] = True
            stream.close()
        for field in ('stdout', 'stderr'):
            thread = threading.Thread(target=reader, args=(getattr(process, field),field), daemon=True)
            thread.start(); readers.append(thread)
        end = time.monotonic() + config['timeout']
        while process.poll() is None:
            if (folder/'cancel').exists(): row['status'] = 'canceled'; terminate(); break
            if time.monotonic() >= end: row['status'] = 'timed_out'; terminate(); break
            time.sleep(.04)
        if row['status'] == 'running': row['status'] = 'succeeded' if process.returncode == 0 else 'failed'
        # Reap a descendant that retained inherited stdout/stderr after its parent
        # exited. Never leave an untracked child holding the monitor's pipes.
        for thread in readers: thread.join(.5)
        if any(thread.is_alive() for thread in readers): terminate()
        row['exit_code'] = process.returncode
    except Exception as error:
        row.update(status='failed', error=str(error)[:500]); terminate()
    finally:
        row['ended_at'] = time.time(); save(folder/'state.json', row)
if __name__ == '__main__': main(Path(sys.argv[1]))
