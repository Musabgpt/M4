"""Persistent measured provider health; unknown usage and prices stay unknown."""
import json
import os
from pathlib import Path
import time
import uuid

class ProviderHealth:
    def __init__(self, path=None):
        self.path = Path(path or os.environ.get("M4_PROVIDER_HEALTH", "/root/.m4/providers-health.json"))
        try: self.rows = json.loads(self.path.read_text())
        except (OSError, ValueError): self.rows = {}
    def available(self, key): return self.rows.get(key, {}).get("retry_at", 0) <= time.time()
    def record(self, key, ok, latency, model, tool_mode="", usage=None, error="", retry_after=0):
        row = self.rows.setdefault(key, {})
        failures = 0 if ok else row.get("consecutive_failures", 0) + 1
        row.update(healthy=ok, checked_at=time.time(), latency_ms=round(latency * 1000), actual_model=model,
                   tool_mode=tool_mode, usage=usage if isinstance(usage, dict) else None, cost=None,
                   consecutive_failures=failures, error=str(error)[:240],
                   retry_at=time.time() + (retry_after or 60) if not ok and (retry_after or failures >= 3) else 0)
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temp = self.path.with_name(".health-" + uuid.uuid4().hex)
            temp.write_text(json.dumps(self.rows, ensure_ascii=False)); temp.replace(self.path)
        except OSError: pass

def streamed_reply(response, on_event=None):
    """Collect native SSE calls by index while forwarding actual text deltas."""
    reply, calls, usage, seen = {"role": "assistant", "content": ""}, {}, None, 0
    for raw in response:
        seen += len(raw)
        if seen > 1500000 or len(raw) > 100000: raise ValueError("Provider stream exceeds budget")
        line = raw.decode("utf-8") if isinstance(raw, bytes) else raw
        if not line.startswith("data:"): continue
        data = line[5:].strip()
        if data == "[DONE]": break
        if not data: continue
        row = json.loads(data)
        if row.get("error"): raise ValueError(str(row["error"])[:200])
        if row.get("usage"): usage = row["usage"]
        for choice in row.get("choices", []):
            delta = choice.get("delta", {})
            if delta.get("content"):
                reply["content"] += delta["content"]
                if on_event: on_event("token", {"text": delta["content"]})
            for part in delta.get("tool_calls", []):
                item = calls.setdefault(part.get("index", 0), {"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
                if part.get("id"): item["id"] += part["id"]
                for key in ("name", "arguments"): item["function"][key] += part.get("function", {}).get(key, "")
    if calls: reply["tool_calls"] = [calls[i] for i in sorted(calls)]
    return reply, usage
