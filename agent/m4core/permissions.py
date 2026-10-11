"""Permission modes modelled on Claude Code: ask / accept_edits / plan / auto."""
import re

ASK, ACCEPT_EDITS, PLAN, AUTO = "ask", "accept_edits", "plan", "auto"
MODES = (ASK, ACCEPT_EDITS, PLAN, AUTO)
READ_TOOLS = {"Read", "Glob", "Grep", "ListDir", "TodoWrite", "BashOutput", "Finish", "KillShell",
              "GitStatus", "GitDiff", "GitLog", "GitHubPR"}
EDIT_TOOLS = {"Write", "Edit", "MultiEdit"}
DANGEROUS = [
    r"\brm\s+-[a-z]*r[a-z]*f?\s+(/|~|\$HOME|\.\.)", r"\bgit\s+push\b.*--force", r"\bgit\s+push\b.*\s-f\b",
    r"\bmkfs\b", r"\bdd\s+if=", r":\(\)\s*\{", r"\bchmod\s+-R\s+777\s+/", r">\s*/dev/sd",
    r"\bgit\s+reset\s+--hard\b", r"\bcurl\b[^|]*\|\s*(ba)?sh", r"\bwget\b[^|]*\|\s*(ba)?sh",
]
_DANGER_RX = [re.compile(p) for p in DANGEROUS]


def is_dangerous(command):
    return any(rx.search(command or "") for rx in _DANGER_RX)


def decide(tool, args, mode=ASK):
    """Return ('allow'|'ask'|'deny', reason)."""
    if mode not in MODES:
        mode = ASK
    if tool in READ_TOOLS:
        return "allow", "read-only"
    if mode == PLAN:
        return "deny", "plan mode is read-only; present a plan with ExitPlanMode"
    if tool == "Bash":
        if is_dangerous(args.get("command", "")):
            return "ask", "dangerous command needs explicit confirmation"
        return ("allow", "auto mode") if mode == AUTO else ("ask", "command execution")
    if tool in EDIT_TOOLS:
        return ("allow", "edits allowed") if mode in (ACCEPT_EDITS, AUTO) else ("ask", "file edit")
    return ("allow", "auto mode") if mode == AUTO else ("ask", "tool needs approval")
