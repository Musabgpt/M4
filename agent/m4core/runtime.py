"""Live M4 coding loop: provider failover, native/prompted tools, permissions,
checkpoints, context compaction, and independent project verification.

This module is invoked by agent.py's CLI. It deliberately does not import the
legacy entrypoint, so existing checkpoint/rollback commands stay compatible.
"""
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

from .compaction import compact
from .loopguard import LoopGuard
from .permissions import is_dangerous
from .prompted_tools import correction_message, parse_calls, render_tool_prompt
from .registry import TOOL_NAMES, TOOL_SCHEMAS, ToolRegistry
from .router import Router, load_catalog


def _prompted_messages(messages):
    """Convert native tool history into regular chat turns for text-only APIs."""
    converted = []
    for msg in messages:
        role = msg.get("role")
        if role == "tool":
            converted.append({"role": "user", "content":
                              "Tool result (" + str(msg.get("tool_call_id", "")) +
                              "):\n" + str(msg.get("content", ""))})
        elif role == "assistant" and msg.get("tool_calls"):
            blocks = []
            for call in msg["tool_calls"]:
                fn = call.get("function") or {}
                blocks.append("<m4_call>" + json.dumps({
                    "tool": fn.get("name"), "args": _decode(fn.get("arguments"))},
                    ensure_ascii=False) + "</m4_call>")
            converted.append({"role": "assistant", "content":
                              (msg.get("content") or "") + "\n" + "\n".join(blocks)})
        else:
            converted.append({"role": role, "content": msg.get("content") or ""})
    if converted and converted[0]["role"] == "system":
        converted[0]["content"] += "\n\n" + render_tool_prompt(TOOL_SCHEMAS)
    return converted


def _decode(raw):
    if isinstance(raw, dict):
        return raw
    try:
        result = json.loads(raw or "{}")
        return result if isinstance(result, dict) else {}
    except (TypeError, ValueError):
        return {}


def _native_calls(reply):
    calls = reply.get("tool_calls")
    if not isinstance(calls, list):
        return []
    result = []
    for call in calls[:4]:
        fn = call.get("function") if isinstance(call, dict) else None
        if not isinstance(fn, dict) or fn.get("name") not in TOOL_NAMES:
            continue
        result.append({"id": str(call.get("id") or "native-%d" % len(result)),
                       "type": "function",
                       "function": {"name": fn["name"],
                                    "arguments": json.dumps(_decode(fn.get("arguments")),
                                                            ensure_ascii=False)}})
    return result


def _call_provider(route, messages, prompted=False):
    payload = {"model": route.model,
               "messages": _prompted_messages(messages) if prompted else messages}
    if not prompted:
        payload.update({"tools": TOOL_SCHEMAS, "tool_choice": "auto"})
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if route.auth_env:
        token = os.environ.get(route.auth_env)
        if not token:
            raise ValueError("Missing API key")
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(route.base_url.rstrip("/") + "/chat/completions",
                                 body, headers, method="POST")
    with urllib.request.urlopen(req, timeout=4 if route.local else 50) as response:
        message = json.load(response)["choices"][0]["message"]
    if not isinstance(message, dict):
        raise ValueError("Invalid provider response")
    return message


def ask_core(router, messages, family="freellmpool"):
    """Return an assistant reply with validated tool calls and the route used."""
    errors = []
    for route in router.order(family):
        started = time.monotonic()
        try:
            if router.tools_mode(route) == "prompted":
                message = _call_provider(route, messages, prompted=True)
                calls, _ = parse_calls(message.get("content"), TOOL_NAMES)
            else:
                try:
                    message = _call_provider(route, messages)
                    calls = _native_calls(message)
                    if calls:
                        router.set_capability(route.id, "native")
                    else:
                        calls, _ = parse_calls(message.get("content"), TOOL_NAMES)
                        if calls:
                            router.set_capability(route.id, "prompted")
                except (OSError, ValueError, KeyError, TypeError, IndexError):
                    message, calls = {}, []
                if not calls:
                    message = _call_provider(route, messages, prompted=True)
                    calls, _ = parse_calls(message.get("content"), TOOL_NAMES)
                    if calls:
                        router.set_capability(route.id, "prompted")
            if not calls:
                raise ValueError("No usable tool call (native or prompted)")
            router.record(route.id, True, time.monotonic() - started)
            return {"role": "assistant", "content": message.get("content") or None,
                    "tool_calls": calls}, route.name
        except (OSError, ValueError, KeyError, TypeError, IndexError) as exc:
            # Do not log request bodies, API keys, or user project contents.
            router.record(route.id, False, error=type(exc).__name__)
            errors.append(route.name + ": " + type(exc).__name__)
    raise RuntimeError("All M4 tool gateways failed: " + "; ".join(errors[-5:]))


def run(host, root, state, goal, max_steps, approved=False, mode="accept_edits"):
    """Drive the actual native-terminal agent while preserving host persistence."""
    path = state / "state.json"
    current = host.load(path, {})
    goal = goal or current.get("goal")
    if not goal:
        raise ValueError("Provide the goal to start a new task")
    if current.get("goal") != goal:
        current = {"goal": goal, "step": 0, "phase": "new", "last": "", "repeat": 0}
        checkpoint = host.task_state.checkpoint(
            str(root), objective=goal, progress="Goal received",
            next_step="Inspect and implement the first verifiable change")
        current["newal_checkpoint"] = checkpoint["id"]
    current["phase"] = "running"
    host.save(path, current)
    db = host.history(state)
    registry = ToolRegistry(root, state, mode=mode)
    router = Router(load_catalog(Path(__file__).resolve().parents[1] / "providers.d"))
    guard = LoopGuard()
    family = os.environ.get("M4_GATEWAY_FAMILY", "freellmpool")
    dialog = [{"role": "system", "content": host.SYSTEM + "\nUse the declared M4 core tools."},
              {"role": "user", "content":
               "GOAL: " + goal + "\nProject: " + str(root) +
               "\nPrevious work:\n" + host.recent(db)}]
    print("GOAL:", goal, "M4 CORE: active", flush=True)

    def approve(name, args, reason):
        # Opt-in command execution never auto-authorizes destructive commands.
        if name == "Bash":
            return approved and not is_dangerous(str(args.get("command", "")))
        return False

    try:
        for _ in range(max_steps):
            current["step"] += 1
            reply, provider = ask_core(router, dialog, family)
            calls = reply["tool_calls"]
            # Keep exactly the same calls in assistant and tool history.
            dialog.append(reply)
            for call in calls:
                fn = call["function"]
                name = fn["name"]
                args = _decode(fn["arguments"])
                output = registry.execute(name, args, approve=approve)
                host.log(db, "tool", {"tool": name, "args":
                                     {k: v for k, v in args.items()
                                      if k not in ("content", "new_string", "edits")},
                                     "result": output[:4000], "provider": provider})
                dialog.append({"role": "tool", "tool_call_id": call["id"],
                               "content": output[:7000]})
                print("Step", current["step"], name, output[:200], flush=True)
                if name == "Finish" and output == "VERIFY":
                    passed, report = host.verify(root, state, approved)
                    host.log(db, "verification", report[:7000])
                    if passed:
                        current["phase"] = "complete"
                        current["summary"] = str(args.get("summary", ""))
                        if current.get("newal_checkpoint"):
                            host.task_state.complete(str(root),
                                                     current["newal_checkpoint"],
                                                     current["summary"])
                        host.save(path, current)
                        print("COMPLETE: independent checks passed", flush=True)
                        return 0
                    if report.startswith("No checks"):
                        current["phase"] = "needs-checks"
                        host.save(path, current)
                        print("BLOCKED:", report, flush=True)
                        return 2
                    dialog.append({"role": "user", "content":
                                   "Independent verification failed. Repair before Finish: " +
                                   report[:2000]})
                decision = guard.record(name, args, changed_files=name in
                                        ("Write", "Edit", "MultiEdit") and
                                        not output.startswith(("Tool error", "Denied", "Not approved")))
                if decision == "warn":
                    dialog.append({"role": "user", "content": LoopGuard.ADVICE})
                elif decision == "stop":
                    current["phase"] = "stuck"
                    host.save(path, current)
                    print("STUCK: repeated unproductive operations", flush=True)
                    return 3
            dialog = compact(dialog, goal=goal, touched=registry.touched,
                             last_error=current.get("error"))
            if current.get("newal_checkpoint"):
                host.task_state.checkpoint(
                    str(root), task_id=current["newal_checkpoint"],
                    progress="Core action cycle " + str(current["step"]),
                    next_step="Continue working and run independent verification",
                    evidence="Recorded events in .term-agent/events.sqlite3")
            host.save(path, current)
        current["phase"] = "paused-step-limit"
        host.save(path, current)
        print("PAUSED: step budget; resume with term-agent resume", flush=True)
        return 2
    except (OSError, ValueError, RuntimeError) as exc:
        current["phase"] = "blocked"
        current["error"] = str(exc)[:1000]
        host.save(path, current)
        print("BLOCKED:", type(exc).__name__, file=host.sys.stderr)
        return 4
    finally:
        registry.shell.close_all()
        db.close()
