"""Strict schemas shared by native and prompted tool calling."""
import json
import uuid
import re
import math


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
            "requires_user_consent": False, "timeout": 90, "result_schema": {"type": ["object", "array", "string", "number", "boolean", "null"]}, **(metadata or {})}

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
        if isinstance(kind, list):
            for alternative in kind:
                try: cls._validate(value, dict(specification, type=alternative), field); return
                except ValueError: pass
            raise ValueError("Invalid union type for " + field)
        if "$ref" in specification: raise ValueError("Referenced MCP schemas are unsupported; flatten the schema before registration")
        for clause in ("anyOf", "oneOf"):
            if clause in specification:
                matches = 0
                for alternative in specification[clause][:20]:
                    try: cls._validate(value, alternative, field); matches += 1
                    except ValueError: pass
                if matches == 0 or clause == "oneOf" and matches != 1: raise ValueError("Schema alternatives rejected " + field)
        for alternative in specification.get("allOf", [])[:20]: cls._validate(value, alternative, field)
        types = {"object": dict, "array": list, "string": str,
                 "integer": int, "boolean": bool, "number": (int, float), "null": type(None)}
        if kind in types and (not isinstance(value, types[kind]) or
                (kind in ("integer", "number") and isinstance(value, bool))):
            raise ValueError("Invalid type for " + field)
        if "enum" in specification and value not in specification["enum"]:
            raise ValueError("Invalid value for " + field)
        if "const" in specification and value != specification["const"]: raise ValueError("Invalid constant for " + field)
        if kind in ("integer", "number"):
            if not math.isfinite(value): raise ValueError("Non-finite number: " + field)
            if value < specification.get("minimum", float("-inf")) or value > specification.get("maximum", float("inf")):
                raise ValueError("Out of range: " + field)
        if kind == "string":
            if not specification.get("minLength", 0) <= len(value) <= specification.get("maxLength", 500000): raise ValueError("Invalid field length: " + field)
            if "pattern" in specification:
                pattern = specification["pattern"]
                if len(pattern) > 200 or re.search(r"[+*}]\s*[+*{]", pattern): raise ValueError("Unbounded external schema pattern")
                if not re.search(pattern, value[:5000]): raise ValueError("Pattern mismatch: " + field)
        if kind == "object":
            properties = specification.get("properties", {})
            if specification.get("additionalProperties") is False and set(value) - set(properties):
                raise ValueError("Unknown arguments for " + field)
            if set(specification.get("required", [])) - set(value):
                raise ValueError("Missing arguments for " + field)
            for key, item in value.items():
                if key in properties:
                    cls._validate(item, properties[key], field + "." + key)
                elif isinstance(specification.get("additionalProperties"), dict):
                    cls._validate(item, specification["additionalProperties"], field + "." + key)
        if kind == "array":
            if not specification.get("minItems", 0) <= len(value) <= specification.get("maxItems", 100):
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

def provider_calls(text, registry):
    """Repair structural wrappers only. Never infer commands from prose."""
    if not isinstance(text, str): raise ValueError("Invalid provider text")
    cleaned = text.strip()
    fenced = re.fullmatch(r"```(?:json)?\s*\n([\s\S]*?)\n```", cleaned)
    if fenced: cleaned = fenced.group(1).strip()
    tagged = re.fullmatch(r"<tool_call>\s*([\s\S]*?)\s*</tool_call>", cleaned)
    if tagged:
        call = json.loads(tagged.group(1))
        cleaned = json.dumps({"tool_calls": [call]})
    return prompted_calls(cleaned, registry)
