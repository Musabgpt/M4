#!/bin/sh
# Run in a trusted project under the bundled Alpine Linux.
set -eu
max_runs="${1:-5}"
steps="${2:-30}"
case "$max_runs" in ''|*[!0-9]*) echo "Restarts must be numeric" >&2; exit 2;; esac
case "$steps" in ''|*[!0-9]*) echo "Steps must be numeric" >&2; exit 2;; esac
if [ "$max_runs" -lt 1 ] || [ "$max_runs" -gt 20 ] || [ "$steps" -lt 1 ] || [ "$steps" -gt 500 ]; then
    echo "Limits: 1-20 runs, 1-500 steps" >&2; exit 2
fi
if [ ! -f .term-agent/state.json ]; then
    echo "Start the goal using term-agent run first" >&2; exit 2
fi
counter=0
while [ "$counter" -lt "$max_runs" ]; do
    counter=$((counter+1))
    echo "Term Agent restart $counter/$max_runs"
    term-agent resume --steps "$steps" --allow-exec || true
    phase="$(python3 -c 'import json; print(json.load(open(".term-agent/state.json")).get("phase","unknown"))')"
    case "$phase" in
        complete) echo "Independent checks passed."; exit 0;;
        paused-step-limit) sleep 2;;
        *) echo "Stopping: phase=$phase"; exit 1;;
    esac
done
echo "Step budget exhausted; manual resume remains available."
exit 2
