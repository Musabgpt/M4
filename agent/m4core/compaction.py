"""Deterministic context compaction (no LLM call needed)."""
import json


def _size(m):
    return len(json.dumps(m, ensure_ascii=False, default=str))


def compact(messages, max_chars=48000, keep_last=8, goal=None, touched=None, last_error=None):
    """Keep system + first user message + last N; fold the middle into a summary."""
    if sum(_size(m) for m in messages) <= max_chars or len(messages) <= keep_last + 2:
        return messages
    head, tail = messages[:2], messages[-keep_last:]
    # never start the tail with an orphan tool result
    while tail and tail[0].get("role") == "tool":
        tail = tail[1:]
    middle = messages[2:len(messages) - len(tail)]
    tools = [tc["function"]["name"] for m in middle for tc in (m.get("tool_calls") or [])]
    summary = ["[Context compacted] %d earlier messages folded." % len(middle)]
    if goal:
        summary.append("Goal: " + goal)
    if touched:
        summary.append("Files touched: " + ", ".join(sorted(touched)[:30]))
    if tools:
        summary.append("Earlier tools used: " + ", ".join(tools[-20:]))
    if last_error:
        summary.append("Last error: " + str(last_error)[:500])
    return head + [{"role": "user", "content": "\n".join(summary)}] + tail
