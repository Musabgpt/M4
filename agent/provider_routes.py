"""Fixed M4 gateway catalog. No custom URLs, local-model import or API key entry."""
from dataclasses import dataclass
from urllib.parse import urlsplit

@dataclass(frozen=True)
class Route:
    family: str
    name: str
    base_url: str
    model: str
    local: bool = False

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

def allowed(route):
    u=urlsplit(route.base_url)
    return bool(not u.username and not u.password and not u.query and not u.fragment
                and ((route.local and u.scheme=="http" and u.hostname in ("127.0.0.1","localhost","::1"))
                     or (not route.local and u.scheme=="https")))

def order(family="freellmpool"):
    selected=family if family in FAMILIES else "freellmpool"
    return (tuple(r for r in ROUTES if r.family==selected and allowed(r))
            + tuple(r for r in ROUTES if r.family=="freellmpool" and r.family!=selected and allowed(r)))
