"""Tool calling for models WITHOUT native function-calling.

The model is told to emit <m4_call>{"tool":..., "args":{...}}</m4_call>.
parse_calls() is tolerant: fenced JSON, trailing commas, smart quotes, several
calls per reply. Returns OpenAI-style tool_calls so the agent loop is unchanged.
"""
import json
import re

CALL_RE = re.compile(r"<m4_call>(.*?)</m4_call>", re.S)
FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)```", re.S)


def render_tool_prompt(schemas):
    lines = ["You can use tools. To call one, reply with EXACTLY one block per call:",
             '<m4_call>{"tool":"NAME","args":{...}}</m4_call>',
             "Valid JSON only, double quotes. Wait for the result before continuing.",
             "Available tools:"]
    for s in schemas:
        fn = s.get("function", s)
        props = fn.get("parameters", {}).get("properties", {})
        req = set(fn.get("parameters", {}).get("required", []))
        sig = ", ".join(k + ("" if k in req else "?") + ":" + str(v.get("type", "any"))
                        for k, v in props.items())
        lines.append("- %s(%s): %s" % (fn["name"], sig, fn.get("description", "")[:140]))
    return "\n".join(lines)


def _repair(text):
    text = (text.replace("\u201c", '"').replace("\u201d", '"')
                .replace("\u2018", "'").replace("\u2019", "'"))
    return re.sub(r",\s*([}\]])", r"\1", text.strip())


def _loads(text):
    for candidate in (text, _repair(text)):
        try:
            return json.loads(candidate)
        except ValueError:
            continue
    return None


def _balanced(text):
    """Yield top-level {...} substrings (string-aware)."""
    depth, start, in_str, esc = 0, None, False, False
    for i, ch in enumerate(text):
        if in_str:
            esc = (not esc and ch == "\\")
            if ch == '"' and not esc:
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}" and depth:
            depth -= 1
            if depth == 0:
                yield text[start:i + 1]


def parse_calls(text, known_tools=None, limit=4):
    """Return (tool_calls, leftover_text). Unknown tool names are dropped."""
    if not isinstance(text, str):
        return [], ""
    blocks = CALL_RE.findall(text)
    leftover = CALL_RE.sub("", text).strip()
    if not blocks:                               # fallback: fenced / bare JSON
        blocks = FENCE_RE.findall(text) or list(_balanced(text))
    calls = []
    for b in blocks:
        for raw in ([b] if _loads(b) is not None else list(_balanced(b))):
            obj = _loads(raw)
            if not isinstance(obj, dict):
                continue
            name = obj.get("tool") or obj.get("name")
            args = obj.get("args", obj.get("arguments", {}))
            if isinstance(args, str):
                args = _loads(args) or {}
            if not isinstance(name, str) or not isinstance(args, dict):
                continue
            if known_tools is not None and name not in known_tools:
                continue
            calls.append({"id": "pt-%d" % len(calls), "type": "function",
                          "function": {"name": name,
                                       "arguments": json.dumps(args, ensure_ascii=False)}})
            if len(calls) >= limit:
                return calls, leftover
    return calls, leftover if calls else text.strip()


def correction_message(error_hint=""):
    return ("Your last reply had no valid tool call. Reply with "
            '<m4_call>{"tool":"NAME","args":{...}}</m4_call> using valid JSON. ' + error_hint)
