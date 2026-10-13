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
    pending, emitted = "", time.monotonic()
    source = iter(lambda: response.readline(100001), b"") if callable(getattr(response, "readline", None)) else iter(response)
    deadline = time.monotonic() + 150
    for raw in source:
        if time.monotonic() > deadline: raise ValueError("Provider stream deadline exceeded")
        seen += len(raw)
        if seen > 1500000 or len(raw) > 100000: raise ValueError("Provider stream exceeds budget")
        line = raw.decode("utf-8") if isinstance(raw, bytes) else raw
        if not line.startswith("data:"): continue
        data = line[5:].strip()
        if data == "[DONE]": break
        if not data: continue
        row = json.loads(data)
        if row.get("model"): reply["reported_model"] = row["model"]
        if row.get("error"): raise ValueError(str(row["error"])[:200])
        if row.get("usage"): usage = row["usage"]
        for choice in row.get("choices", []):
            delta = choice.get("delta", {})
            if delta.get("content"):
                reply["content"] += delta["content"]
                pending += delta["content"]
                if on_event and (len(pending)>=128 or time.monotonic()-emitted>=.25):
                    on_event("token", {"text": pending}); pending=""; emitted=time.monotonic()
            for part in delta.get("tool_calls", []):
                item = calls.setdefault(part.get("index", 0), {"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
                if part.get("id"): item["id"] += part["id"]
                for key in ("name", "arguments"): item["function"][key] += part.get("function", {}).get(key, "")
    if calls: reply["tool_calls"] = [calls[i] for i in sorted(calls)]
    if pending and on_event: on_event("token", {"text": pending})
    return reply, usage

def vision_probe(route, health):
    """Use actual image bytes with a known answer before routing user images."""
    import base64, struct, zlib, urllib.request
    row=health.rows.get(route.name,{})
    if row.get('vision_checked_at',0)>time.time()-1800: return row.get('vision_verified') is True
    def chunk(name,data): return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data)&0xffffffff)
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',2,2,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\0'+b'\0\0\xff'*2+b'\0'+b'\0\0\xff'*2))+chunk(b'IEND',b'')
    body={'model':route.model,'stream':False,'messages':[{'role':'user','content':[{'type':'text','text':'Identify the dominant image color. Reply exactly with one English color word.'},{'type':'image_url','image_url':{'url':'data:image/png;base64,'+base64.b64encode(png).decode()}}]}]}
    try:
        from provider_routes import headers
        request=urllib.request.Request(route.base_url.rstrip('/')+'/chat/completions',json.dumps(body).encode(),headers(route),method='POST')
        with urllib.request.urlopen(request,timeout=12) as response:
            data=response.read(100001)
            if len(data)>100000: raise ValueError('Vision probe oversized')
            answer=json.loads(data)['choices'][0]['message']['content'].strip().lower().strip('.!')
            verified=answer=='blue'
    except (OSError,ValueError,KeyError,IndexError,TypeError): verified=False
    row=health.rows.setdefault(route.name,{})
    row.update(vision_verified=verified,vision_checked_at=time.time())
    try: health.path.parent.mkdir(parents=True,exist_ok=True); health.path.write_text(json.dumps(health.rows))
    except OSError: pass
    return verified
