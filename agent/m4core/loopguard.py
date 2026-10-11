"""Detects unproductive loops (e.g. repeated workspace inspection)."""
import hashlib
import json
from collections import deque

READ_ONLY = {"Read", "Glob", "Grep", "ListDir", "git_status", "list_files",
             "read_file", "search_code", "BashOutput", "TodoWrite"}


class LoopGuard:
    def __init__(self, window=12, repeat_limit=3, readonly_limit=8):
        self.recent = deque(maxlen=window)
        self.repeat_limit, self.readonly_limit = repeat_limit, readonly_limit
        self.readonly_streak = 0
        self.warned = False

    @staticmethod
    def signature(name, args):
        return hashlib.sha256(json.dumps([name, args], sort_keys=True,
                                         default=str).encode()).hexdigest()[:16]

    def record(self, name, args, changed_files=False):
        """Return 'ok' | 'warn' | 'stop'."""
        sig = self.signature(name, args)
        self.recent.append(sig)
        if changed_files or name not in READ_ONLY:
            self.readonly_streak = 0
        else:
            self.readonly_streak += 1
        repeats = sum(1 for s in self.recent if s == sig)
        bad = repeats >= self.repeat_limit or self.readonly_streak >= self.readonly_limit
        if not bad:
            return "ok"
        if not self.warned:
            self.warned = True
            self.recent.clear()
            self.readonly_streak = 0
            return "warn"
        return "stop"

    ADVICE = ("You are repeating actions without progress. Stop inspecting. "
              "Pick the smallest concrete edit toward the goal and make it now, "
              "or report the exact blocker.")
