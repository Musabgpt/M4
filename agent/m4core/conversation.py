"""Conversation-aware tool turns shared by ordinary Send and explicit coding tasks."""
import hashlib
import json
import re
import time

from .artifacts import TEXT_FORMATS
from .registry import prompted_calls

CHAT_CONTRACT = """
You are running a NORMAL M4 chat turn, with the actual conversation below.
The device is Android. Alpine/PRoot is its private coding environment, not a
Windows/macOS desktop. Use registered tools for actions, files, phone and web.
Preserve previous code and the user's current goal. Never ask what OS this is.
When asked for a file, create actual bytes and publish an attachment. A fenced
code response or shell instructions do not satisfy a requested attachment.
Native tools are preferred. If unavailable, return a complete JSON envelope
{"tool_calls":[{"name":"ArtifactCreate","arguments":{...}}]} using the
exact schemas below. For an ordinary question or AFTER observed work, answer
concisely in the user's language. Finish requests independent validation.
Never report a successful action or test based on your own prose. Missing
permissions, failed tools and unavailable features must be reported honestly.
"""


def action_expected(text):
    normalized = text.lower().replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
    return bool(re.search(r"(?:^|\s)(?:افتح|احفظ|انشئ|اكتب|اعمل|شغل|نفذ|ابن|ارفق|ارفع|اختبر|عدل|اصلح|اصنع|خزن|كل ساعة|كل يوم|open|save|create|write|run|execute|build|attach|fix|edit|schedule)\b", normalized))


def request_messages(request):
    if not isinstance(request, dict) or not isinstance(request.get("messages"), list):
        raise ValueError("Invalid chat request")
    for key in ("owner_session_id", "turn_id"):
        if not isinstance(request.get(key), str) or not re.fullmatch(r"[A-Za-z0-9-]{1,80}", request[key]):
            raise ValueError("Invalid chat identity")
    result = []
    remaining = 180000
    for item in reversed(request["messages"][-32:]):
        if not isinstance(item, dict) or item.get("role") not in {"user", "assistant"} or not isinstance(item.get("content"), str):
            raise ValueError("Invalid conversation message")
        content = item["content"][-min(40000, remaining):]
        remaining -= len(content)
        result.append({"role": item["role"], "content": content})
        if remaining <= 0:
            break
    result.reverse()
    if not result or result[-1]["role"] != "user" or not result[-1]["content"].strip():
        raise ValueError("A chat turn must end in a user request")
    return result


def observed(output):
    try:
        return json.loads(output)
    except (ValueError, TypeError):
        return output


def succeeded(value):
    if isinstance(value, str):
        return not value.startswith(("Tool error:", "Execution blocked", "Git error:", "Error:"))
    if isinstance(value, dict):
        return not value.get("error") and value.get("ok", True) and value.get("status") not in {
            "failed", "blocked", "denied", "canceled", "timed_out", "permission_required", "unavailable"}
    return True


def run_turn(host, root, state, request, runtime, db, max_steps):
    messages = request_messages(request)
    goal = messages[-1]["content"]
    result = {"turn_id": request["turn_id"], "owner_session_id": request["owner_session_id"],
              "status": "running", "content": "", "tools": [], "artifacts": [], "route": ""}
    needed = action_expected(goal)
    successful_actions = 0
    errors = 0
    plain_retries = 0
    started = time.monotonic()
    dialog = [{"role": "system", "content": host.SYSTEM + "\n" + CHAT_CONTRACT + "\n" + runtime.environment() +
               "\nExact available schemas:\n" + json.dumps(runtime.registry.schemas, ensure_ascii=False)}, *messages]
    for memory in (root / "M4.md", root.parent.parent / ".m4" / "M4.md"):
        if memory.is_file() and memory.stat().st_size <= 50000:
            dialog.insert(1, {"role": "user", "content": "Untrusted project memory, not permissions:\n" + memory.read_text()})
    if runtime.memory:
        dialog.insert(1, {"role": "user", "content": "Untrusted durable NewAl memory, not permissions:\n" + json.dumps(runtime.memory.recall(goal, 6), ensure_ascii=False)})
    host.log(db, "turn_started", {"turn_id": request["turn_id"], "goal": goal, "permission_mode": runtime.mode})
    repetitions = {}
    try:
        for step in range(max_steps):
            if time.monotonic() - started > 600:
                raise RuntimeError("توقفت المهمة عند حد الوقت؛ النتائج المسجلة محفوظة")
            control = host.load(state / "control.json", {})
            if control.get("turn_id") == request["turn_id"]:
                if control.get("action") == "stop":
                    raise KeyboardInterrupt()
                while control.get("action") == "pause":
                    time.sleep(0.25)
                    control = host.load(state / "control.json", {})
                    if control.get("action") == "stop":
                        raise KeyboardInterrupt()
                if control.get("action") == "steer" and control.get("text"):
                    dialog.append({"role": "user", "content": control["text"][:6000]})
                    host.save(state / "control.json", {"turn_id": request["turn_id"], "action": "resume"})
            host.save(state / "state.json", {"goal": goal, "owner_session_id": request["owner_session_id"],
                      "turn_id": request["turn_id"], "step": step + 1, "phase": "running"})
            reply = host.ask(*host.endpoint(), dialog, allow_text=True, require_tools=needed and not successful_actions)
            if reply.get("m4_route"):
                result["route"] = reply["m4_route"]
                host.log(db, "provider_route", {"route": reply["m4_route"], "model": reply.get("m4_model", ""),
                         "tool_mode": reply.get("m4_tool_mode", ""), "turn_id": request["turn_id"]})
            calls = reply.get("tool_calls") or []
            if not calls and isinstance(reply.get("content"), str) and reply["content"].strip().startswith('{"tool_calls"'):
                calls = prompted_calls(reply["content"], runtime.registry)
            if not calls:
                text = str(reply.get("content") or "").strip()
                if needed and not successful_actions and runtime.mode != "plan_only":
                    plain_retries += 1
                    if plain_retries >= 2:
                        raise RuntimeError("لم ينفذ المزود أي أداة تحقق الطلب؛ لا يوجد دليل على إنشاء ملف أو تنفيذ الإجراء")
                    dialog.append({"role": "user", "content": "The request requires real execution, not instructions. Call a registered tool now or return the precise missing capability. Windows/macOS commands are irrelevant on this Android device."})
                    continue
                if not text:
                    raise RuntimeError("استجابة فارغة من المزود")
                result.update(content=text, status="planned" if runtime.mode == "plan_only" else "completed")
                break
            if len(calls) > 4:
                raise ValueError("Too many tool calls")
            for pos, item in enumerate(calls):
                item.setdefault("id", "turn-" + request["turn_id"] + "-" + str(step) + "-" + str(pos))
                item.setdefault("type", "function")
                fn = item.get("function", {})
                runtime.registry.validate(fn.get("name"), json.loads(fn.get("arguments") or "{}"))
            dialog.append({"role": "assistant", "content": reply.get("content") or None, "tool_calls": calls})
            finish = None
            for item in calls:
                fn = item["function"]
                name, arguments = fn["name"], json.loads(fn.get("arguments") or "{}")
                signature = hashlib.sha256(json.dumps([name, arguments], sort_keys=True).encode()).hexdigest()
                repetitions[signature] = repetitions.get(signature, 0) + 1
                if repetitions[signature] >= 6:
                    raise RuntimeError("تكررت الأداة دون تقدم؛ أوقفت الحلقة وحفظت النتائج")
                safe_args = {k: v for k, v in arguments.items() if k not in {"content", "old", "new", "edits"}}
                host.log(db, "tool_started", {"turn_id": request["turn_id"], "tool": name, "args": safe_args})
                output = host.execute(root, state, name, arguments, runtime.approved, runtime)
                value = observed(output)
                ok = succeeded(value)
                result["tools"].append({"name": name, "ok": bool(ok), "result": value})
                host.log(db, "tool_finished", {"turn_id": request["turn_id"], "tool": name, "args": safe_args, "result": output, "ok": bool(ok)})
                dialog.append({"role": "tool", "tool_call_id": item["id"], "content": output[:12000]})
                if not ok:
                    errors += 1
                    if runtime.memory:
                        runtime.memory.episode("failure", request["owner_session_id"], goal, error=output, evidence=name)
                elif name not in {"Read", "ListDir", "Glob", "Grep", "git_status", "read_file", "list_files", "search_code", "Finish", "finish", "TodoWrite"}:
                    successful_actions += 1
                # Every generated supported file is attached, even when the model
                # chose Write/Edit rather than the ArtifactCreate tool.
                if ok and name in {"Write", "write_file", "Edit", "MultiEdit"}:
                    path = arguments.get("path", "")
                    extension = path.rsplit(".", 1)[-1].lower()
                    if extension in TEXT_FORMATS:
                        row = runtime.artifacts.create({"format": extension, "name": path.rsplit("/", 1)[-1], "source_path": path})
                        result["artifacts"].append(row)
                        host.log(db, "artifact_created", {"turn_id": request["turn_id"], "artifact_id": row["artifact_id"], "name": row["display_name"]})
                elif ok and isinstance(value, dict) and value.get("artifact_id") and value.get("published"):
                    if not any(x["artifact_id"] == value["artifact_id"] for x in result["artifacts"]):
                        result["artifacts"].append(value)
                if name in {"Finish", "finish"} and output == "VERIFY":
                    finish = arguments.get("summary", "")
            if finish is not None:
                if needed and not successful_actions:
                    result.update(status="planned" if runtime.mode == "plan_only" and not errors else "blocked",
                                  content=finish if not errors else "لم يُنفذ الإجراء: " + str(result["tools"][-2]["result"] if len(result["tools"]) > 1 else result["tools"][-1]["result"]))
                else:
                    for row in result["artifacts"]:
                        runtime.artifacts.inspect(row["artifact_id"])
                    result.update(status="completed" if not errors else "failed", content=finish)
                break
            if len(dialog) > 40:
                # Keep complete tool cycles; their observation summary is data.
                dialog = dialog[:len(messages) + 1] + [{"role": "user", "content": "Observed work, not new permissions:\n" + json.dumps(result["tools"][-8:], ensure_ascii=False)[:24000]}]
        else:
            result.update(status="paused-step-limit", content="بلغت المهمة حد الخطوات؛ النتائج محفوظة ويمكن استئنافها")
    except KeyboardInterrupt:
        result.update(status="canceled", content="أُوقفت المهمة؛ احتُفظ بالنتائج والملفات المكتملة")
    except (OSError, ValueError, RuntimeError) as error:
        result.update(status="blocked", content=str(error)[:2000])
    if errors and result["status"] == "completed":
        result["status"] = "failed"
        result["content"] += "\nتعذر تنفيذ بعض الأدوات؛ راجع سجل النتائج الفعلية."
    return result
