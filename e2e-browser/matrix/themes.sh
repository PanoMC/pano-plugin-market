#!/usr/bin/env bash
# Starts / stops `vite dev` of the five theme checkouts for the E2E-20 matrix, API calls proxied to the isolated instance.
#   themes.sh start <instance-http-port> <base-port>   ports base .. base+4 (vanilla blaze blocky frost banana); prints the MARKET_E2E_MATRIX value
#   themes.sh stop                                     SIGTERM to the recorded PIDs (never a pattern)
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
M=$(cd "$HERE/../.." && pwd -P)
ROOT=${MARKET_E2E_THEMES_ROOT:-$(cd "$M/../../.." && pwd -P)/themes}
STATE=${MARKET_E2E_MATRIX_STATE:-$M/build/e2e-matrix}
NAMES=(vanilla blaze blocky frost banana)
mkdir -p "$STATE"
case "${1:-}" in
  start)
    api=${2:?instance http port}; base=${3:?base port}; list=""; i=0
    for n in "${NAMES[@]}"; do
      port=$((base + i)); i=$((i + 1))
      ss -ltn | grep -q ":$port " && { echo "port $port in use" >&2; exit 10; }
      (cd "$ROOT/$n-theme" && VITE_API_URL="http://127.0.0.1:$api/api" exec setsid node_modules/.bin/vite dev --host=127.0.0.1 --port "$port" --strictPort \
        </dev/null >"$STATE/$n.log" 2>&1) &
      echo $! > "$STATE/$n.pid"
      list="$list${list:+,}$n=http://127.0.0.1:$port"
    done
    i=0
    for n in "${NAMES[@]}"; do
      port=$((base + i)); i=$((i + 1))
      for _ in $(seq 1 120); do ss -ltn | grep -q ":$port " && break; sleep 1; done
      ss -ltn | grep -q ":$port " || { echo "theme $n did not start (see $STATE/$n.log)" >&2; exit 14; }
    done
    echo "export MARKET_E2E_MATRIX=$list" ;;
  stop)
    for n in "${NAMES[@]}"; do
      f="$STATE/$n.pid"; [ -f "$f" ] || continue
      pid=$(cat "$f"); kill -0 "$pid" 2>/dev/null && kill -TERM "$pid"; rm -f "$f"
    done ;;
  *) echo "usage: themes.sh start <instance-http-port> <base-port> | stop" >&2; exit 2 ;;
esac
