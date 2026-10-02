#!/usr/bin/env bash
# Local Paper test server for Postal: same Paper and plugin builds as CI (ci/lib.sh), debug output
# on, and an optional pre-built test network, so you can join with a client and test by hand.
#
#   dev/test-server.sh setup [--accept-eula] [--offline] [--op NAME]
#                                                          download Paper + plugins, create the server
#   dev/test-server.sh start [--no-build] [--seed] [--op NAME]
#                                                          build Postal, install it, run the server
#                                                          (your saved account is opped automatically)
#   dev/test-server.sh report                              bundle logs + config for a bug report
#   dev/test-server.sh reset [--all]                       wipe worlds/plugin data (--all: whole server)
#
# Env: JAVA (Java 25+; default: 'java' if it's 25+, else a JDK downloaded into dev-server/jdk),
#      DEV_DIR (default: <repo>/dev-server), PAPER_VERSION
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=ci/lib.sh
source "$REPO/ci/lib.sh"

JAVA_FROM_ENV="${JAVA:-}"
DEV_DIR="${DEV_DIR:-$REPO/dev-server}"
BUNDLED_JDK="$DEV_DIR/jdk"
CACHE="$DEV_DIR/cache"
SERVER="$DEV_DIR/server"
REPORTS="$DEV_DIR/reports"
RCON_PORT=25575
POSTAL_CONFIG="$SERVER/plugins/Postal/config.yml"
OP_FILE="$DEV_DIR/op-player"  # your Minecraft username, opped on every start

die() { echo "error: $*" >&2; exit 1; }
info() { echo "==> $*"; }

usage() {
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

java_ok() { # java-binary: true if it exists and is Java REQUIRED_JAVA+
    local major
    command -v "$1" >/dev/null 2>&1 || [ -x "$1" ] || return 1
    major="$(java_major "$1")"
    [ -n "$major" ] && [ "$major" -ge "$REQUIRED_JAVA" ]
}

# download_jdk: a private Temurin JDK into dev-server/jdk (no system install, no sudo).
download_jdk() {
    local os arch url tmp home
    case "$(uname -s)" in
        Linux) os=linux ;;
        Darwin) os=mac ;;
        *) die "can't download a JDK for $(uname -s); install Java $REQUIRED_JAVA+ yourself and set JAVA=/path/to/java" ;;
    esac
    case "$(uname -m)" in
        x86_64|amd64) arch=x64 ;;
        arm64|aarch64) arch=aarch64 ;;
        *) die "can't download a JDK for $(uname -m); install Java $REQUIRED_JAVA+ yourself and set JAVA=/path/to/java" ;;
    esac
    url="https://api.adoptium.net/v3/binary/latest/$REQUIRED_JAVA/ga/$os/$arch/jdk/hotspot/normal/eclipse"
    info "Downloading Temurin JDK $REQUIRED_JAVA ($os/$arch) into $BUNDLED_JDK (your system Java is left alone)"
    tmp="$DEV_DIR/jdk-download"
    rm -rf "$tmp" "$BUNDLED_JDK"
    mkdir -p "$tmp"
    curl -fSL --retry 3 --progress-bar -o "$tmp/jdk.tar.gz" "$url"
    tar -xzf "$tmp/jdk.tar.gz" -C "$tmp"
    # Linux: jdk-25.../bin/java; macOS: jdk-25.../Contents/Home/bin/java
    home="$(dirname "$(dirname "$(find "$tmp" -path '*/bin/java' -type f | head -1)")")"
    [ -x "$home/bin/java" ] || die "downloaded JDK has no bin/java"
    mv "$home" "$BUNDLED_JDK"
    rm -rf "$tmp"
}

# resolve_java: sets JAVA to a Java REQUIRED_JAVA+ binary: $JAVA if given, else 'java' on PATH if new
# enough, else the bundled JDK (downloaded on first use).
resolve_java() {
    if [ -n "$JAVA_FROM_ENV" ]; then
        java_ok "$JAVA_FROM_ENV" || die "JAVA=$JAVA_FROM_ENV is Java $(java_major "$JAVA_FROM_ENV" 2>/dev/null || echo '?'); Paper $PAPER_VERSION needs Java $REQUIRED_JAVA+"
        JAVA="$JAVA_FROM_ENV"
    elif java_ok java; then
        JAVA=java
    else
        if [ ! -x "$BUNDLED_JDK/bin/java" ]; then
            if command -v java >/dev/null 2>&1; then
                info "Your default Java is Java $(java_major java); Paper $PAPER_VERSION needs Java $REQUIRED_JAVA+."
            fi
            download_jdk
        fi
        JAVA="$BUNDLED_JDK/bin/java"
    fi
    # Build Postal with the same JDK (Maven follows JAVA_HOME).
    JAVA_HOME="$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p' | head -1)"
    export JAVA_HOME
}

check_tools() {
    command -v curl >/dev/null || die "curl is required"
    command -v python3 >/dev/null || die "python3 is required (used for downloads and RCON)"
    resolve_java
}

# set_property <key> <value>: sets a key in server.properties, adding it if missing.
set_property() {
    python3 - "$SERVER/server.properties" "$1" "$2" <<'EOF'
import sys
path, key, value = sys.argv[1:]
try:
    lines = open(path).read().splitlines()
except FileNotFoundError:
    lines = []
out, found = [], False
for line in lines:
    if line.split("=", 1)[0] == key:
        out.append(f"{key}={value}"); found = True
    else:
        out.append(line)
if not found:
    out.append(f"{key}={value}")
open(path, "w").write("\n".join(out) + "\n")
EOF
}

get_property() {
    sed -n "s/^$1=//p" "$SERVER/server.properties" 2>/dev/null | head -1
}

cmd_setup() {
    local accept_eula=0 offline=0 op=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --accept-eula) accept_eula=1 ;;
            --offline) offline=1 ;;
            --op) shift; op="${1:?--op needs a player name}" ;;
            *) die "unknown option for setup: $1" ;;
        esac
        shift
    done
    check_tools

    if [ "$accept_eula" -ne 1 ] && [ ! -f "$SERVER/eula.txt" ]; then
        echo "Running a Minecraft server requires accepting the Minecraft EULA:"
        echo "  https://aka.ms/MinecraftEULA"
        printf "Do you accept it? [y/N] "
        read -r answer
        case "$answer" in [yY]*) ;; *) die "EULA not accepted; nothing installed." ;; esac
    fi

    info "Downloading Paper $PAPER_VERSION, Citizens, VaultUnlocked and EssentialsX (cached in $CACHE)"
    download_server_jars "$CACHE"
    install_server "$CACHE" "$SERVER"
    echo "eula=true" > "$SERVER/eula.txt"

    if [ ! -f "$SERVER/server.properties" ]; then
        info "Writing server.properties (flat creative world, peaceful)"
        set_property motd "Postal dev server"
        set_property level-type 'minecraft\:flat'
        set_property generate-structures false
        set_property gamemode creative
        set_property difficulty peaceful
        set_property spawn-protection 0
        set_property allow-flight true
        set_property view-distance 8
        set_property simulation-distance 6
        set_property max-players 4
        # RCON lets this script send setup commands while the console stays interactive.
        set_property enable-rcon true
        set_property rcon.port "$RCON_PORT"
        set_property rcon.password "$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
    fi
    if [ "$offline" -eq 1 ]; then
        set_property online-mode false
        info "online-mode=false: any username can join. Only use this on a private network."
    fi
    if [ -z "$op" ] && [ ! -s "$OP_FILE" ] && [ -t 0 ]; then
        printf "Your Minecraft username, to be opped automatically (blank to skip): "
        read -r op
    fi
    [ -n "$op" ] && save_op "$op"
    info "Server ready in $SERVER. Next: dev/test-server.sh start --seed"
}

save_op() {
    case "$1" in
        *[!A-Za-z0-9_]*|"") die "'$1' is not a valid Minecraft username" ;;
    esac
    mkdir -p "$DEV_DIR"
    echo "$1" > "$OP_FILE"
    info "Will op $1 on every start (change with --op NAME)"
}

# write_postal_config: debug and economy on; keeps everything else Postal already wrote.
write_postal_config() {
    mkdir -p "$(dirname "$POSTAL_CONFIG")"
    if [ ! -f "$POSTAL_CONFIG" ]; then
        printf "Settings:\n  Debug: 'true'\nEconomy:\n  Use: 'true'\n" > "$POSTAL_CONFIG"
        return
    fi
    python3 - "$POSTAL_CONFIG" <<'EOF'
import re, sys
path = sys.argv[1]
text = open(path).read()
text = re.sub(r"(?m)^(  Debug: )'false'", r"\1'true'", text)
text = re.sub(r"(?m)^(  Use: )'false'", r"\1'true'", text)
open(path, "w").write(text)
EOF
}

rcon() {
    python3 "$REPO/dev/rcon.py" 127.0.0.1 "$(get_property rcon.port)" "$(get_property rcon.password)" "$@"
}

cmd_start() {
    local build=1 seed=0 op=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --no-build) build=0 ;;
            --seed) seed=1 ;;
            --op) shift; op="${1:?--op needs a player name}" ;;
            *) die "unknown option for start: $1" ;;
        esac
        shift
    done
    check_tools
    [ -f "$SERVER/paper.jar" ] || die "no server yet; run: dev/test-server.sh setup"

    if [ "$build" -eq 1 ]; then
        command -v mvn >/dev/null || die "Maven is required to build (or pass --no-build)"
        info "Building Postal"
        (cd "$REPO" && mvn -B -q package)
    fi
    local jar
    jar="$(ls -t "$REPO"/target/va_postal-*.jar 2>/dev/null | head -1 || true)"
    [ -n "$jar" ] || die "no Postal jar in target/; build first (mvn package)"
    cp "$jar" "$SERVER/plugins/Postal.jar"
    info "Installed $(basename "$jar")"

    write_postal_config
    [ -n "$op" ] && save_op "$op"
    local commands=()
    [ -s "$OP_FILE" ] && commands+=("op $(cat "$OP_FILE")")
    if [ "$seed" -eq 1 ]; then
        if [ -f "$SERVER/.seeded" ]; then
            info "Test network already seeded; skipping (dev/test-server.sh reset to start over)"
        else
            info "Seeding the test network (Central, Testville, Home) at the world origin"
            seed_config "$POSTAL_CONFIG"
            commands+=("${SEED_COMMANDS[@]}")
            touch "$SERVER/.seeded"
        fi
    fi
    if [ -f "$SERVER/.seeded" ]; then
        commands+=("postal start")
    fi

    # Mark where this run starts so 'report' can include just the current session if wanted.
    date '+%Y-%m-%d %H:%M:%S' > "$SERVER/.last-start"
    "$JAVA" -version 2>&1 | grep -v '^Picked up' | head -1 > "$SERVER/.last-java"
    rm -f "$SERVER/logs/latest.log"

    HELPER_PID=""
    if [ ${#commands[@]} -gt 0 ]; then
        (
            for _ in $(seq 1 300); do
                grep -q 'Done (' "$SERVER/logs/latest.log" 2>/dev/null && break
                sleep 1
            done
            sleep 3
            rcon "${commands[@]}" >/dev/null 2>"$DEV_DIR/rcon-error.log" \
                && echo "[dev] Sent setup commands: ${commands[*]}" \
                || echo "[dev] Could not send setup commands over RCON; see $DEV_DIR/rcon-error.log"
        ) &
        HELPER_PID=$!
    fi
    trap 'if [ -n "${HELPER_PID:-}" ]; then kill "$HELPER_PID" 2>/dev/null || true; fi' EXIT

    local port
    port="$(get_property server-port)"
    info "Starting Paper $PAPER_VERSION. Join at localhost:${port:-25565}. Type 'stop' to shut down."
    [ -f "$SERVER/.seeded" ] && echo "    Test network: Central (0,-60,0), Testville post office (20,-60,0), Home (40,-60,0)."
    echo "    Afterwards: dev/test-server.sh report"
    (cd "$SERVER" && "$JAVA" -Xms2G -Xmx4G -jar paper.jar nogui) || true
}

cmd_report() {
    [ -d "$SERVER/logs" ] || die "no server logs yet"
    local stamp dir
    stamp="$(date +%Y%m%d-%H%M%S)"
    dir="$REPORTS/postal-report-$stamp"
    mkdir -p "$dir/logs"

    cp "$SERVER/logs/latest.log" "$dir/logs/" 2>/dev/null || true
    # Earlier runs from the last day (Paper rotates logs to dated .log.gz files on start).
    find "$SERVER/logs" -name '*.log.gz' -mtime -1 -exec cp {} "$dir/logs/" \; 2>/dev/null || true
    [ -f "$POSTAL_CONFIG" ] && cp "$POSTAL_CONFIG" "$dir/postal-config.yml"
    [ -f "$SERVER/plugins/Citizens/saves.yml" ] && cp "$SERVER/plugins/Citizens/saves.yml" "$dir/citizens-saves.yml"

    {
        echo "Postal commit: $(cd "$REPO" && git rev-parse --short HEAD 2>/dev/null) ($(cd "$REPO" && git rev-parse --abbrev-ref HEAD 2>/dev/null))"
        (cd "$REPO" && git status --porcelain 2>/dev/null | head -20 | sed 's/^/  dirty: /')
        echo "Paper: $PAPER_VERSION"
        echo "Java (last start): $(cat "$SERVER/.last-java" 2>/dev/null || echo unknown)"
        echo "OS: $(uname -srm)"
        echo "Plugins:"
        for jar in "$SERVER"/plugins/*.jar; do
            echo "  $(basename "$jar"): $(unzip -p "$jar" plugin.yml 2>/dev/null | sed -n 's/^version: *//p' | head -1)"
        done
    } > "$dir/versions.txt"

    # Readable excerpt: Postal output, warnings, errors and stack traces, ANSI codes stripped.
    if [ -f "$SERVER/logs/latest.log" ]; then
        sed $'s/\x1b\\[[0-9;]*m//g' "$SERVER/logs/latest.log" \
            | grep -E '\[Postal\]|WARN\]|ERROR\]|Exception|Caused by|^\s+at |npc|NPC' \
            | grep -v 'Nag author' > "$dir/excerpt.txt" || true
    fi

    tar -czf "$dir.tar.gz" -C "$REPORTS" "$(basename "$dir")"
    info "Report: $dir.tar.gz"
    echo "    Attach that file, or paste $dir/excerpt.txt ($(wc -l < "$dir/excerpt.txt" 2>/dev/null || echo 0) lines)."
    echo "    Say what you did in game and what you expected to happen."
}

cmd_reset() {
    local all=0
    [ "${1:-}" = "--all" ] && all=1
    [ -d "$SERVER" ] || die "no server to reset"
    if [ "$all" -eq 1 ]; then
        printf "Delete the whole test server in %s (downloads are kept)? [y/N] " "$SERVER"
        read -r answer
        case "$answer" in [yY]*) rm -rf "$SERVER"; info "Removed. Run setup again." ;; *) echo "Cancelled." ;; esac
        return
    fi
    printf "Wipe the worlds, Postal config, Citizens NPCs and Essentials user data? [y/N] "
    read -r answer
    case "$answer" in [yY]*) ;; *) echo "Cancelled."; return ;; esac
    rm -rf "$SERVER"/world "$SERVER"/world_nether "$SERVER"/world_the_end \
        "$SERVER/plugins/Postal" "$SERVER/plugins/Citizens/saves.yml" "$SERVER/plugins/Essentials/userdata" \
        "$SERVER/.seeded"
    info "Reset. Next start creates a fresh world (use --seed for the test network)."
}

case "${1:-}" in
    setup) shift; cmd_setup "$@" ;;
    start) shift; cmd_start "$@" ;;
    report) shift; cmd_report "$@" ;;
    reset) shift; cmd_reset "$@" ;;
    -h|--help|help|"") usage 0 ;;
    *) echo "unknown command: $1" >&2; usage 1 ;;
esac
