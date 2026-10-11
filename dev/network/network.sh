#!/usr/bin/env bash
# A three-server Postal network behind a Velocity proxy, sharing one MariaDB mail store, for testing letters
# between servers by hand (join the proxy with a client) or from a script. See dev/network/README.md.
#
#   big    the SMP world (dev/towns/build_smp.py): 12 towns x 20 houses, every route surveyed
#   small  the test towns (dev/towns/build_towns.py): 4 towns, 21 addresses with hand-made routes
#   dims   one network over three dimensions (dev/towns/build_dims.py): Central and Brookfield in the
#          Overworld, Cinderholm in the Nether, Voidreach in the End; every route surveyed
#
#   dev/network/network.sh setup [--offline] [--op NAME] [--no-build] [--accept-eula]
#                                         download everything, build the three worlds, survey their routes
#   dev/network/network.sh start          start MariaDB, the three servers and the proxy (in the background)
#   dev/network/network.sh stop           stop them (only the processes this script started)
#   dev/network/network.sh status         what's running, and where to join
#   dev/network/network.sh cmd SERVER COMMAND...
#                                         run console commands on big, small or dims (prints the replies)
#   dev/network/network.sh log SERVER     follow a server's log (big, small, dims or proxy)
#   dev/network/network.sh check          send test letters between every pair of servers and across the
#                                         dimensions, wait for them, and report what was delivered
#   dev/network/network.sh player         a headless player moves between the servers through the proxy with a
#                                         letter it posted (needs setup --offline)
#   dev/network/network.sh reset          stop everything and delete the servers and the MariaDB container
#                                         (downloads and settings such as --op are kept)
#
# Needs bash 4+, Java 25+, python3, curl, timeout (coreutils) and Docker. On macOS: brew install bash coreutils.
#
# Env: JAVA (Java 25+; default: java), NET_DIR (default: <repo>/dev-network), NET_PORT (the proxy's port,
#      default 25630; the servers use the next six: game ports +1..+3, RCON +4..+6),
#      MYSQL_HOST/MYSQL_PORT/MYSQL_DATABASE/MYSQL_USER/MYSQL_PASSWORD (use an existing MySQL or MariaDB instead
#      of a Docker container), DEV_BALANCE (players' starting money, default 100000),
#      BIG_TOWNS / BIG_ADDRESSES (the big world's size, default 12 x 20), CHECK_MINUTES (check's wait, default 12)
set -euo pipefail
[ "${BASH_VERSINFO[0]}" -ge 4 ] || { echo "error: needs bash 4 or newer (macOS: brew install bash)" >&2; exit 1; }

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=ci/lib.sh
source "$REPO/ci/lib.sh"

JAVA="${JAVA:-java}"
NET_DIR="${NET_DIR:-$REPO/dev-network}"
CACHE="$NET_DIR/cache"
PROXY="$NET_DIR/proxy"
NET_PORT="${NET_PORT:-25630}"
SERVERS=(big small dims)
declare -A PORT=([big]=$((NET_PORT + 1)) [small]=$((NET_PORT + 2)) [dims]=$((NET_PORT + 3)))
declare -A RCON=([big]=$((NET_PORT + 4)) [small]=$((NET_PORT + 5)) [dims]=$((NET_PORT + 6)))
declare -A HEAP=([big]=3G [small]=2G [dims]=2G)
declare -A VEHICLE=([big]="the airship" [small]="the mail coach" [dims]="the ender barge")
ESC=$'\x1b'                        # for stripping colour codes (BSD sed has no \x1b)
DB_CONTAINER=postal-net-mariadb
MARKER="$NET_DIR/.postal-network"   # only a directory with this file in it is ever cleaned up
DB_PORT_LOCAL=3307
DEV_BALANCE="${DEV_BALANCE:-100000}"
BIG_TOWNS="${BIG_TOWNS:-12}"
BIG_ADDRESSES="${BIG_ADDRESSES:-20}"

die() { echo "error: $*" >&2; exit 1; }
info() { echo "==> $*"; }
usage() { sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

server_dir() { echo "$NET_DIR/$1"; }
is_server() { case "$1" in big|small|dims) return 0 ;; *) return 1 ;; esac; }

db_host() { echo "${MYSQL_HOST:-127.0.0.1}"; }
db_port() { if [ -n "${MYSQL_HOST:-}" ]; then echo "${MYSQL_PORT:-3306}"; else echo "$DB_PORT_LOCAL"; fi; }

check_tools() {
    command -v curl >/dev/null || die "curl is required"
    command -v python3 >/dev/null || die "python3 is required"
    TIMEOUT="$(command -v timeout || command -v gtimeout || true)"
    [ -n "$TIMEOUT" ] || die "timeout is required (macOS: brew install coreutils)"
    local major
    major="$(java_major "$JAVA" 2>/dev/null || true)"
    [ -n "$major" ] && [ "$major" -ge "$REQUIRED_JAVA" ] \
        || die "Paper $PAPER_VERSION and Velocity need Java $REQUIRED_JAVA+; set JAVA=/path/to/java (now: ${major:-none})"
    if [ -z "${MYSQL_HOST:-}" ]; then
        command -v docker >/dev/null || die "docker is required for the MariaDB container (or set MYSQL_HOST)"
    fi
}

rcon_password() { cat "$NET_DIR/rcon-password"; }
rcon() { # server command...
    python3 "$REPO/dev/rcon.py" 127.0.0.1 "${RCON[$1]}" "$(rcon_password)" "${@:2}"
}

pid_of() { cat "$NET_DIR/$1.pid" 2>/dev/null || true; }
# running <name>: its recorded pid is alive and is still our java (a stale pid file after a crash or reboot may
# name someone else's process by now).
running() {
    local pid jar=paper.jar
    pid="$(pid_of "$1")"
    [ "$1" = proxy ] && jar=velocity.jar
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && ps -p "$pid" -o args= 2>/dev/null | grep "$jar" >/dev/null
}
# guard_dir: refuses to touch NET_DIR unless it's new, empty, or one this script made.
guard_dir() {
    if [ -d "$NET_DIR" ] && [ ! -f "$MARKER" ] && [ -n "$(ls -A "$NET_DIR")" ]; then
        die "$NET_DIR isn't empty and wasn't made by this script; pick another NET_DIR"
    fi
    mkdir -p "$NET_DIR"
    touch "$MARKER"
}

# ---- Setup ---------------------------------------------------------------------------------------------

# set_props <file> key=value...: sets keys in a .properties file, adding missing ones.
set_props() {
    python3 - "$@" <<'PY'
import sys
path, pairs = sys.argv[1], [a.split("=", 1) for a in sys.argv[2:]]
try:
    lines = open(path).read().splitlines()
except FileNotFoundError:
    lines = []
want = dict(pairs)
out = []
for line in lines:
    key = line.split("=", 1)[0]
    if key in want:
        line = key + "=" + want.pop(key)
    out.append(line)
out += [k + "=" + v for k, v in want.items()]
open(path, "w").write("\n".join(out) + "\n")
PY
}

# set_yaml <file> Block.Key=value...: sets a key anywhere inside a top-level block of Postal's config (or of
# paper-global.yml), adding it under the block if it's missing. A value that was quoted stays quoted.
set_yaml() {
    python3 - "$@" <<'PY'
import sys
path, pairs = sys.argv[1], [a.split("=", 1) for a in sys.argv[2:]]
lines = open(path).read().split("\n")

def indent(line):
    return len(line) - len(line.lstrip(" "))

def is_plain(value):  # written unquoted: booleans and numbers in their canonical form ('0123' stays a string)
    if value in ("true", "false"):
        return True
    try:
        return str(int(value)) == value or str(float(value)) == value
    except ValueError:
        return False

def render(value, quoted):
    return "'" + value.replace("'", "''") + "'" if quoted or not is_plain(value) else value

def section_end(start):  # the first line after start that isn't deeper than it (comments and blanks don't count)
    depth = indent(lines[start])
    for i in range(start + 1, len(lines)):
        if lines[i].strip() and not lines[i].lstrip().startswith("#") and indent(lines[i]) <= depth:
            return i
    return len(lines)

def child(start, name):  # the direct child called name (case-insensitive) of the section at start, or None
    want = indent(lines[start]) + 2 if start >= 0 else 0
    end = section_end(start) if start >= 0 else len(lines)
    for i in range(start + 1, end):
        if indent(lines[i]) == want and lines[i].strip().split(":")[0].lower() == name.lower():
            return i
    return None

def find_anywhere(start, name):  # Postal's config: a key anywhere inside its top-level block (Storage.Host)
    for i in range(start + 1, section_end(start)):
        if lines[i].strip().split(":")[0].lower() == name.lower():
            return i
    return None

for spec, value in pairs:
    parts = spec.split(".")
    at = child(-1, parts[0])
    if at is None:
        lines += [parts[0] + ":"]
        at = len(lines) - 1
    for name in parts[1:-1]:
        nxt = child(at, name)
        if nxt is None:
            sys.exit(f"{path}: no section {name} in {spec}")
        at = nxt
    key = parts[-1]
    i = child(at, key) if len(parts) > 2 else (child(at, key) or find_anywhere(at, key))
    if i is not None:
        old = lines[i].split(":", 1)[1].strip()
        lines[i] = " " * indent(lines[i]) + lines[i].strip().split(":")[0] + ": " + render(value, old.startswith("'"))
    else:
        lines.insert(at + 1, " " * (indent(lines[at]) + 2) + key + ": " + render(value, False))
open(path, "w").write("\n".join(lines))
PY
}

# boot <server> <log> <timeout> <command>...: runs a server in the foreground, feeds it console commands
# once it's up ("sleep:N" waits, "until:REGEX:COUNT" waits until the log has COUNT matching lines), then stops it.
boot() {
    local s="$1" log="$2" limit="$3"; shift 3
    local d; d="$(server_dir "$s")"
    rm -f "$d/logs/latest.log"
    (
        for _ in $(seq 1 300); do
            grep -q 'Done (' "$d/logs/latest.log" 2>/dev/null && break
            sleep 1
        done
        sleep 5
        for cmd in "$@"; do
            case "$cmd" in
                sleep:*) sleep "${cmd#sleep:}" ;;
                until:*)
                    local spec="${cmd#until:}" regex count
                    regex="${spec%:*}"; count="${spec##*:}"
                    for _ in $(seq 1 900); do
                        [ "$(grep -acE "$regex" "$d/logs/latest.log" 2>/dev/null || true)" -ge "$count" ] && break
                        sleep 2
                    done ;;
                *) echo "$cmd"; sleep 1 ;;
            esac
        done
        echo stop
    ) | (cd "$d" && "$TIMEOUT" "$limit" "$JAVA" "-Xmx${HEAP[$s]}" -jar paper.jar nogui) > "$log" 2>&1 || true
}

install_backend() { # server
    local s="$1" d; d="$(server_dir "$1")"
    rm -rf "${d:?}"
    install_server "$CACHE" "$d"
    cp "$POSTAL_JAR" "$d/plugins/Postal.jar"
    echo "eula=true" > "$d/eula.txt"
    set_props "$d/server.properties" "server-port=${PORT[$s]}" "online-mode=false" "motd=Postal network: $s" \
        'level-type=minecraft\:flat' generate-structures=false gamemode=creative difficulty=peaceful \
        spawn-protection=0 allow-flight=true view-distance=8 simulation-distance=6 max-players=10 \
        enable-rcon=true "rcon.port=${RCON[$s]}" "rcon.password=$(rcon_password)" \
        enforce-secure-profile=false server-ip=127.0.0.1
    mkdir -p "$d/world/datapacks"
    case "$s" in
        big) python3 "$REPO/dev/towns/build_smp.py" --towns "$BIG_TOWNS" --addresses "$BIG_ADDRESSES" \
                 --datapack "$d/world/datapacks/postal_smp" --list "$NET_DIR/big-towns.json" ;;
        small) python3 "$REPO/dev/towns/build_towns.py" --datapack "$d/world/datapacks/postal_towns" ;;
        dims) python3 "$REPO/dev/towns/build_dims.py" --datapack "$d/world/datapacks/postal_dims" \
                  --list "$NET_DIR/dims-towns.json" ;;
    esac
}

build_commands() { # server: the console commands that build its world
    case "$1" in
        big)
            echo "function postal_smp:central"; echo "sleep:3"
            for i in $(seq 0 $((BIG_TOWNS - 1))); do echo "function postal_smp:town_$i"; echo "sleep:4"; done ;;
        small) echo "function postal_towns:build"; echo "sleep:10" ;;
        dims) echo "function postal_dims:build"; echo "sleep:15" ;;
    esac
    echo "save-all flush"; echo "sleep:10"
}

configure_backend() { # server online-mode(true|false)
    local s="$1" d config; d="$(server_dir "$1")"; config="$d/plugins/Postal/config.yml"
    [ -f "$config" ] || die "$s didn't write a Postal config; see $NET_DIR/$s-build.log"
    case "$s" in
        big) python3 "$REPO/dev/towns/build_smp.py" --towns "$BIG_TOWNS" --addresses "$BIG_ADDRESSES" --config "$config" ;;
        small) python3 "$REPO/dev/towns/build_towns.py" --config "$config" ;;
        dims) python3 "$REPO/dev/towns/build_dims.py" --config "$config" ;;
    esac
    set_yaml "$config" \
        Storage.Type=mysql "Storage.Host=$(db_host)" "Storage.Port=$(db_port)" \
        "Storage.Database=${MYSQL_DATABASE:-postal_net}" "Storage.User=${MYSQL_USER:-postal}" \
        "Storage.Password=${MYSQL_PASSWORD:-postal}" \
        "Network.Server_id=$s" Network.Poll_seconds=3 Network.Departure_minutes=1 Network.Transit_minutes=0.5 \
        "Network.Vehicle=${VEHICLE[$s]}" \
        Settings.Debug=true Settings.Concurrent_postmen=true Settings.Report_nav_probs=true \
        Settings.Postman_cool_sec=10 Settings.Central_cool_sec=10 Settings.Residence_cool_ticks=40 \
        Settings.Heart_beat_ticks=40 Settings.Heart_beat_auto=false \
        Economy.Use=true
    set_yaml "$d/config/paper-global.yml" proxies.velocity.enabled=true "proxies.velocity.online-mode=$2" \
        "proxies.velocity.secret=$(cat "$PROXY/forwarding.secret")"
    if [ -f "$d/plugins/Essentials/config.yml" ]; then
        sed -i.bak "s/^starting-balance: .*/starting-balance: $DEV_BALANCE/" "$d/plugins/Essentials/config.yml"
    fi
}

write_proxy() { # online-mode(true|false) bind-address
    mkdir -p "$PROXY"
    cp "$CACHE/velocity.jar" "$PROXY/velocity.jar"
    [ -s "$PROXY/forwarding.secret" ] || python3 -c 'import secrets; print(secrets.token_urlsafe(24), end="")' > "$PROXY/forwarding.secret"
    cat > "$PROXY/velocity.toml" <<EOF
# Written by dev/network/network.sh setup.
config-version = "2.9"
bind = "$2:$NET_PORT"
motd = "<#d3a109>Postal test network"
show-max-players = 30
online-mode = $1
force-key-authentication = $1
prevent-client-proxy-connections = false
player-info-forwarding-mode = "MODERN"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
sample-players-in-ping = false
enable-player-address-logging = true

[servers]
big = "127.0.0.1:${PORT[big]}"
small = "127.0.0.1:${PORT[small]}"
dims = "127.0.0.1:${PORT[dims]}"
try = ["small", "big", "dims"]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 3000
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = true
log-player-connections = true
accepts-transfers = false
EOF
}

start_db() {
    [ -n "${MYSQL_HOST:-}" ] && return 0
    if [ "$(docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null || true)" = true ]; then
        return 0
    fi
    if docker inspect "$DB_CONTAINER" >/dev/null 2>&1; then
        info "Starting MariaDB ($DB_CONTAINER)"
        docker start "$DB_CONTAINER" >/dev/null
        for _ in $(seq 1 60); do
            docker exec "$DB_CONTAINER" healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1 && return 0
            sleep 2
        done
        die "MariaDB didn't come back up"
    fi
    die "no database yet; run: dev/network/network.sh setup"
}

survey_and_wait() { # server: surveys every route on its second boot
    local s="$1" towns=() total
    case "$s" in
        big) mapfile -t towns < <(python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))))' "$NET_DIR/big-towns.json")
             total=$(python3 -c 'import json,sys; print(sum(len(t["addresses"]) for t in json.load(open(sys.argv[1])).values()))' "$NET_DIR/big-towns.json") ;;
        dims) mapfile -t towns < <(python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))))' "$NET_DIR/dims-towns.json")
              total=$(python3 -c 'import json,sys; print(sum(len(t["addresses"]) for t in json.load(open(sys.argv[1])).values()))' "$NET_DIR/dims-towns.json") ;;
        *) return 0 ;;
    esac
    local cmds=()
    for t in "${towns[@]}"; do cmds+=("postal survey $t"); done
    info "$s: surveying $total routes"
    boot "$s" "$NET_DIR/$s-survey.log" 3600 "${cmds[@]}" "until:Route [A-Za-z0-9]+, [A-Za-z0-9]+: :$total" "sleep:5"
    local found failed
    found=$(sed "s/${ESC}\[[0-9;]*m//g" "$NET_DIR/$s-survey.log" | grep -acE "Route [A-Za-z0-9]+, [A-Za-z0-9]+: [0-9]+ waypoints" || true)
    failed=$(sed "s/${ESC}\[[0-9;]*m//g" "$NET_DIR/$s-survey.log" | grep -aE "Route [A-Za-z0-9]+, [A-Za-z0-9]+: " | grep -avE ": [0-9]+ waypoints" || true)
    info "$s: $found of $total routes surveyed"
    [ -z "$failed" ] || { echo "$failed" | sed 's/^/    no route: /'; }
}

cmd_setup() {
    local offline=0 op="" build=1 accept_eula=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --offline) offline=1 ;;
            --op) shift; op="${1:?--op needs a player name}" ;;
            --no-build) build=0 ;;
            --accept-eula) accept_eula=1 ;;
            *) die "unknown option for setup: $1" ;;
        esac
        shift
    done
    check_tools
    if running proxy || running big || running small || running dims; then
        die "the network is running; stop it first"
    fi
    if [ "$accept_eula" -ne 1 ]; then
        echo "Running Minecraft servers requires accepting the Minecraft EULA: https://aka.ms/MinecraftEULA"
        printf "Do you accept it? [y/N] "
        read -r answer
        case "$answer" in [yY]*) ;; *) die "EULA not accepted; nothing installed." ;; esac
    fi
    if [ -n "$op" ]; then
        case "$op" in *[!A-Za-z0-9_]*) die "'$op' is not a valid Minecraft username" ;; esac
    fi
    if [ "$build" -eq 1 ]; then
        info "Building Postal"
        (cd "$REPO" && ./mvnw -B -q package -DskipTests)
    fi
    POSTAL_JAR="$(ls -t "$REPO"/target/va_postal-*.jar 2>/dev/null | head -1 || true)"
    [ -n "$POSTAL_JAR" ] || die "no Postal jar in target/; build first (./mvnw package)"

    guard_dir
    mkdir -p "$CACHE"
    info "Downloading Paper $PAPER_VERSION, the plugins and Velocity (cached in $CACHE)"
    download_server_jars "$CACHE"
    fetch "$VELOCITY_URL" "$CACHE/velocity.jar"
    [ -s "$NET_DIR/rcon-password" ] || python3 -c 'import secrets; print(secrets.token_urlsafe(24), end="")' > "$NET_DIR/rcon-password"
    echo "${op}" > "$NET_DIR/op-player"
    local online=true
    [ "$offline" -eq 1 ] && online=false
    echo "$online" > "$NET_DIR/online-mode"
    # Offline, anyone who can reach the proxy can join as any name (your op included): keep it on this machine.
    if [ "$online" = true ]; then write_proxy true 0.0.0.0; else write_proxy false 127.0.0.1; fi

    if [ -z "${MYSQL_HOST:-}" ]; then
        info "Starting a fresh MariaDB ($DB_CONTAINER on port $DB_PORT_LOCAL)"
        MARIADB_NAME="$DB_CONTAINER" MARIADB_PORT="$DB_PORT_LOCAL" "$REPO/ci/start-mariadb.sh" postal_net
    fi

    # One server first, so it downloads the libraries Paper and Citizens load at startup; the others get a copy
    # (two downloads at once can fail one of them).
    local s
    info "big: building $BIG_TOWNS towns (a few minutes)"
    install_backend big
    mapfile -t cmds < <(build_commands big)
    boot big "$NET_DIR/big-build.log" 1800 "${cmds[@]}"
    grep -q "Error occurred while enabling Citizens" "$NET_DIR/big-build.log" \
        && die "Citizens couldn't load its libraries (a download failed); run setup again"
    keep_libraries "$CACHE" "$(server_dir big)"
    for s in small dims; do
        info "$s: building its world"
        install_backend "$s"
        mapfile -t cmds < <(build_commands "$s")
        boot "$s" "$NET_DIR/$s-build.log" 900 "${cmds[@]}" &
    done
    wait
    for s in "${SERVERS[@]}"; do configure_backend "$s" "$online"; done
    survey_and_wait big &
    survey_and_wait dims &
    wait
    info "Ready. Next: dev/network/network.sh start"
}

# ---- Running -------------------------------------------------------------------------------------------

wait_done() { # name log
    for _ in $(seq 1 300); do
        grep -q 'Done (' "$2" 2>/dev/null && return 0
        running "$1" || die "$1 stopped while starting; see $2"
        sleep 1
    done
    die "$1 didn't start within 5 minutes; see $2"
}

cmd_start() {
    check_tools
    [ -f "$PROXY/velocity.toml" ] || die "no network yet; run: dev/network/network.sh setup"
    start_db
    local s d
    for s in "${SERVERS[@]}"; do
        if running "$s"; then info "$s is already running"; continue; fi
        d="$(server_dir "$s")"
        rm -f "$d/logs/latest.log"
        (cd "$d" && exec nohup "$JAVA" "-Xmx${HEAP[$s]}" -jar paper.jar nogui < /dev/null > "$NET_DIR/$s.out" 2>&1) &
        echo $! > "$NET_DIR/$s.pid"
    done
    if ! running proxy; then
        (cd "$PROXY" && exec nohup "$JAVA" -Xmx512M -jar velocity.jar < /dev/null > "$NET_DIR/proxy.out" 2>&1) &
        echo $! > "$NET_DIR/proxy.pid"
    fi
    info "Waiting for the servers to start"
    for s in "${SERVERS[@]}"; do wait_done "$s" "$(server_dir "$s")/logs/latest.log"; done
    wait_done proxy "$NET_DIR/proxy.out"
    local op; op="$(cat "$NET_DIR/op-player" 2>/dev/null || true)"
    sleep 3
    local cmds=("postal start")
    [ -n "$op" ] && cmds=("op $op" "postal start")
    for s in "${SERVERS[@]}"; do
        local tries=0
        until rcon "$s" "${cmds[@]}" >/dev/null 2>&1; do
            tries=$((tries + 1))
            [ "$tries" -lt 10 ] || die "couldn't reach $s over RCON on port ${RCON[$s]}"
            sleep 3
        done
    done
    cmd_status
}

cmd_stop() {
    local s pid
    for s in proxy "${SERVERS[@]}"; do
        running "$s" || { rm -f "$NET_DIR/$s.pid"; continue; }
        pid="$(pid_of "$s")"
        info "Stopping $s (pid $pid)"
        kill -TERM "$pid" 2>/dev/null || true
    done
    for s in proxy "${SERVERS[@]}"; do
        running "$s" || { rm -f "$NET_DIR/$s.pid"; continue; }
        pid="$(pid_of "$s")"
        for _ in $(seq 1 90); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
        if running "$s"; then kill -9 "$pid" 2>/dev/null || true; fi
        rm -f "$NET_DIR/$s.pid"
    done
    if [ -z "${MYSQL_HOST:-}" ] && docker inspect "$DB_CONTAINER" >/dev/null 2>&1; then
        docker stop "$DB_CONTAINER" >/dev/null && info "Stopped MariaDB ($DB_CONTAINER); its data is kept"
    fi
}

cmd_status() {
    local s
    for s in proxy "${SERVERS[@]}"; do
        if running "$s"; then printf '  %-6s running (pid %s)' "$s" "$(pid_of "$s")"; else printf '  %-6s stopped' "$s"; fi
        if [ "$s" = proxy ]; then echo "  port $NET_PORT"; else echo "  port ${PORT[$s]}, RCON ${RCON[$s]}"; fi
    done
    if running proxy; then
        echo "Join localhost:$NET_PORT (online mode: $(cat "$NET_DIR/online-mode")). You start on small;"
        echo "switch with /server big, /server small or /server dims."
    fi
}

cmd_cmd() {
    local s="${1:-}"; shift || true
    is_server "$s" || die "usage: network.sh cmd big|small|dims COMMAND..."
    [ $# -gt 0 ] || die "no command given"
    running "$s" || die "$s isn't running"
    rcon "$s" "$*"   # the words after the server are one command: cmd dims postal network
}

cmd_log() {
    case "${1:-}" in
        proxy) tail -n 50 -f "$NET_DIR/proxy.out" ;;
        big|small|dims) tail -n 50 -f "$(server_dir "$1")/logs/latest.log" ;;
        *) die "usage: network.sh log big|small|dims|proxy" ;;
    esac
}

cmd_reset() {
    [ -f "$MARKER" ] || die "$NET_DIR wasn't made by network.sh; not deleting anything"
    cmd_stop
    local s
    for s in "${SERVERS[@]}"; do rm -rf "$(server_dir "$s")"; done
    rm -rf "${PROXY:?}" "$NET_DIR"/*.json "$NET_DIR"/*.log "$NET_DIR"/*.out
    if [ -z "${MYSQL_HOST:-}" ]; then docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true; fi
    info "Removed the servers and the database. Downloads are kept in $CACHE."
    if [ -n "${MYSQL_HOST:-}" ]; then
        echo "    Your own database ($MYSQL_HOST) was left alone: empty it before the next setup."
    fi
}

case "${1:-}" in
    setup) shift; cmd_setup "$@" ;;
    start) shift; cmd_start "$@" ;;
    stop) shift; cmd_stop "$@" ;;
    status) shift; cmd_status "$@" ;;
    cmd) shift; cmd_cmd "$@" ;;
    log) shift; cmd_log "$@" ;;
    check) shift; exec "$REPO/dev/network/check.sh" "$@" ;;
    player) shift; exec "$REPO/dev/network/player.sh" "$@" ;;
    reset) shift; cmd_reset "$@" ;;
    -h|--help|help|"") usage 0 ;;
    *) echo "unknown command: $1" >&2; usage 1 ;;
esac
