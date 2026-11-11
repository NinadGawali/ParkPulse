#!/usr/bin/env bash
# Stops the services started by run-local.sh (PIDs in logs/pids), then any java still listening
# on the service ports. Does not touch docker infra (docker compose down for that).
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

if [[ -f logs/pids ]]; then
  while IFS=: read -r name pid; do
    [[ -n "${pid:-}" ]] || continue
    kill "$pid" 2>/dev/null && echo "stop-local: stopped $name ($pid)"
    # Git Bash PIDs differ from Windows PIDs; taskkill by winpid when available.
    winpid="$(ps -p "$pid" 2>/dev/null | awk 'NR==2{print $4}')"
    [[ -n "$winpid" ]] && taskkill //F //PID "$winpid" >/dev/null 2>&1
  done < logs/pids
  : > logs/pids
fi

# Fallback: whatever still listens on the service ports (Windows).
if command -v netstat >/dev/null 2>&1; then
  for port in 8080 8081 8082 8083 8084 8085 8086 8087 8088 8089; do
    pid="$(netstat -ano 2>/dev/null | tr -d '\r' | awk -v p=":$port" '$2 ~ p"$" && $4=="LISTENING"{print $5; exit}')"
    if [[ -n "$pid" ]]; then taskkill //F //PID "$pid" >/dev/null 2>&1 && echo "stop-local: killed :$port (pid $pid)"; fi
  done
fi
echo "stop-local: done"
