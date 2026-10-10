#!/usr/bin/env bash
# Boots a throwaway Paper server with Postal on the seeded test network, joins it with the headless bot
# player in ci/bot/ (MCProtocolLib), and checks what only a player can see: the compass target.
#
# Scenarios: op the bot; /gps testville + "/" (Postal's confirmation) must point the compass at Testville's
# post office, and a following /tlist must NOT move it (the bug fixed in PR #37). Then /addr a signed book in hand
# (POSTED) and re-address it (old record RETURNED, a new one POSTED), as in the P1 test plan's 1a and 1c.
#
# Usage: ci/bot-test.sh <path-to-postal.jar>
# Env:   JAVA      Java 25 runtime for the Paper server (default: java)
#        BOT_JAVA  Java 21+ for the bot and its Maven build (default: java)
#        WORK_DIR  (default: ./bot-work)     PORT (default: 25565)    PAPER_VERSION as in smoke-test.sh
#
# Running this accepts the Minecraft EULA for a throwaway test server.
set -euo pipefail

POSTAL_JAR="$(realpath "${1:?usage: bot-test.sh <postal.jar>}")"
JAVA="${JAVA:-java}"
BOT_JAVA="${BOT_JAVA:-java}"
WORK_DIR="${WORK_DIR:-bot-work}"
PORT="${PORT:-25565}"
BOT_NAME="PostalBot"
SEED_SIZE=small  # Testville with one address is all the scenario needs
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=ci/lib.sh
source "$HERE/lib.sh"

mkdir -p "$WORK_DIR/cache"
WORK_DIR="$(realpath "$WORK_DIR")"
CACHE="$WORK_DIR/cache"
SERVER="$WORK_DIR/server"
BOT_LOG="$WORK_DIR/bot.log"
SERVER_LOG="$WORK_DIR/server.log"
CONSOLE="$WORK_DIR/console.fifo"
BOT_IN="$WORK_DIR/bot.fifo"

SERVER_PID="" BOT_PID=""
cleanup() { # only ever our own processes
    [ -n "$BOT_PID" ] && kill "$BOT_PID" 2>/dev/null || true
    [ -n "$SERVER_PID" ] && kill -9 "$SERVER_PID" 2>/dev/null || true
    exec 3>&- 4>&- 2>/dev/null || true
    rm -f "$CONSOLE" "$BOT_IN"
}
trap cleanup EXIT

# ---- Build the bot (a separate Maven project, never part of the plugin) ----------------------------------------------
BOT_DIR="$HERE/bot"
if [ ! -d "$BOT_DIR/target/lib" ] || [ -n "$(find "$BOT_DIR/src" "$BOT_DIR/pom.xml" -newer "$BOT_DIR/target/lib" 2>/dev/null)" ]; then
    echo "== Building the bot"
    (cd "$BOT_DIR" && mvn -q -B package)
fi
BOT_CP="$BOT_DIR/target/classes:$BOT_DIR/target/lib/*"

# ---- Server ---------------------------------------------------------------------------------------------------------
download_server_jars "$CACHE"
rm -rf "$SERVER"
install_server "$CACHE" "$SERVER"
cp "$POSTAL_JAR" "$SERVER/plugins/Postal.jar"
echo "eula=true" > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<EOF
online-mode=false
server-port=$PORT
server-ip=127.0.0.1
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
max-players=2
enforce-secure-profile=false
EOF

wait_log() { # file regex timeout-seconds [first-line]: waits for a line matching regex at or after first-line
    local file="$1" re="$2" secs="$3" from="${4:-1}" i
    for ((i = 0; i < secs * 2; i++)); do
        if tail -n +"$from" "$file" 2>/dev/null | grep -qE "$re"; then return 0; fi
        sleep 0.5
    done
    return 1
}
lines() { wc -l < "$1" | tr -d ' '; }

start_server() { # log: starts Paper with a console fifo on fd 3; retries when a plugin fails to enable
    local attempt
    for attempt in 1 2 3; do
        rm -f "$SERVER/logs/latest.log" "$CONSOLE"
        mkfifo "$CONSOLE"
        (cd "$SERVER" && exec "$JAVA" -Xms1G -Xmx2G -jar paper.jar nogui < "$CONSOLE") > "$1" 2>&1 &
        SERVER_PID=$!
        exec 3>"$CONSOLE"
        wait_log "$1" 'Done \(' 300 || { echo "server did not start"; tail -30 "$1"; exit 1; }
        sleep 3
        # Citizens downloads libraries on first start and that occasionally fails, which takes Postal down with it.
        if ! grep -q 'Error occurred while enabling' "$1"; then return 0; fi
        echo "a plugin failed to enable (attempt $attempt), restarting:"
        grep -A1 'Error occurred while enabling' "$1" | head -4
        stop_server
    done
    echo "plugins would not enable after 3 attempts"
    exit 1
}
console() { echo "$*" >&3; }
stop_server() {
    console stop
    wait "$SERVER_PID" 2>/dev/null || true
    SERVER_PID=""
    exec 3>&-
    rm -f "$CONSOLE"
}

echo "== Boot 1: build the seed world and let Postal write its config"
start_server "$WORK_DIR/boot1.log"
# Same opening as smoke-test.sh phase 1: Postal writes its default config while these run ("postal start" is
# refused cleanly until the network is seeded).
for c in plugins postal tlist alist "postal start" "${SEED_COMMANDS[@]}"; do console "$c"; sleep 2; done
sleep 3
stop_server
[ -f "$SERVER/plugins/Postal/config.yml" ] || { echo "Postal did not write its config"; tail -30 "$WORK_DIR/boot1.log"; exit 1; }
CONFIG="$SERVER/plugins/Postal/config.yml"
sed -i "0,/^  Use: 'false'/s//  Use: 'true'/" "$CONFIG"
seed_config "$CONFIG"

echo "== Boot 2: join the bot and run the scenario"
start_server "$SERVER_LOG"

: > "$BOT_LOG"
mkfifo "$BOT_IN"
"$BOT_JAVA" -cp "$BOT_CP" ci.bot.Bot 127.0.0.1 "$PORT" "$BOT_NAME" < "$BOT_IN" > "$BOT_LOG" 2>"$WORK_DIR/bot.err" &
BOT_PID=$!
exec 4>"$BOT_IN"
bot() { echo "$*" >&4; }

fail=0
check() { # description, command...
    local desc="$1"; shift
    if "$@"; then echo "PASS: $desc"; else echo "FAIL: $desc"; fail=1; fi
}
compass_events() { grep -c ' COMPASS ' "$BOT_LOG" || true; }
last_compass() { grep ' COMPASS ' "$BOT_LOG" | tail -1 | cut -d' ' -f3-; }
# bot_cmd <instruction> <regex>: sends an instruction and waits for chat matching the regex after it.
bot_cmd() {
    local from; from=$(( $(lines "$BOT_LOG") + 1 ))
    bot "$1"
    wait_log "$BOT_LOG" "$2" 20 "$from"
}

check "bot joined" wait_log "$BOT_LOG" ' JOINED' 60 || { cat "$BOT_LOG" "$WORK_DIR/bot.err"; exit 1; }
console "op $BOT_NAME"
check "bot was opped" wait_log "$BOT_LOG" 'CHAT .*commands\.op\.success' 15
sleep 2

# The compass target Postal will set: Testville's post office, as seeded by lib.sh (world,20.0,-60.0,2.0).
EXPECTED="minecraft:overworld 20 -60 2"

before_gps=$(compass_events)
check "/gps asks for confirmation" bot_cmd "/gps testville" 'CHAT .*Ready to set your compass to'
sleep 1
check "the compass is not moved before confirming" test "$(compass_events)" = "$before_gps"
check "confirming with / is accepted" bot_cmd "/" 'CHAT .*Your compass has been set to'
sleep 1
check "the compass target is Testville's post office ($EXPECTED)" test "$(last_compass)" = "$EXPECTED"

after_gps=$(compass_events)
check "/tlist answers" bot_cmd "/tlist" 'CHAT TESTVILLE'
sleep 2
check "/tlist did not change the compass target" test "$(compass_events)" = "$after_gps"
check "the compass target is still Testville's post office" test "$(last_compass)" = "$EXPECTED"

# ---- Letters: addressing and re-addressing a book in hand (P1 test plan 1a and 1c) ----------------------------------
# Only a player can do this: /addr works on the signed book in the player's main hand, near a post office. The
# console supplies what the bot can't do itself: the book, the money for postage, and the walk to the office.
console "eco give $BOT_NAME 10000"
console "minecraft:tp $BOT_NAME 20 -59 4"
console "minecraft:give $BOT_NAME written_book[written_book_content={title:\"Bot letter\",author:\"$BOT_NAME\",pages:[\"Hello from the bot\"]}]"
sleep 2
track_ids() { # state: ids of Testville, Home letters in that state, from the last /postal track recent
    tail -n +"$1" "$BOT_LOG" | grep -E "CHAT [0-9a-f-]{36} [Tt]estville, [Hh]ome: $2" | awk '{print $3}' | sort -u || true
}
check "/addr asks for confirmation" bot_cmd "/addr testville home" 'CHAT .*Ready to address to'
check "confirming /addr is accepted" bot_cmd "/" 'CHAT .*'
sleep 1
from=$(( $(lines "$BOT_LOG") + 1 ))
check "the letter is tracked as POSTED" bot_cmd "/postal track recent" 'CHAT [0-9a-f-]{36} [Tt]estville, [Hh]ome: POSTED'
first_id=$(track_ids "$from" POSTED | head -1)

check "re-addressing asks for confirmation" bot_cmd "/addr testville home" 'CHAT .*Ready to address to'
check "confirming the re-address is accepted" bot_cmd "/" 'CHAT .*'
sleep 1
from=$(( $(lines "$BOT_LOG") + 1 ))
bot_cmd "/postal track recent" 'CHAT [0-9a-f-]{36} [Tt]estville, [Hh]ome' || true
sleep 1
check "the first record is closed as RETURNED" bash -c "[ -n '$first_id' ] && tail -n +$from '$BOT_LOG' | grep -qE 'CHAT $first_id [Tt]estville, [Hh]ome: RETURNED'"
check "a new record is POSTED" bash -c "tail -n +$from '$BOT_LOG' | grep -E 'CHAT [0-9a-f-]{36} [Tt]estville, [Hh]ome: POSTED' | grep -qv '$first_id'"

bot quit
wait "$BOT_PID" 2>/dev/null || true
BOT_PID=""
stop_server

check "no Postal stack traces in the server log" bash -c "! grep -qE 'at .*com\.vodhanel\.' '$SERVER_LOG'"

echo "---- bot events"
cat "$BOT_LOG"
exit "$fail"
