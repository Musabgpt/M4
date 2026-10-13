"""NewAl-Cloud durable memory port. Recalled text is data, never authorization."""
from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import subprocess
import threading
import time


def _clip(value, limit=8000):
    value = str(value or "")
    return value if len(value) <= limit else value[:limit] + "\n…"


def _safe_text(value, limit=8000):
    value = re.sub(r"(?i)(authorization\s*:\s*bearer\s+)[^\s]+", r"\1[redacted]", str(value or ""))
    value = re.sub(r"(?i)(api[_-]?key|token|password|secret)\s*[=:]\s*[^\s,;]+", r"\1=[redacted]", value)
    return _clip(value, limit)


class MemoryStore:
    """SQLite/WAL memory which survives app restarts and is safe for concurrent workers."""

    def __init__(self, root: str):
        self.root = os.path.abspath(root)
        base = os.environ.get("M4_MEMORY_HOME") or os.path.join(
            os.environ.get("M4_CONFIG_HOME") or os.path.expanduser("~/.m4"), "memory")
        os.makedirs(base, exist_ok=True)
        digest = hashlib.sha256(self.root.encode("utf-8")).hexdigest()[:16]
        self.path = os.path.join(base, digest + ".sqlite3")
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.path, check_same_thread=False, timeout=30)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=NORMAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS episodes(
          id INTEGER PRIMARY KEY, ts REAL NOT NULL, kind TEXT NOT NULL,
          session_id TEXT, task TEXT, summary TEXT, error TEXT, evidence TEXT
        );
        CREATE TABLE IF NOT EXISTS learnings(
          id INTEGER PRIMARY KEY, ts REAL NOT NULL, topic TEXT NOT NULL,
          lesson TEXT NOT NULL, evidence TEXT, hits INTEGER NOT NULL DEFAULT 0,
          last_used REAL
        );
        CREATE TABLE IF NOT EXISTS mutations(
          id INTEGER PRIMARY KEY, ts REAL NOT NULL, repo TEXT NOT NULL,
          base_ref TEXT, candidate_ref TEXT, goal TEXT, checks TEXT,
          result TEXT, promoted INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS idx_learnings_topic ON learnings(topic);
        CREATE INDEX IF NOT EXISTS idx_episodes_task ON episodes(task);
        """)
        self.db.commit()

    def episode(self, kind, session_id="", task="", summary="", error="", evidence=""):
        with self.lock:
            self.db.execute(
                "INSERT INTO episodes(ts,kind,session_id,task,summary,error,evidence) VALUES(?,?,?,?,?,?,?)",
                (time.time(), kind, str(session_id), _safe_text(task), _safe_text(summary),
                 _safe_text(error), _safe_text(evidence)))
            self.db.commit()

    def learn(self, topic, lesson, evidence=""):
        topic = _safe_text(topic, 240)
        lesson = _safe_text(lesson, 4000)
        if not topic or not lesson:
            return
        with self.lock:
            row = self.db.execute(
                "SELECT id FROM learnings WHERE topic=? AND lesson=?", (topic, lesson)).fetchone()
            if row:
                self.db.execute("UPDATE learnings SET hits=hits+1,last_used=? WHERE id=?",
                                (time.time(), row[0]))
            else:
                self.db.execute(
                    "INSERT INTO learnings(ts,topic,lesson,evidence,hits,last_used) VALUES(?,?,?,?,1,?)",
                    (time.time(), topic, lesson, _safe_text(evidence), time.time()))
            self.db.commit()

    def recall(self, query="", limit=12):
        terms = [t.lower() for t in re.findall(r"[\w-]{3,}", query or "")[:8]]
        with self.lock:
            rows = self.db.execute(
                "SELECT topic,lesson,evidence,hits,last_used FROM learnings "
                "ORDER BY hits DESC,last_used DESC,ts DESC LIMIT 200").fetchall()
        if terms:
            rows = [r for r in rows if any(t in (r[0] + " " + r[1]).lower() for t in terms)] or rows
        return [{"topic":r[0],"lesson":r[1],"evidence":r[2],"hits":r[3],"last_used":r[4]}
                for r in rows[:max(1, min(int(limit), 50))]]

    def recent_failures(self, limit=10):
        with self.lock:
            rows = self.db.execute(
                "SELECT ts,task,error,evidence FROM episodes WHERE kind='failure' "
                "ORDER BY ts DESC LIMIT ?", (max(1, min(int(limit), 50)),)).fetchall()
        return [{"ts":r[0],"task":r[1],"error":r[2],"evidence":r[3]} for r in rows]

    def close(self):
        with self.lock:
            self.db.close()


