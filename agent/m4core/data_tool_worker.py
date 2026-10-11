"""Bounded short-lived computation worker; no filesystem or network builtins."""
import builtins
import json
from pathlib import Path
import resource
import sys
resource.setrlimit(resource.RLIMIT_CPU, (2, 2))
resource.setrlimit(resource.RLIMIT_AS, (128 * 1024 * 1024, 128 * 1024 * 1024))
sys.path.insert(0, str(Path(__file__).parent.parent))
from m4core.extensions import validate_source, ALLOWED_IMPORTS
def import_data(name, globals=None, locals=None, fromlist=(), level=0):
    if name not in ALLOWED_IMPORTS or level: raise ImportError("Unavailable computation module")
    return __import__(name, globals, locals, fromlist, 0)
tree = validate_source(Path(sys.argv[1]).read_text())
scope = {"__builtins__": {n: getattr(builtins, n) for n in ("abs", "all", "any", "bool", "dict", "enumerate", "float", "int", "len", "list", "max", "min", "range", "round", "set", "sorted", "str", "sum", "tuple", "zip")}}
scope["__builtins__"]["__import__"] = import_data
exec(compile(tree, "tested-data-tool", "exec"), scope)
print(json.dumps(scope["run"](json.load(sys.stdin)), ensure_ascii=False))
