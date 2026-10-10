#!/bin/sh
# Optional network installs only. Installed add-on packages may execute code.
set -eu
task=help
if [ "$#" -gt 0 ]; then task="$1"; fi
case "$task" in
 pi)
   command -v npm >/dev/null 2>&1 || { echo "npm unavailable" >&2; exit 1; }
   npm install -g @mariozechner/pi-coding-agent
   ;;
 ralph)
   command -v pi >/dev/null 2>&1 || { echo "Run term-agent-addons pi first" >&2; exit 1; }
   pi install npm:@tmustier/pi-ralph-wiggum
   ;;
 github)
   apk-v2 add github-cli
   ;;
 python-tests)
   apk-v2 add py3-pytest
   ;;
 *)
   echo "Use: term-agent-addons pi | ralph | github | python-tests"
   echo "Third-party add-ons require network access and may have Alpine/PRoot compatibility limits."
   ;;
esac
