"""Scoped, read-only Git and public GitHub tools for M4.

No arbitrary git subcommands, authentication tokens, git push, or remote writes.
"""
import json
import re
import subprocess
import urllib.error
import urllib.request

from .shell import scrub_env

_REPO = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


def _git(root, *args):
    try:
        result = subprocess.run(["git", "-C", str(root), *args],
                                capture_output=True, text=True, timeout=20,
                                stdin=subprocess.DEVNULL, env=scrub_env())
    except (OSError, subprocess.TimeoutExpired) as exc:
        return "Git error: " + type(exc).__name__
    if result.returncode:
        return "Git error: " + result.stderr[:700]
    return result.stdout[:12000] or "(no changes)"


def git_status(root):
    return _git(root, "status", "--short", "--branch")


def git_diff(root, staged=False):
    return _git(root, "diff", "--no-ext-diff", "--cached" if staged else
                "--no-color", "--") if staged else _git(root, "diff", "--no-ext-diff", "--no-color", "--")


def git_log(root, limit=10):
    limit = max(1, min(int(limit), 30))
    return _git(root, "log", "-n", str(limit), "--format=%h %s")


def github_pr(repo, number):
    if not isinstance(repo, str) or not _REPO.fullmatch(repo):
        return "Invalid repository (expected owner/name)"
    try:
        number = int(number)
    except (ValueError, TypeError):
        return "Invalid PR number"
    if not 1 <= number <= 100000000:
        return "Invalid PR number"
    url = "https://api.github.com/repos/" + repo + "/pulls/" + str(number)
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "M4-Core-Agent"})
    try:
        with urllib.request.urlopen(req, timeout=12) as response:
            info = json.load(response)
        if not isinstance(info, dict):
            return "Invalid GitHub response"
        return json.dumps({
            "number": info.get("number"), "title": info.get("title"),
            "state": info.get("state"), "draft": info.get("draft"),
            "merged": bool(info.get("merged_at")),
            "url": info.get("html_url"), "base": (info.get("base") or {}).get("ref"),
            "head": (info.get("head") or {}).get("ref")},
            ensure_ascii=False)
    except (OSError, ValueError, TypeError) as exc:
        return "GitHub lookup failed: " + type(exc).__name__
