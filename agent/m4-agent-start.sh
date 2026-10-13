#!/bin/sh
# Start a coding task against the ACTUAL M4 sources, never an empty demo folder.
# Alpine/PRoot runtime. No Termux, privileged shell or Android root required.
set -eu
export M4_MEMORY_HOME=/root/.m4/memory
umask 077
goal="${1:-}"
chat_request=""
if [ "$goal" = "--chat" ]; then
  chat_request="${2:-}"
  test -n "$chat_request" || { echo "Missing chat request" >&2; exit 2; }
fi
if [ -z "$goal" ]; then
  echo "Missing coding goal: m4-agent-start 'Describe a task'" >&2
  exit 2
fi
project="${M4_AGENT_PROJECT:-/root/projects/M4}"
provider="${M4_AGENT_PROVIDER_FILE:-/root/.m4-agent/provider.env}"
source_repo="https://github.com/Musabgpt/M4.git"
seed="${M4_AGENT_SEED:-/opt/m4-seed}"
if [ ! -f "$provider" ]; then
  echo "No built-in gateway selection found: open M4 chat." >&2
  exit 3
fi
if [ -d "$project/.git" ]; then
  echo "Using existing project: $project (local changes are preserved)"
elif [ -e "$project" ]; then
  # Never overwrite an existing directory and never silently use an empty project.
  echo "Existing folder is not a Git checkout: $project" >&2
  echo "Move it aside manually or select a different project directory." >&2
  exit 4
else
  mkdir -p "$(dirname "$project")"
  if [ -d "$seed/.git" ] && [ -f "$seed/agent/agent.py" ] && [ -d "$seed/agent_tests" ]; then
    echo "Creating offline M4 project from verified APK source snapshot"
    cp -a "$seed" "$project"
  else
    echo "Cloning M4 source: $source_repo"
    git clone --depth=1 "$source_repo" "$project" || {
      echo "Unable to clone M4. Check the network or GitHub access." >&2
      exit 5
    }
  fi
fi
cd "$project"
if [ ! -f "agent/agent.py" ] || [ ! -d "agent_tests" ]; then
  echo "This directory does not have the expected M4 agent sources." >&2
  exit 6
fi
# Only a fixed, harmless gateway family name is sourced here, not API credentials.
. "$provider"
case "${M4_GATEWAY_FAMILY:-freellmpool}" in
    freellmpool|gpt4free|free-llm-gateway|free-llm-api) ;;
    *) echo "Unsupported M4 gateway family" >&2; exit 7 ;;
esac
# Keep setup in shell builtins: Android's PRoot build can run the existing
# shell, but fork(2) is unavailable for a child command. Python is replaced
# with exec below, so no intermediate child is needed for a chat turn.
mkdir -p "$project/.term-agent"
checks="$project/.term-agent/checks.json"
if [ ! -s "$checks" ]; then
  printf '["python3 -m unittest discover -s agent_tests -v"]\n' > "$checks"
fi
echo "Project source: $project"
echo "Independent Python agent checks configured. Android APK build runs in GitHub CI."
if [ "${M4_PERMISSION_MODE:-auto_project}" = "plan_only" ]; then
  if [ -n "$chat_request" ]; then
    exec term-agent chat --request "$chat_request" --steps 40 --permission-mode plan_only
  fi
  exec term-agent run "$goal" --steps 40 --permission-mode plan_only
fi
if [ -n "$chat_request" ]; then
  exec term-agent chat --request "$chat_request" --steps 40 --allow-exec --permission-mode "${M4_PERMISSION_MODE:-auto_project}"
fi
exec term-agent run "$goal" --steps 40 --allow-exec
