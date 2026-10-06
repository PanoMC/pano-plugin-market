#!/usr/bin/env bash
# Isolated Pano instance for the market end-to-end tests (17 section 8.1, MK-012). Never the dev instance.
#
#   e2e-instance.sh start   [--keep] [options]   delete + recreate the instance dir and database, boot, install, wait until the market is up
#   e2e-instance.sh stop                          SIGTERM to the recorded PID, wait for it, check the port is free
#   e2e-instance.sh kill                          SIGKILL to the recorded PID (crash tests)
#   e2e-instance.sh restart [options]             stop, then start --keep (data kept, ports read back from the instance)
#   e2e-instance.sh status                        0 = running, 3 = not running
#   e2e-instance.sh install-legacy [options]      boot WITHOUT the market jar, install, stop, load the scheme-v2 fixtures,
#                                                 insert the plugin scheme-version row (2), copy the jars, start
#
# Options:  --name <n>            second, named instance (needs --http-port and --gateway-port); dir instance-<n>, database pano_market_e2e_<n>
#           --http-port <p>       Pano HTTP port (default 18188)
#           --gateway-port <p>    port reserved for the fake gateway of the test JVM (default 18189); checked free, never opened here
#           --ui external:<theme>,<panel>
#                                 also serve the host UIs from the local checkouts (themes/vanilla-theme, panel-ui) with
#                                 `vite dev` on those two ports, API calls proxied to this instance. The UIFiles zips are never touched.
#           --keep                start: reuse the instance directory and database (data kept; no install when already installed)
#
# Environment (secrets are only ever read from here, never printed or written to a file):
#   PANO_IT_MARIADB=host:port (default 127.0.0.1:3306)   PANO_IT_MARIADB_PASSWORD (root password, required)
#   MARKET_E2E_DB_CONTAINER   docker container that runs the database (default pano-web-platform-db-1); when docker or the
#                             container is missing, a host `mariadb` / `mysql` client over TCP is used instead
#   MARKET_E2E_JAVA           JRE 11 binary (default /usr/lib/jvm/java-11-openjdk/bin/java)
#   MARKET_E2E_PANO_JAR       Pano fat jar (default $P/build/libs/Pano-local-build.jar, checked for staleness)
#   MARKET_E2E_PLUGIN_JAR     market plugin jar (default $P/build/plugins/pano-plugin-market-local-build.jar, standalone:
#                             $M/build/libs/pano-plugin-market-local-build.jar)
#   MARKET_E2E_FAKE_JAR       fake provider jar (default $M/build/fake/pano-plugin-market-fake-local-build.jar)
#   MARKET_E2E_JAVA_OPTS      extra JVM options (default -XX:MaxRAMPercentage=40)
#   MARKET_E2E_READY_TIMEOUT  seconds to wait for each readiness phase (default 300)
#   MARKET_E2E_ALLOW_DEGRADED set to 1: start / restart accept a market that runs DEGRADED (503 STORE_UNAVAILABLE, ERROR in the log); only the log
#                             marker and GET /api/health are awaited (LifecycleE2E L-04)
#   MARKET_E2E_LEGACY_EXTRA_SQL  install-legacy: a SQL file loaded after seed-v2.sql, before the migrating boot (LifecycleE2E L-01b)
#   MARKET_E2E_THEME_DIR / MARKET_E2E_PANEL_DIR   host UI checkouts for --ui external
#
# Exit codes (printed as `e2e-instance: FAIL <code> <text>`; 17 section 14, plus the two this script needs):
#   0 ok   2 usage   3 status: not running
#   10 a port is in use (or the instance is already running)   11 Pano jar missing or stale   12 plugin jar missing or the
#   plugins directory does not hold exactly the expected jars   13 database create / fixture load failed
#   14 instance did not become ready in time   15 install (smoke script) failed   16 market plugin did not start (log marker
#   missing or an ERROR line mentions the market)   17 stop / kill / restart without a recorded PID
#   18 the JRE 11 binary is missing   19 stop: the recorded PID or the port did not go away
# Every process is addressed by the PID this script recorded (and verified to belong to the instance directory); never a pattern.
set -uo pipefail

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
M=$(cd "$SELF/.." && pwd -P)
EMBEDDED=0
if [ -f "$M/../../gradlew" ] && [ -d "$M/../../Pano/src/main" ]; then
  EMBEDDED=1
  P=$(cd "$M/../.." && pwd -P)
fi

BASE="$M/build/market-e2e"
READY_TIMEOUT=${MARKET_E2E_READY_TIMEOUT:-300}
PLUGIN_ID=pano-plugin-market
MARKER='[MarketPlugin] - Started!'

say() { echo "e2e-instance: $*" >&2; }
die() { local code=$1; shift; say "FAIL $code $*"; exit "$code"; }

# ---------------------------------------------------------------------------------------------- arguments

CMD=${1:-}
[ -n "$CMD" ] || die 2 "usage: e2e-instance.sh start|stop|kill|restart|status|install-legacy [options] (see the header)"
shift

NAME=default
HTTP_PORT=
GW_PORT=
UI=
KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME=${2:-}; shift 2 || die 2 "--name needs a value" ;;
    --http-port) HTTP_PORT=${2:-}; shift 2 || die 2 "--http-port needs a value" ;;
    --gateway-port) GW_PORT=${2:-}; shift 2 || die 2 "--gateway-port needs a value" ;;
    --ui) UI=${2:-}; shift 2 || die 2 "--ui needs a value" ;;
    --keep) KEEP=1; shift ;;
    *) die 2 "unknown option $1" ;;
  esac
done

[[ "$NAME" =~ ^[a-z][a-z0-9]{0,19}$ ]] || die 2 "--name must match [a-z][a-z0-9]{0,19}"
if [ "$NAME" = default ]; then
  INSTANCE="$BASE/instance"
  DB_NAME=pano_market_e2e
else
  INSTANCE="$BASE/instance-$NAME"
  DB_NAME="pano_market_e2e_$NAME"
fi
# The only database names this script ever creates or drops.
[[ "$DB_NAME" =~ ^pano_market_e2e(_[a-z][a-z0-9]{0,19})?$ ]] || die 2 "refusing database name $DB_NAME"

for v in HTTP_PORT GW_PORT; do
  val=${!v}
  [ -z "$val" ] || { [[ "$val" =~ ^[0-9]+$ ]] && [ "$val" -ge 1024 ] && [ "$val" -le 65535 ] || die 2 "$v must be a port between 1024 and 65535"; }
done

UI_THEME_PORT=
UI_PANEL_PORT=
parse_ui() {
  [ -n "$UI" ] || return 0
  [[ "$UI" =~ ^external:([0-9]+),([0-9]+)$ ]] || die 2 "--ui must be external:<themePort>,<panelPort>"
  UI_THEME_PORT=${BASH_REMATCH[1]}
  UI_PANEL_PORT=${BASH_REMATCH[2]}
}
parse_ui

PIDFILE="$INSTANCE/pano.pid"
PORTSFILE="$INSTANCE/ports.env"

# restart / stop / kill / status act on the ports the instance was started with.
load_ports() {
  [ -f "$PORTSFILE" ] || return 0
  # shellcheck disable=SC1090
  local k v
  while IFS='=' read -r k v; do
    case "$k" in
      HTTP_PORT) [ -n "$HTTP_PORT" ] || HTTP_PORT=$v ;;
      GW_PORT) [ -n "$GW_PORT" ] || GW_PORT=$v ;;
      UI_THEME_PORT) [ -n "$UI_THEME_PORT" ] || UI_THEME_PORT=$v ;;
      UI_PANEL_PORT) [ -n "$UI_PANEL_PORT" ] || UI_PANEL_PORT=$v ;;
    esac
  done < "$PORTSFILE"
}

default_ports() {
  if [ "$NAME" != default ]; then
    [ -n "$HTTP_PORT" ] && [ -n "$GW_PORT" ] || die 2 "a named instance needs --http-port and --gateway-port"
  fi
  HTTP_PORT=${HTTP_PORT:-18188}
  GW_PORT=${GW_PORT:-18189}
}

# ---------------------------------------------------------------------------------------------- helpers

port_in_use() { [ -n "$(ss -ltnH "sport = :$1" 2>/dev/null)" ]; }

pid_alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

# A recorded PID is trusted only while /proc says it is a java process whose working directory is the instance directory.
is_instance_java() {
  local pid=$1 cwd
  pid_alive "$pid" || return 1
  cwd=$(readlink "/proc/$pid/cwd" 2>/dev/null) || return 1
  [ "$cwd" = "$INSTANCE" ] || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q -- '-jar' || return 1
}

# A recorded UI PID is trusted only while it leads its own process group (we started it with setsid) and runs vite.
is_ui_leader() {
  local pid=$1
  pid_alive "$pid" || return 1
  [ "$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')" = "$pid" ] || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q 'vite' || return 1
}

recorded_pid() { [ -f "$PIDFILE" ] && tr -d ' \n' < "$PIDFILE" || true; }

wait_gone() { # $1 pid, $2 seconds
  local i
  for ((i = 0; i < $2 * 2; i++)); do pid_alive "$1" || return 0; sleep 0.5; done
  return 1
}

http_code() { curl -s -o /dev/null -m 10 -w '%{http_code}' "$1" 2>/dev/null || true; }

wait_until() { # $1 description, then a command; fails with 14
  local what=$1 i; shift
  for ((i = 0; i < READY_TIMEOUT; i += 2)); do "$@" && return 0; sleep 2; done
  return 1
}

jre() {
  JAVA_BIN=${MARKET_E2E_JAVA:-/usr/lib/jvm/java-11-openjdk/bin/java}
  [ -x "$JAVA_BIN" ] || die 18 "JRE 11 not found at $JAVA_BIN (set MARKET_E2E_JAVA)"
}

# ---------------------------------------------------------------------------------------------- database

DB_HOSTPORT=${PANO_IT_MARIADB:-127.0.0.1:3306}
DB_HOST=${DB_HOSTPORT%%:*}
DB_PORT=${DB_HOSTPORT##*:}
DB_CONTAINER=${MARKET_E2E_DB_CONTAINER:-pano-web-platform-db-1}

db_client_kind() {
  [ -n "${PANO_IT_MARIADB_PASSWORD:-}" ] || return 1
  if command -v docker >/dev/null 2>&1 && docker inspect "$DB_CONTAINER" >/dev/null 2>&1; then echo docker; return 0; fi
  if command -v mariadb >/dev/null 2>&1; then echo mariadb; return 0; fi
  if command -v mysql >/dev/null 2>&1; then echo mysql; return 0; fi
  return 1
}

# $1 = SQL text; stdin is passed through when -i is given as $2 (fixture loading)
db_run() {
  local sql=$1 stdin=${2:-} kind
  kind=$(db_client_kind) || return 1
  export MYSQL_PWD=$PANO_IT_MARIADB_PASSWORD
  case "$kind" in
    docker)
      if [ "$stdin" = -i ]; then docker exec -i -e MYSQL_PWD "$DB_CONTAINER" mariadb -uroot --default-character-set=utf8mb4 "$DB_NAME"
      else docker exec -e MYSQL_PWD "$DB_CONTAINER" mariadb -uroot --default-character-set=utf8mb4 -e "$sql"; fi ;;
    *)
      if [ "$stdin" = -i ]; then "$kind" -h "$DB_HOST" -P "$DB_PORT" -uroot --default-character-set=utf8mb4 "$DB_NAME"
      else "$kind" -h "$DB_HOST" -P "$DB_PORT" -uroot --default-character-set=utf8mb4 -e "$sql"; fi ;;
  esac
}

db_recreate() {
  db_run "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" >/dev/null 2>"$BASE/db.err" \
    || die 13 "database $DB_NAME could not be created (PANO_IT_MARIADB / PANO_IT_MARIADB_PASSWORD set? container $DB_CONTAINER up?) $(head -c 200 "$BASE/db.err" 2>/dev/null | sed 's/[Pp]assword[^ ]*/<redacted>/g')"
}

# ---------------------------------------------------------------------------------------------- jars

PANO_JAR=${MARKET_E2E_PANO_JAR:-}
PLUGIN_JAR=${MARKET_E2E_PLUGIN_JAR:-}
FAKE_JAR=${MARKET_E2E_FAKE_JAR:-}

resolve_jars() {
  if [ -z "$PANO_JAR" ]; then
    [ "$EMBEDDED" = 1 ] || die 11 "standalone checkout: set MARKET_E2E_PANO_JAR"
    PANO_JAR="$P/build/libs/Pano-local-build.jar"
    CHECK_STALE=1
  else
    CHECK_STALE=0
  fi
  if [ -z "$PLUGIN_JAR" ]; then
    if [ "$EMBEDDED" = 1 ]; then PLUGIN_JAR="$P/build/plugins/pano-plugin-market-local-build.jar"
    else PLUGIN_JAR="$M/build/libs/pano-plugin-market-local-build.jar"; fi
  fi
  [ -n "$FAKE_JAR" ] || FAKE_JAR="$M/build/fake/pano-plugin-market-fake-local-build.jar"
}

check_pano_jar() {
  [ -f "$PANO_JAR" ] || die 11 "Pano jar missing: $PANO_JAR (build it: gradlew :Pano:jar)"
  if [ "$CHECK_STALE" = 1 ]; then
    # UIFiles holds the owner's staged UI zips; they are not source and do not make the jar stale.
    local newer
    newer=$(find "$P/Pano/src/main" -type f -not -path '*/resources/UIFiles/*' -newer "$PANO_JAR" -print -quit 2>/dev/null)
    [ -z "$newer" ] || die 11 "Pano jar is older than ${newer#"$P"/} (rebuild: gradlew :Pano:jar)"
  fi
}

check_plugin_jars() {
  [ -f "$PLUGIN_JAR" ] || die 12 "market plugin jar missing: $PLUGIN_JAR"
  [ -f "$FAKE_JAR" ] || die 12 "fake provider jar missing: $FAKE_JAR (gradlew :plugins:pano-plugin-market:fakeProviderJar)"
  [ "$(basename "$PLUGIN_JAR")" != "$(basename "$FAKE_JAR")" ] || die 12 "market and fake jar have the same file name"
}

# instance/plugins must hold exactly the expected file names (never a glob: build/api/ holds the API jar, which must not be copied).
copy_plugin_jars() { # $1 = with | without market jars
  mkdir -p "$INSTANCE/plugins"
  if [ "$1" = with ]; then
    cp -- "$PLUGIN_JAR" "$FAKE_JAR" "$INSTANCE/plugins/" || die 12 "copying the plugin jars failed"
    local expected actual
    expected=$(printf '%s\n%s\n' "$(basename "$PLUGIN_JAR")" "$(basename "$FAKE_JAR")" | sort)
    actual=$(ls -1A "$INSTANCE/plugins" | grep -F ".jar" | sort) # plugin data directories created at run time are not jars
    [ "$expected" = "$actual" ] || die 12 "instance/plugins holds jars [$(echo "$actual" | tr "\n" " ")] instead of exactly the two expected ones"
  fi
}

# ---------------------------------------------------------------------------------------------- lifecycle

record_ports() {
  {
    echo "HTTP_PORT=$HTTP_PORT"
    echo "GW_PORT=$GW_PORT"
    echo "UI_THEME_PORT=$UI_THEME_PORT"
    echo "UI_PANEL_PORT=$UI_PANEL_PORT"
  } > "$PORTSFILE"
}

launch_pano() {
  jre
  local pid
  (
    cd "$INSTANCE" || exit 1
    export PANO_DB_HOST=$DB_HOST PANO_DB_PORT=$DB_PORT PANO_DB_NAME=$DB_NAME PANO_DB_USER=root
    export PANO_DB_PASSWORD=${PANO_IT_MARIADB_PASSWORD:-}
    export PANO_HTTP_PORT=$HTTP_PORT
    # shellcheck disable=SC2086
    exec setsid "$JAVA_BIN" ${MARKET_E2E_JAVA_OPTS:--XX:MaxRAMPercentage=40} \
      -Dpano.market.fakeProvider=true -Dpf4j.pluginsDir=plugins -jar "$PANO_JAR" -nogui \
      </dev/null >pano.log 2>&1
  ) &
  pid=$!
  echo "$pid" > "$PIDFILE"
  sleep 1
  is_instance_java "$pid" || { say "recorded PID $pid is not the instance JVM"; return 1; }
}

# The UI runtimes (bun) are children of the Pano JVM; a SIGKILL orphans them with their ports. Descendants are collected
# BEFORE the signal and reaped afterwards by exact PID, only while they still run from inside the instance directory.
descendants() { # $1 pid -> its descendants, one per line
  local c
  for c in $(ps -o pid= --ppid "$1" 2>/dev/null); do echo "$c"; descendants "$c"; done
}

reap() { # PIDs on stdin
  local pid cwd
  while read -r pid; do
    pid_alive "$pid" || continue
    cwd=$(readlink "/proc/$pid/cwd" 2>/dev/null) || continue
    case "$cwd" in "$INSTANCE"|"$INSTANCE"/*) kill -KILL "$pid" 2>/dev/null ;; esac
  done
}

stop_recorded() { # SIGTERM then wait; returns 0 when gone (or never ran), 19 when it stayed
  local pid
  pid=$(recorded_pid)
  [ -n "$pid" ] || return 0
  if ! pid_alive "$pid"; then rm -f "$PIDFILE"; return 0; fi
  is_instance_java "$pid" || { say "PID $pid in $PIDFILE is not this instance's JVM; not signalling it"; rm -f "$PIDFILE"; return 0; }
  kill -TERM "$pid" 2>/dev/null
  wait_gone "$pid" 90 || return 19
  rm -f "$PIDFILE"
  return 0
}

stop_ui() {
  local f pid
  for f in "$INSTANCE/ui-theme.pid" "$INSTANCE/ui-panel.pid"; do
    [ -f "$f" ] || continue
    pid=$(tr -d ' \n' < "$f")
    if is_ui_leader "$pid"; then
      kill -TERM -- "-$pid" 2>/dev/null
      wait_gone "$pid" 20 || kill -KILL -- "-$pid" 2>/dev/null
    fi
    rm -f "$f"
  done
}

start_ui() {
  [ -n "$UI_THEME_PORT" ] || return 0
  local root theme_dir panel_dir
  root=$(cd "$M/../../.." 2>/dev/null && pwd -P || true)
  theme_dir=${MARKET_E2E_THEME_DIR:-$root/themes/vanilla-theme}
  panel_dir=${MARKET_E2E_PANEL_DIR:-$root/panel-ui}
  [ -x "$theme_dir/node_modules/.bin/vite" ] || { say "theme checkout not usable: $theme_dir (bun install there)"; return 1; }
  [ -x "$panel_dir/node_modules/.bin/vite" ] || { say "panel checkout not usable: $panel_dir (bun install there)"; return 1; }
  local tpid ppid_
  (cd "$theme_dir" && VITE_API_URL="http://127.0.0.1:$HTTP_PORT/api" exec setsid node_modules/.bin/vite dev --host=127.0.0.1 --port "$UI_THEME_PORT" --strictPort \
    </dev/null >"$INSTANCE/ui-theme.log" 2>&1) &
  tpid=$!
  echo "$tpid" > "$INSTANCE/ui-theme.pid"
  (cd "$panel_dir" && DEV=true VITE_API_URL="http://127.0.0.1:$HTTP_PORT/panel/api" exec setsid node_modules/.bin/vite dev --host=127.0.0.1 --port "$UI_PANEL_PORT" --strictPort \
    </dev/null >"$INSTANCE/ui-panel.log" 2>&1) &
  ppid_=$!
  echo "$ppid_" > "$INSTANCE/ui-panel.pid"
  local p
  for p in "$UI_THEME_PORT" "$UI_PANEL_PORT"; do
    wait_until "UI on $p" port_in_use "$p" || { say "host UI on port $p did not start (see $INSTANCE/ui-*.log)"; return 1; }
  done
  return 0
}

health_ok() { [ "$(http_code "http://127.0.0.1:$HTTP_PORT/api/health")" = 200 ]; }
store_ok() { [ "$(http_code "http://127.0.0.1:$HTTP_PORT/api/market/store")" = 200 ]; }

# The vendored copy (with PANO_DB_PREFIX) is used in every layout; the platform original cannot set a table prefix.
smoke_script() { echo "$M/scripts/smoke-install.sh"; }

install_pano() {
  local pw
  if [ ! -f "$INSTANCE/admin.env" ]; then
    pw="Aa1-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    ( umask 077; printf 'SMOKE_ADMIN_USER=smokeadmin\nSMOKE_ADMIN_PASSWORD=%s\n' "$pw" > "$INSTANCE/admin.env" )
    chmod 600 "$INSTANCE/admin.env"
  fi
  pw=$(sed -n 's/^SMOKE_ADMIN_PASSWORD=//p' "$INSTANCE/admin.env")
  PANO_URL="http://127.0.0.1:$HTTP_PORT" PANO_DB_HOST=$DB_HOST PANO_DB_PORT=$DB_PORT PANO_DB_NAME=$DB_NAME PANO_DB_USER=root \
    PANO_DB_PASSWORD=${PANO_IT_MARIADB_PASSWORD:-} PANO_DB_PREFIX=pano_ SMOKE_ADMIN_PASSWORD=$pw SMOKE_TIMEOUT=$READY_TIMEOUT \
    "$(smoke_script)" >"$INSTANCE/smoke-install.log" 2>&1
}

market_marker_ok() { grep -qF -- "$MARKER" "$INSTANCE/pano.log" 2>/dev/null; }

market_errors() { grep -E '(^|[^A-Za-z])ERROR([^A-Za-z]|$)' "$INSTANCE/pano.log" 2>/dev/null | grep -i 'market' || true; }

abort_boot() { # kill what we started, keep the logs
  local code=$1; shift
  stop_ui
  local pid; pid=$(recorded_pid)
  if is_instance_java "$pid"; then
    local kids; kids=$(descendants "$pid")
    kill -TERM "$pid" 2>/dev/null
    wait_gone "$pid" 60 || kill -KILL "$pid" 2>/dev/null
    rm -f "$PIDFILE"
    echo "$kids" | reap
  fi
  die "$code" "$* (logs: $INSTANCE/pano.log, $INSTANCE/smoke-install.log)"
}

boot_and_wait() { # $1 = install | keep ; assumes the JVM is not running
  launch_pano || abort_boot 14 "the JVM did not start"
  wait_until "health" health_ok || abort_boot 14 "no GET /api/health 200 within ${READY_TIMEOUT}s"
  if [ "$1" = install ] && [ ! -f "$INSTANCE/installed" ]; then
    install_pano || abort_boot 15 "the install (smoke-install.sh) failed"
    : > "$INSTANCE/installed"
    post_install_restart
    trust_loopback_proxy
  fi
}

# F-18 (evidence/E2E-04.md, CP-3): the real ClientIpResolver trusts X-Forwarded-For only from a listed proxy, so the IP block of F-18 is provable over HTTP only when the
# instance lists the loopback address of the test JVM. The key is written while the JVM is stopped (Pano rewrites config.conf on shutdown), then the instance boots again.
# The lifecycle instance keeps its own flow; MARKET_E2E_NO_TRUSTED_LOOPBACK=1 switches this off.
trust_loopback_proxy() {
  [ -z "${MARKET_E2E_NO_TRUSTED_LOOPBACK:-}" ] || return 0
  case "$INSTANCE" in *-lifecycle) return 0 ;; esac
  [ -f "$INSTANCE/plugins/$(basename "$PLUGIN_JAR")" ] || return 0
  grep -qE '^[[:space:]]*trusted-proxies[[:space:]]*=[[:space:]]*\[[[:space:]]*\]' "$INSTANCE/config.conf" 2>/dev/null || return 0
  stop_recorded || abort_boot 19 "the install JVM did not exit to trust the loopback proxy"
  sed -i -E 's/^([[:space:]]*)trusted-proxies[[:space:]]*=[[:space:]]*\[[[:space:]]*\]/\1trusted-proxies = ["127.0.0.1", "::1"]/' "$INSTANCE/config.conf"
  mv -f "$INSTANCE/pano.log" "$INSTANCE/pano-trust.log" 2>/dev/null
  launch_pano || abort_boot 14 "the JVM did not start after trusting the loopback proxy"
  wait_until "health" health_ok || abort_boot 14 "no GET /api/health 200 after trusting the loopback proxy"
}

# Finding of MK-012 (see evidence/MK-012.md): the market plugin of a JVM that was booted BEFORE the setup finished does not
# initialise when the setup finishes (no "Setup finished! Initializing plugin..." line, no marker, /api/market/store keeps
# failing); a boot of the installed instance does. Until that is fixed (MK-020 owns MarketPlugin) the instance is restarted once
# after the install. MARKET_E2E_NO_POST_INSTALL_RESTART=1 switches this off to verify the fix.
post_install_restart() {
  [ -z "${MARKET_E2E_NO_POST_INSTALL_RESTART:-}" ] || return 0
  [ -f "$INSTANCE/plugins/$(basename "$PLUGIN_JAR")" ] || return 0 # install-legacy installs without the market
  local i
  # The marker alone is not proof: a plugin that finished its setup hook after the wizard logs "Started!" with its beans missing
  # (MarketBootstrap "No qualifying bean ...", /api/market/store 500), so the store route must answer too (MK-080).
  for ((i = 0; i < 20; i++)); do market_marker_ok && store_ok && return 0; sleep 1; done
  say "note: the market did not initialise in the install JVM; restarting it once (post-install restart, see evidence/MK-012.md)"
  stop_recorded || abort_boot 19 "the install JVM did not exit for the post-install restart"
  mv -f "$INSTANCE/pano.log" "$INSTANCE/pano-install.log" 2>/dev/null
  launch_pano || abort_boot 14 "the JVM did not start after the install"
  wait_until "health" health_ok || abort_boot 14 "no GET /api/health 200 after the post-install restart"
}

wait_market() {
  wait_until "marker" market_marker_ok || abort_boot 16 "log marker '$MARKER' missing within ${READY_TIMEOUT}s"
  # MARKET_E2E_ALLOW_DEGRADED=1 (17 L-04): the instance is meant to run with a broken schema, so the market answers 503 STORE_UNAVAILABLE and logs
  # an ERROR; ready then means "the marker is there and the platform answers", nothing more.
  if [ -n "${MARKET_E2E_ALLOW_DEGRADED:-}" ]; then
    wait_until "health" health_ok || abort_boot 14 "no GET /api/health 200 within ${READY_TIMEOUT}s"
    return 0
  fi
  wait_until "store" store_ok || abort_boot 14 "no GET /api/market/store 200 within ${READY_TIMEOUT}s"
  local errs; errs=$(market_errors)
  [ -z "$errs" ] || abort_boot 16 "ERROR line(s) mention the market: $(echo "$errs" | head -n 2 | cut -c1-200)"
}

print_exports() {
  echo "export MARKET_E2E_URL=http://127.0.0.1:$HTTP_PORT"
  echo "export MARKET_E2E_DIR=$INSTANCE"
  echo "export MARKET_E2E_GATEWAY_PORT=$GW_PORT"
  echo "export MARKET_E2E_DB=$DB_NAME"
  if [ -n "$UI_THEME_PORT" ]; then
    echo "export MARKET_E2E_THEME_URL=http://127.0.0.1:$UI_THEME_PORT"
    echo "export MARKET_E2E_PANEL_URL=http://127.0.0.1:$UI_PANEL_PORT"
  fi
}

preflight_ports() { # $1 = fresh | keep; the gateway port belongs to the test JVM's FakePayGateway, so it is only checked on a fresh start
  local p ports="$HTTP_PORT $UI_THEME_PORT $UI_PANEL_PORT"
  [ "$1" = fresh ] && ports="$ports $GW_PORT"
  for p in $ports; do
    port_in_use "$p" && die 10 "port $p is in use"
  done
  local pid; pid=$(recorded_pid)
  if is_instance_java "$pid"; then die 10 "instance '$NAME' is already running (PID $pid); stop it first"; fi
  return 0
}

cmd_start() { # $1 = fresh | keep
  default_ports
  [ "$1" = keep ] && load_ports
  preflight_ports "$1"
  resolve_jars
  check_pano_jar
  check_plugin_jars
  mkdir -p "$BASE"
  if [ "$1" = fresh ]; then
    case "$INSTANCE" in "$BASE"/instance|"$BASE"/instance-*) rm -rf -- "$INSTANCE" ;; *) die 2 "refusing to delete $INSTANCE" ;; esac
    mkdir -p "$INSTANCE"
    db_recreate
  else
    [ -d "$INSTANCE" ] || die 17 "--keep: no instance directory $INSTANCE (run start first)"
    rm -f "$INSTANCE/plugins/"*.jar
  fi
  copy_plugin_jars with
  record_ports
  boot_and_wait install
  wait_market
  if ! start_ui; then abort_boot 14 "the host UIs did not start"; fi
  say "ok: instance '$NAME' up (PID $(recorded_pid), http $HTTP_PORT, database $DB_NAME)"
  print_exports
}

cmd_stop() {
  load_ports
  local pid; pid=$(recorded_pid)
  stop_ui
  if [ -z "$pid" ]; then say "not running (no recorded PID)"; return 0; fi
  stop_recorded || die 19 "PID $pid did not exit after SIGTERM"
  [ -z "$HTTP_PORT" ] || ! port_in_use "$HTTP_PORT" || die 19 "port $HTTP_PORT is still in use after the stop"
  say "stopped (PID $pid)"
}

cmd_kill() {
  load_ports
  local pid; pid=$(recorded_pid)
  [ -n "$pid" ] || die 17 "no recorded PID for instance '$NAME'"
  is_instance_java "$pid" || { rm -f "$PIDFILE"; die 17 "recorded PID $pid is not a running instance JVM"; }
  stop_ui
  local kids; kids=$(descendants "$pid")
  kill -KILL "$pid" 2>/dev/null
  wait_gone "$pid" 20 || die 19 "PID $pid survived SIGKILL"
  # the PID file stays: restart must find the dead PID (stop_recorded removes a file whose PID is gone)
  echo "$kids" | reap
  say "killed (PID $pid)"
}

cmd_restart() {
  load_ports
  local pid; pid=$(recorded_pid)
  [ -n "$pid" ] || die 17 "no recorded PID for instance '$NAME'"
  stop_ui
  stop_recorded || die 19 "PID $pid did not exit after SIGTERM"
  cmd_start keep
}

cmd_status() {
  load_ports
  local pid; pid=$(recorded_pid)
  if [ -n "$pid" ] && is_instance_java "$pid"; then
    echo "running pid=$pid http=${HTTP_PORT:-?} db=$DB_NAME health=$(http_code "http://127.0.0.1:${HTTP_PORT:-0}/api/health") dir=$INSTANCE"
    return 0
  fi
  echo "not running (instance '$NAME', db $DB_NAME)"
  return 3
}

cmd_install_legacy() {
  default_ports
  preflight_ports fresh
  resolve_jars
  check_pano_jar
  check_plugin_jars
  mkdir -p "$BASE"
  case "$INSTANCE" in "$BASE"/instance|"$BASE"/instance-*) rm -rf -- "$INSTANCE" ;; *) die 2 "refusing to delete $INSTANCE" ;; esac
  mkdir -p "$INSTANCE"
  db_recreate
  copy_plugin_jars without
  record_ports
  boot_and_wait install
  stop_recorded || die 19 "the legacy install JVM did not exit"
  local fx="$M/src/test/resources/fixtures"
  [ -f "$fx/schema-v2.sql" ] && [ -f "$fx/seed-v2.sql" ] || die 13 "fixtures missing under $fx"
  db_run "" -i < "$fx/schema-v2.sql" 2>"$BASE/db.err" || die 13 "schema-v2.sql did not load: $(head -c 200 "$BASE/db.err")"
  db_run "" -i < "$fx/seed-v2.sql" 2>"$BASE/db.err" || die 13 "seed-v2.sql did not load: $(head -c 200 "$BASE/db.err")"
  # Optional extra fixture rows of a scenario (LifecycleE2E L-01b adds a legacy payment method row of an installed provider): a file of SQL.
  if [ -n "${MARKET_E2E_LEGACY_EXTRA_SQL:-}" ]; then
    [ -f "$MARKET_E2E_LEGACY_EXTRA_SQL" ] || die 13 "MARKET_E2E_LEGACY_EXTRA_SQL is not a file: $MARKET_E2E_LEGACY_EXTRA_SQL"
    db_run "" -i < "$MARKET_E2E_LEGACY_EXTRA_SQL" 2>"$BASE/db.err" || die 13 "MARKET_E2E_LEGACY_EXTRA_SQL did not load: $(head -c 200 "$BASE/db.err")"
  fi
  # Without this row the platform runs initPluginDB (fresh install) instead of the migration chain.
  db_run "USE \`$DB_NAME\`; INSERT INTO \`pano_scheme_version\` (\`pluginId\`, \`key\`, \`extra\`) VALUES ('$PLUGIN_ID', '2', 'e2e legacy fixture')" >/dev/null 2>"$BASE/db.err" \
    || die 13 "the plugin scheme-version row could not be inserted: $(head -c 200 "$BASE/db.err")"
  copy_plugin_jars with
  boot_and_wait keep
  wait_market
  if ! start_ui; then abort_boot 14 "the host UIs did not start"; fi
  say "ok: legacy instance '$NAME' up (scheme version 2 data, PID $(recorded_pid))"
  print_exports
}

cmd_start_presetup() {
  default_ports
  preflight_ports fresh
  resolve_jars
  check_pano_jar
  check_plugin_jars
  mkdir -p "$BASE"
  case "$INSTANCE" in "$BASE"/instance|"$BASE"/instance-*) rm -rf -- "$INSTANCE" ;; *) die 2 "refusing to delete $INSTANCE" ;; esac
  mkdir -p "$INSTANCE"
  db_recreate
  copy_plugin_jars with
  record_ports
  launch_pano || abort_boot 14 "the JVM did not start"
  wait_until "health" health_ok || abort_boot 14 "no GET /api/health 200 within ${READY_TIMEOUT}s"
  say "ok: instance '$NAME' up before setup (PID $(recorded_pid), http $HTTP_PORT, database $DB_NAME)"
  print_exports
}

cmd_finish_setup() {
  load_ports
  local pid; pid=$(recorded_pid)
  is_instance_java "$pid" || die 17 "instance '$NAME' is not running (start-presetup first)"
  [ ! -f "$INSTANCE/installed" ] || die 2 "instance '$NAME' is already installed"
  install_pano || abort_boot 15 "the install (smoke-install.sh) failed"
  : > "$INSTANCE/installed"
  # No restart: the market of this JVM has to initialise by itself when the setup finishes (01 section 14.4).
  [ "$(recorded_pid)" = "$pid" ] || die 17 "the instance PID changed during the setup"
  wait_market
  say "ok: setup finished without a restart (PID $pid, http $HTTP_PORT)"
  print_exports
}

case "$CMD" in
  start-presetup) cmd_start_presetup ;;
  finish-setup) cmd_finish_setup ;;
  start) if [ "$KEEP" = 1 ]; then cmd_start keep; else cmd_start fresh; fi ;;
  stop) cmd_stop ;;
  kill) cmd_kill ;;
  restart) cmd_restart ;;
  status) cmd_status ;;
  install-legacy) cmd_install_legacy ;;
  *) die 2 "unknown command '$CMD'" ;;
esac
