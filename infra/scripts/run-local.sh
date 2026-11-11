#!/usr/bin/env bash
# Starts all 10 services as detached java processes (local profile) from the repo root and waits
# until each reports UP on /actuator/health. Logs: logs/<service>.log, PIDs: logs/pids.
# Prerequisites: infra running (infra/scripts/infra-up.sh), jars built (./mvnw clean package -DskipTests).
# Usage: run-local.sh [service ...]     (default: all, identity first)
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

# JDK 21+ required; a stale JDK 17 shell is a common failure, so prefer jdk-22 when found.
if ! java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then
  for jdk in "/c/Program Files/Java/jdk-22" "/c/Program Files/Java/jdk-21"; do
    if [[ -x "$jdk/bin/java" ]]; then export JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH"; break; fi
  done
fi
java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])' || { echo "run-local: JDK 21+ required"; exit 1; }

# name:port, in start order (identity serves the JWKS everyone else verifies against)
all=(identity:8081 profile:8082 listing:8083 booking:8084 payment:8085 search:8086
  notification:8087 review:8088 admin-bff:8089 gateway:8080)
selected=("$@")

mkdir -p logs
pidfile="logs/pids"
touch "$pidfile"
export JAVA_TOOL_OPTIONS="-Xmx384m -XX:+UseSerialGC"

wanted() {
  [[ ${#selected[@]} -eq 0 ]] && return 0
  local s; for s in "${selected[@]}"; do [[ "$s" == "$1" ]] && return 0; done
  return 1
}

started=()
for entry in "${all[@]}"; do
  name="${entry%%:*}"; port="${entry##*:}"
  wanted "$name" || continue
  if curl -fs "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
    echo "run-local: $name already up on :$port"; continue
  fi
  jar="services/$name/target/$name-service-0.1.0-SNAPSHOT.jar"
  [[ -f "$jar" ]] || { echo "run-local: $jar missing, build first"; exit 1; }
  nohup java -jar "$jar" --spring.profiles.active=local \
    >"logs/$name.log" 2>&1 </dev/null &
  echo "$name:$!" >> "$pidfile"
  disown
  started+=("$entry")
  echo "run-local: starting $name (:$port)"
done

fail=0
for entry in "${started[@]}"; do
  name="${entry%%:*}"; port="${entry##*:}"
  ok=false
  for _ in $(seq 1 90); do
    if curl -fs "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then ok=true; break; fi
    sleep 2
  done
  if $ok; then echo "run-local: $name UP"; else echo "run-local: $name did NOT become healthy (see logs/$name.log)"; fail=1; fi
done
exit $fail
