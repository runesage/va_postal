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
#        STORAGE=mysql  runs on MySQL/MariaDB (MYSQL_* as in smoke-test.sh; the database must be empty). With
#        MARIADB_CONTAINER (as ci/start-mariadb.sh names it: postal-mariadb) it also stops and restarts the database
#        mid-run to check the outage handling (P3 test plan section 4).
#
# Running this accepts the Minecraft EULA for a throwaway test server.
set -euo pipefail

POSTAL_JAR="$(realpath "${1:?usage: bot-test.sh <postal.jar>}")"
JAVA="${JAVA:-java}"
BOT_JAVA="${BOT_JAVA:-java}"
WORK_DIR="${WORK_DIR:-bot-work}"
PORT="${PORT:-25565}"
BOT_NAME="PostalBot"
STORAGE="${STORAGE:-sqlite}"
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
difficulty=peaceful
spawn-monsters=false
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
if [ "$STORAGE" = mysql ]; then
    python3 - "$CONFIG" "${MYSQL_HOST:-127.0.0.1}" "${MYSQL_PORT:-3306}" "${MYSQL_DATABASE:-postal}" \
        "${MYSQL_USER:-postal}" "${MYSQL_PASSWORD:-postal}" <<'PY'
import sys
p, host, port, db, user, password = sys.argv[1:]
lines = open(p).read().split("\n")
values = {"Type": "mysql", "Host": host, "Port": port, "Database": db, "User": user, "Password": password}
out, in_storage = [], False
for line in lines:
    if line.startswith("Storage:"):
        in_storage = True
    elif line and not line.startswith(" "):
        in_storage = False
    key = line.strip().split(":")[0]
    if in_storage and key in values:
        line = line[:len(line) - len(line.lstrip())] + key + ": '" + values.pop(key) + "'"
    out.append(line)
assert not values, "Storage keys not found in config: %s" % values
open(p, "w").write("\n".join(out))
PY
fi

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

# ---- Parcels: packing, COD, cancelling, then accept/refuse delivered parcels (P2 test plan 1a-1d, 3a-3f) ------------
# The console stands in for hands: setblock places a filled chest, and `item replace ... from block` copies a delivered
# label out of Home's mailbox into the bot's hand (a copy, so the original stays: that's the copied-label case, 3c).
UUID_RE='[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
chat_since() { tail -n +"$1" "$BOT_LOG"; }
# track_state <id>: the state /postal track <id> reports (empty if none)
track_state() {
    local from; from=$(( $(lines "$BOT_LOG") + 1 ))
    bot_cmd "/postal track $1" "CHAT .* from .*: " >/dev/null || true
    chat_since "$from" | grep -oE ' from [^:]+: [A-Z_]+' | head -1 | awk '{print $NF}' || true
}
track_text() { # id: everything /postal track <id> printed
    local from; from=$(( $(lines "$BOT_LOG") + 1 ))
    bot_cmd "/postal track $1" "CHAT .*Contents" >/dev/null || true
    sleep 1
    chat_since "$from"
}
newest_parcel() { # the newest parcel's id, from /postal track recent
    local from; from=$(( $(lines "$BOT_LOG") + 1 ))
    bot_cmd "/postal track recent" "CHAT \[Postal\] Recent mail" >/dev/null || true
    sleep 1
    chat_since "$from" | grep -E "CHAT $UUID_RE \[parcel\]" | head -1 | grep -oE "$UUID_RE" || true
}
CHEST_ITEMS='[{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":"Bot Blade","minecraft:enchantments":{"minecraft:sharpness":5}}},{Slot:1b,id:"minecraft:oak_log",count:32}]'
block_items() { # x y z: the Items of the block entity there, as the console prints them
    local from; from=$(( $(lines "$SERVER_LOG") + 1 ))
    console "data get block $1 $2 $3 Items"
    sleep 1
    tail -n +"$from" "$SERVER_LOG" | grep -E 'has the following block data|Found no elements|is not a block entity' | tail -1
}

console "minecraft:setblock 25 -60 4 minecraft:chest{Items:$CHEST_ITEMS}"
console "minecraft:tp $BOT_NAME 24 -59 4"
sleep 2
check "/package asks for confirmation" bot_cmd "/package testville home" 'CHAT .*Ready create shipping label'
check "confirming /package gives the shipping label" bot_cmd "/" 'CHAT .*shipping label is now in your hand'
sleep 1
P1=$(newest_parcel)
check "the parcel is tracked as POSTED" test "$(track_state "$P1")" = POSTED
check "its contents list the named, enchanted blade and the logs" bash -c "grep -q 'Bot Blade' <<<\"\$1\" && grep -qi 'oak_log' <<<\"\$1\"" _ "$(track_text "$P1")"
check "the packed chest is empty" bash -c "! grep -q 'diamond_sword' <<<\"\$1\"" _ "$(block_items 25 -60 4)"
check "/cod asks for confirmation" bot_cmd "/cod 50" "CHAT .*recipient must pay you \\\$50"
check "confirming /cod is accepted" bot_cmd "/" 'CHAT .*'
sleep 1
check "the parcel shows COD 50" bash -c "grep -qE 'COD 50' <<<\"\$1\"" _ "$(track_text "$P1")"
check "re-addressing an unposted label is refused" bot_cmd "/addr testville home" 'CHAT .*(cancel|package again)'

console "minecraft:setblock 25 -60 7 minecraft:chest{Items:$CHEST_ITEMS}"
console "minecraft:tp $BOT_NAME 24 -59 7"
sleep 2
bot_cmd "/package testville home" 'CHAT .*Ready create shipping label' || true
bot_cmd "/" 'CHAT .*shipping label is now in your hand' || true
sleep 1
P2=$(newest_parcel)
check "/package cancel answers" bot_cmd "/package cancel" 'CHAT .*'
sleep 1
check "the cancelled parcel is RETURNED" test "$(track_state "$P2")" = RETURNED
check "the cancelled parcel's items are back in its chest" bash -c "grep -q 'diamond_sword' <<<\"\$1\"" _ "$(block_items 25 -60 7)"

# Four test parcels to Home in one round: plain (accept, accept again, copied label), COD 25, refuse, retired item.
console "postal testparcel testville testville home"; sleep 2
console "postal testparcel testville testville home 25"; sleep 2
console "postal testparcel testville testville home"; sleep 2
console "postal testparcel testville testville home 0 retired"; sleep 2
mapfile -t TP < <(grep -oE "Test parcel $UUID_RE" "$SERVER_LOG" | awk '{print $3}')
echo "test parcels: ${TP[*]}"
console "postal start"
delivered=no
for _ in $(seq 1 40); do
    sleep 10
    all=yes
    for id in "${TP[@]}"; do [ "$(track_state "$id")" = DELIVERED ] || { all=no; break; }; done
    if [ "$all" = yes ]; then delivered=yes; break; fi
done
check "the four test parcels are DELIVERED to Home" test "$delivered" = yes
console "postal stop"
sleep 2

# label_slot <id>: the slot in Home's mailbox (40 -60 0) holding that parcel's label
label_slot() {
    python3 - "$1" "$(block_items 40 -60 0)" <<'PY'
import re, sys
want, text = sys.argv[1], re.sub(r'\x1b\[[0-9;]*m', '', sys.argv[2])
text = text[text.find('['):]
depth, start, in_str, esc = 0, None, False, False
for i, c in enumerate(text):   # split the top-level items of the list by brace depth
    if in_str:
        esc = (c == '\\' and not esc)
        if c == '"' and not esc: in_str = False
        continue
    if c == '"': in_str = True
    elif c == '{':
        if depth == 0: start = i
        depth += 1
    elif c == '}':
        depth -= 1
        if depth == 0:
            item = text[start:i + 1]
            s = re.match(r'\{Slot: (\d+)b', item)
            if s and want in item:
                print(s.group(1)); break
PY
}
hold_label() { # id: copies that parcel's label from Home's mailbox into the bot's hand
    local slot; slot=$(label_slot "$1")
    [ -n "$slot" ] || return 1
    console "minecraft:item replace entity $BOT_NAME weapon.mainhand from block 40 -60 0 container.$slot"
    sleep 1
}
balance() {
    local from; from=$(( $(lines "$BOT_LOG") + 1 ))
    bot_cmd "/balance" 'CHAT .*[Bb]alance' >/dev/null || true
    chat_since "$from" | grep -iE 'balance' | grep -oE '\$[0-9,]+(\.[0-9]+)?' | head -1 | tr -d '$,' || true
}
accept_held() { # confirms if asked
    local from; from=$(( $(lines "$BOT_LOG") + 1 ))
    bot "/accept"; sleep 2
    if chat_since "$from" | grep -q "Enter '/' to confirm"; then bot "/"; sleep 2; fi
}

if [ "$delivered" = yes ]; then
    console "minecraft:tp $BOT_NAME 34 -59 4"
    sleep 1
    check "the plain parcel's label is in Home's mailbox" hold_label "${TP[0]}"
    accept_held
    check "/accept marks it ACCEPTED" test "$(track_state "${TP[0]}")" = ACCEPTED
    check "/accept again on the statement is refused" bot_cmd "/accept" "CHAT .*already been accepted or refused"
    hold_label "${TP[0]}" || true
    check "a copy of an accepted label is refused" bot_cmd "/accept" "CHAT .*already been filled"

    before=$(balance)
    hold_label "${TP[1]}" || true
    accept_held
    after=$(balance)
    check "the COD parcel is ACCEPTED" test "$(track_state "${TP[1]}")" = ACCEPTED
    check "accepting it charged 25 (balance $before -> $after)" python3 -c "import sys; sys.exit(0 if abs(float('$before' or 0)-float('$after' or 0)-25)<0.01 else 1)"

    hold_label "${TP[2]}" || true
    from=$(( $(lines "$BOT_LOG") + 1 ))
    bot "/refuse"; sleep 2
    if chat_since "$from" | grep -q "Enter '/' to confirm"; then bot "/"; sleep 2; fi
    check "/refuse marks it REFUSED" test "$(track_state "${TP[2]}")" = REFUSED

    hold_label "${TP[3]}" || true
    from=$(( $(lines "$BOT_LOG") + 1 ))
    accept_held
    check "the retired-item parcel is ACCEPTED" test "$(track_state "${TP[3]}")" = ACCEPTED
    check "the player is told the retired item couldn't be delivered" bash -c "tail -n +$from '$BOT_LOG' | grep -q 'no longer exists'"
fi

# ---- The mail store as a player sees it: /postal store and /postal directory (P3 test plan 1, 2) --------------------
store_text() {
    local from; from=$(( $(lines "$BOT_LOG") + 1 ))
    bot_cmd "/postal store" 'CHAT .*Mail store' >/dev/null || true
    sleep 1
    chat_since "$from"
}
if [ "$STORAGE" = mysql ]; then
    check "/postal store says MySQL" bash -c "grep -q 'Mail store: MySQL' <<<\"\$1\"" _ "$(store_text)"
else
    check "/postal store says SQLite" bash -c "grep -q 'Mail store: SQLite' <<<\"\$1\"" _ "$(store_text)"
fi
check "/postal store reports no failures" bash -c "grep -q 'Failures: 0' <<<\"\$1\"" _ "$(store_text)"
from=$(( $(lines "$BOT_LOG") + 1 ))
bot_cmd "/postal directory" 'CHAT .*' >/dev/null || true
sleep 2
check "/postal directory lists Testville" bash -c "tail -n +$from '$BOT_LOG' | grep -qi 'testville'"

# ---- The database goes away and comes back (P3 test plan 4) -------------------------------------------------------
if [ "$STORAGE" = mysql ] && [ -n "${MARIADB_CONTAINER:-}" ]; then
    docker stop "$MARIADB_CONTAINER" >/dev/null
    sleep 2
    from=$(( $(lines "$BOT_LOG") + 1 ))
    bot_cmd "/postal testletter testville testville home" 'CHAT .*Test letter' >/dev/null || true
    sleep 2
    check "with the database down, a test letter is handed in untracked" bash -c "tail -n +$from '$BOT_LOG' | grep -q 'untracked'"
    check "/postal store says the store is unavailable" bash -c "grep -qiE 'unavailable|can.t be reached' <<<\"\$1\"" _ "$(store_text)"
    check "the server log says the store can't be reached" grep -q "can't be reached" "$SERVER_LOG"
    docker start "$MARIADB_CONTAINER" >/dev/null
    check "the server log says the store is back" wait_log "$SERVER_LOG" 'mail store is back after' 90
    from=$(( $(lines "$BOT_LOG") + 1 ))
    bot_cmd "/postal testletter testville testville home" 'CHAT .*Test letter' >/dev/null || true
    sleep 1
    check "new mail is tracked again" bash -c "tail -n +$from '$BOT_LOG' | grep -qE 'Test letter [0-9a-f]{8}-'"
fi

bot quit
wait "$BOT_PID" 2>/dev/null || true
BOT_PID=""
stop_server

check "no Postal stack traces in the server log" bash -c "! grep -qE 'at .*com\.vodhanel\.' '$SERVER_LOG'"

echo "---- bot events"
cat "$BOT_LOG"
exit "$fail"
