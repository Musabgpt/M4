"""Provider router: providers.d catalog, health, circuit breaker, capability cache.

Security rules are inherited from provider_routes.allowed(): HTTPS for remote,
loopback-only HTTP for local, no credentials/query/fragment in URLs. Keyed
providers are only used when their secret is present in the environment (the
Android app injects it from the Keystore); secrets are never read from files.
"""
import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

TOOL_RANK = {"native": 0, "prompted": 1, "unknown": 2, "none": 9}


@dataclass
class Route:
    id: str
    family: str
    name: str
    base_url: str
    model: str
    local: bool = False
    auth_env: str = ""          # env var holding the key; "" = keyless
    tools: str = "unknown"      # native | prompted | none | unknown
    enabled: bool = True
    verified: str = ""          # date limits were last checked ("" = never)
    privacy: str = "unknown"


def allowed(route):
    u = urlsplit(route.base_url)
    if u.username or u.password or u.query or u.fragment:
        return False
    if route.local:
        return u.scheme == "http" and u.hostname in ("127.0.0.1", "localhost", "::1")
    return u.scheme == "https"


def load_catalog(folder):
    routes, seen = [], set()
    for f in sorted(Path(folder).glob("*.json")):
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        for item in (data if isinstance(data, list) else [data]):
            try:
                r = Route(**{k: item[k] for k in Route.__dataclass_fields__ if k in item})
            except TypeError:
                continue
            if r.id in seen or not allowed(r):
                continue
            seen.add(r.id)
            routes.append(r)
    return routes


@dataclass
class Health:
    fail_streak: int = 0
    open_until: float = 0.0
    last_error: str = ""
    latency: float = 0.0
    ok_count: int = 0


@dataclass
class Router:
    routes: list
    breaker_threshold: int = 3
    breaker_base: float = 30.0
    clock: object = time.monotonic
    environ: dict = field(default_factory=lambda: os.environ)
    health: dict = field(default_factory=dict)
    caps: dict = field(default_factory=dict)

    def _h(self, rid):
        return self.health.setdefault(rid, Health())

    def usable(self, r):
        if not (r.enabled and allowed(r)):
            return False
        if r.auth_env and not self.environ.get(r.auth_env):
            return False
        return self._h(r.id).open_until <= self.clock()

    def tools_mode(self, r):
        return self.caps.get(r.id, r.tools)

    def set_capability(self, rid, mode):
        if mode in TOOL_RANK:
            self.caps[rid] = mode

    def order(self, family="freellmpool", require_tools=True):
        cand = [r for r in self.routes if self.usable(r)
                and not (require_tools and self.tools_mode(r) == "none")]

        def key(r):
            h = self._h(r.id)
            return (0 if r.family == family else 1, TOOL_RANK[self.tools_mode(r)],
                    h.fail_streak, h.latency or 9.9)
        return sorted(cand, key=key)

    def record(self, rid, ok, latency=0.0, error=""):
        h = self._h(rid)
        if ok:
            h.fail_streak, h.open_until, h.last_error = 0, 0.0, ""
            h.ok_count += 1
            h.latency = latency if not h.latency else 0.7 * h.latency + 0.3 * latency
            return
        h.fail_streak += 1
        h.last_error = str(error)[:160]
        if h.fail_streak >= self.breaker_threshold:
            over = h.fail_streak - self.breaker_threshold
            h.open_until = self.clock() + min(self.breaker_base * (2 ** over), 1800)

    def status(self):
        return [{"id": r.id, "family": r.family, "tools": self.tools_mode(r),
                 "usable": self.usable(r), "fail_streak": self._h(r.id).fail_streak,
                 "last_error": self._h(r.id).last_error, "verified": r.verified or "never"}
                for r in self.routes]
