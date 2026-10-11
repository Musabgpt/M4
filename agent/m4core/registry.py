"""Strict schemas shared by native and prompted tool calling."""
import json
import uuid


def schema(kind, **extra):
    return dict(type=kind, **extra)


def tool(name, description, properties=None, required=()):
    return {"type": "function", "function": {"name": name,
            "description": description,
            "parameters": {"type": "object", "properties": properties or {},
                           "required": list(required), "additionalProperties": False}}}


class Registry:
    def __init__(self):
        self.entries = {}
        self.metadata = {}

    def register(self, descriptor, handler, metadata=None):
        name = descriptor["function"]["name"]
        if name in self.entries:
            raise ValueError("Duplicate tool: " + name)
        self.entries[name] = (descriptor, handler)
        self.metadata[name] = {"version": "1", "risk_level": "read", "capabilities": [],
            "requires_user_consent": False, "timeout": 90, "result_schema": {"type": ["object", "string"]}, **(metadata or {})}

    def unregister(self, name):
        self.entries.pop(name, None)
        self.metadata.pop(name, None)

    @property
    def schemas(self):
        return [entry[0] for entry in self.entries.values()]

    def validate(self, name, arguments):
        if name not in self.entries:
            raise ValueError("Unregistered tool: " + name)
        self._validate(arguments, self.entries[name][0]["function"]["parameters"], name)

    @classmethod
    def _validate(cls, value, specification, field):
        kind = specification.get("type")
        types = {"object": dict, "array": list, "string": str,
                 "integer": int, "boolean": bool, "number": (int, float)}
        if kind in types and (not isinstance(value, types[kind]) or
                (kind in ("integer", "number") and isinstance(value, bool))):
            raise ValueError("Invalid type for " + field)
        if "enum" in specification and value not in specification["enum"]:
            raise ValueError("Invalid value for " + field)
        if kind in ("integer", "number"):
            if value < specification.get("minimum", float("-inf")) or value > specification.get("maximum", float("inf")):
                raise ValueError("Out of range: " + field)
        if kind == "string" and len(value) > specification.get("maxLength", 500000):
            raise ValueError("Oversized field: " + field)
        if kind == "object":
            properties = specification.get("properties", {})
            if specification.get("additionalProperties") is False and set(value) - set(properties):
                raise ValueError("Unknown arguments for " + field)
            if set(specification.get("required", [])) - set(value):
                raise ValueError("Missing arguments for " + field)
            for key, item in value.items():
                if key in properties:
                    cls._validate(item, properties[key], field + "." + key)
        if kind == "array":
            if len(value) > specification.get("maxItems", 100):
                raise ValueError("Too many items: " + field)
            for item in value:
                cls._validate(item, specification.get("items", {}), field + "[]")

    def call(self, name, arguments):
        self.validate(name, arguments)
        return self.entries[name][1](arguments)


def prompted_calls(text, registry):
    """Accept only a complete JSON envelope, never executable prose or fences."""
    if not isinstance(text, str) or len(text) > 100000:
        raise ValueError("Invalid prompted response")
    payload = json.loads(text)
    if not isinstance(payload, dict) or set(payload) != {"tool_calls"}:
        raise ValueError("Expected a tool_calls envelope")
    calls = payload["tool_calls"]
    if not isinstance(calls, list) or not 1 <= len(calls) <= 4:
        raise ValueError("Expected 1–4 tool calls")
    result = []
    for call in calls:
        if not isinstance(call, dict) or set(call) != {"name", "arguments"}:
            raise ValueError("Invalid prompted call")
        registry.validate(call["name"], call["arguments"])
        result.append({"id": "prompted-" + uuid.uuid4().hex, "type": "function",
                       "function": {"name": call["name"],
                                    "arguments": json.dumps(call["arguments"], ensure_ascii=False)}})
    return result
