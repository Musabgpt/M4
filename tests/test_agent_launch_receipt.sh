#!/usr/bin/env bash
# Exercise the real launch wrapper with a deterministic local tool fixture.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
mkdir -p "$TEMP/bin" "$TEMP/project/.git" "$TEMP/project/agent" "$TEMP/project/agent_tests" "$TEMP/chats/demo"
touch "$TEMP/project/agent/agent.py"
printf '{}\n' > "$TEMP/chats/demo/request.json"
printf 'export M4_GATEWAY_FAMILY=gpt4free\n' > "$TEMP/provider.env"
cat > "$TEMP/bin/term-agent" <<'MOCK'
#!/bin/sh
case "$1" in
  init|check-add) exit 0 ;;
  chat)
    if [ "${QA_MOCK_AGENT_RESULT:-failure}" = "success" ]; then
      printf '{"status":"completed","content":"mock response"}\n' > "${3%/*}/response.json"
      exit 0
    fi
    exit 27
    ;;
esac
exit 5
MOCK
chmod 755 "$TEMP/bin/term-agent"
export PATH="$TEMP/bin:$PATH" M4_AGENT_PROJECT="$TEMP/project" M4_AGENT_PROVIDER_FILE="$TEMP/provider.env"
set +e
QA_MOCK_AGENT_RESULT=failure sh "$ROOT/agent/m4-agent-start.sh" --chat "$TEMP/chats/demo/request.json" > "$TEMP/output.log" 2>&1
rc="$?"
set -e
test "$rc" -eq 27 || { echo "Expected exit 27, received $rc"; cat "$TEMP/output.log"; exit 1; }
python3 - "$TEMP/chats/demo/response.json" <<'PY'
import json,sys
result=json.load(open(sys.argv[1]))
assert result['status']=='blocked',result
assert result['exit_code']==27,result
assert 'exited' in result['content'],result
PY
rm "$TEMP/chats/demo/response.json"
QA_MOCK_AGENT_RESULT=success sh "$ROOT/agent/m4-agent-start.sh" --chat "$TEMP/chats/demo/request.json" > "$TEMP/output.log" 2>&1
python3 - "$TEMP/chats/demo/response.json" <<'PY'
import json,sys
result=json.load(open(sys.argv[1]))
assert result['status']=='completed',result
assert result['content']=='mock response',result
PY
echo 'PASS: failed agent produces blocked receipt and successful agent preserves response'
