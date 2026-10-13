"""Fixed M4 gateway catalog. No custom URLs, local-model import or API key entry."""
from dataclasses import dataclass
from urllib.parse import urlsplit
import json
import os
from pathlib import Path

@dataclass(frozen=True)
class Route:
    family: str
    name: str
    base_url: str
    model: str
    local: bool = False
    auth_env: str = ""

ROUTES = (
    Route("freellmpool","Kilo Auto Free","https://api.kilo.ai/api/gateway","kilo-auto/free"),
    Route("freellmpool","LLM7 Free","https://api.llm7.io/v1","default"),
    Route("freellmpool","Pollinations Free","https://text.pollinations.ai/openai","openai-fast"),
    Route("freellmpool","OVHcloud Free","https://oai.endpoints.kepler.ai.cloud.ovh.net/v1","Qwen3-Coder-30B-A3B-Instruct"),
    Route("gpt4free","GPT4Free local API","http://127.0.0.1:1337/v1","auto",True),
    Route("free-llm-gateway","MrFadiAi local gateway","http://127.0.0.1:8080/v1","auto",True),
    Route("free-llm-api","PryxIntel local gateway","http://127.0.0.1:3001/v1","auto",True),
)
FAMILIES = ("freellmpool","gpt4free","free-llm-gateway","free-llm-api")
KEYED_ROUTES = (
    Route("freellmpool", "OpenAI configured", "https://api.openai.com/v1", "gpt-4.1-mini", False, "OPENAI_API_KEY"),
    Route("freellmpool", "Groq configured", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", False, "GROQ_API_KEY"),
)

def catalog(directory=None):
    """Ship vetted JSON profiles; mutable profiles cannot redirect credentials."""
    directory = Path(directory) if directory is not None else Path(__file__).with_name("providers.d")
    pins = {r.name: r for r in ROUTES + KEYED_ROUTES}
    selected = []
    for path in sorted(directory.glob("*.json")):
        if path.stat().st_size > 20000: continue
        try:
            profiles = json.loads(path.read_text())
            if not isinstance(profiles, list): continue
            for profile in profiles:
                route = Route(**profile)
                if pins.get(route.name) != route or route in selected: continue
                if route.auth_env and not os.environ.get(route.auth_env): continue
                selected.append(route)
        except (OSError, ValueError, TypeError): continue
    # Backward-compatible bundles retain the pinned keyless catalog.
    return tuple(selected) if selected else ROUTES

def headers(route):
    result = {"Content-Type": "application/json"}
    if route.auth_env:
        value = os.environ.get(route.auth_env, "")
        if not value or any(c in value for c in "\r\n"): raise ValueError("Configured provider credential is unavailable")
        result["Authorization"] = "Bearer " + value
    return result

def allowed(route):
    u=urlsplit(route.base_url)
    return bool(not u.username and not u.password and not u.query and not u.fragment
                and ((route.local and u.scheme=="http" and u.hostname in ("127.0.0.1","localhost","::1"))
                     or (not route.local and u.scheme=="https")))

def order(family="freellmpool"):
    selected=family if family in FAMILIES else "freellmpool"
    routes = catalog()
    return (tuple(r for r in routes if r.family==selected and allowed(r))
            + tuple(r for r in routes if r.family=="freellmpool" and r.family!=selected and allowed(r)))
