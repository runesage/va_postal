#!/usr/bin/env bash
# Boots a throwaway Paper server with Postal on the seeded test network, joins it with the headless bot
# player in ci/bot/ (MCProtocolLib), and checks what only a player can see: the compass target.
#
# Scenarios: op the bot; /gps testville + "/" (Postal's confirmation) must point the compass at Testville's
# post office, and a following /tlist must NOT move it (the bug fixed in PR #37). Then /addr a signed book in hand
# (POSTED) and re-address it (old record RETURNED, a new one POSTED), as in the P1 test plan's 1a and 1c. Parcels,
# then the office counter: the clerk's menu, the buy dialog, a deed used on a chest, an application approved.
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

SERVER_PID="" BOT_PID="" BUYER_PID=""
cleanup() { # only ever our own processes
    [ -n "$BOT_PID" ] && kill "$BOT_PID" 2>/dev/null || true
    [ -n "$BUYER_PID" ] && kill "$BUYER_PID" 2>/dev/null || true
    [ -n "$SERVER_PID" ] && kill -9 "$SERVER_PID" 2>/dev/null || true
    exec 3>&- 4>&- 5>&- 2>/dev/null || true
    rm -f "$CONSOLE" "$BOT_IN" "$WORK_DIR/buyer.fifo"
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

# ---- The office counter: buying an address from the clerk (docs/design/deeds.md §2-3) ---------------------------------
# Only a player can: right-click the clerk, click the counter menu, fill in the dialog, use the deed on a chest. The bot
# is opped, so it also sees the owner's buttons (Approval, Applications). Testville's clerk stands at the west side of
# its chest (20 -60 0): 19.5 -60 0.5.
CLERK="19.5 -60 0.5"
console "minecraft:clear $BOT_NAME"
console "minecraft:setblock 14 -60 6 minecraft:chest[facing=south]"
console "postal start"
sleep 12
console "minecraft:tp $BOT_NAME 18 -60 2.5"
sleep 2
check "the clerk is in view" bash -c "grep -qE ' ENTITY [0-9]+ player 19\.5 -60\.0 0\.5' '$BOT_LOG'"
check "right-clicking the clerk opens the counter" bot_cmd "interact $CLERK" 'SCREEN [0-9]+ Testville Post Office'
sleep 1
check "the counter offers an address" grep -qE 'ITEM [0-9]+/11 Buy an address' "$BOT_LOG"
check "an owner (op) sees the approval switch, off" grep -qE 'ITEM [0-9]+/24 Approval: off' "$BOT_LOG"
check "Buy an address opens the dialog with a suggested name" \
    bot_cmd "click 11" 'DIALOG Buy an address: Testville \| value=[A-Z][^|]+ \| Buy, Suggest another'
check "Suggest another draws a name again" bot_cmd "press Suggest another" 'DIALOG Buy an address: Testville \| value=[A-Z]'
bot "input value Rose Cottage"
check "buying it gives the deed" bot_cmd "press Buy" 'CHAT .*Here is your deed for Rose Cottage'
check "the deed is in the inventory" wait_log "$BOT_LOG" 'ITEM 0/[0-9]+ Deed of Address: Rose Cottage' 10
console "minecraft:tp $BOT_NAME 14 -60 9"
sleep 2
check "using the deed on a chest opens Rose Cottage" bot_cmd "use 14 -60 6 south" 'CHAT .*Welcome to Rose Cottage, Testville'
sleep 1
check "Rose Cottage is one of Testville's addresses" bot_cmd "/alist testville" 'CHAT .*[Rr]ose.[Cc]ottage'

# Approval from the counter: switch it on, apply, approve in the Applications menu; the deed comes to the bot.
console "minecraft:tp $BOT_NAME 18 -60 2.5"
sleep 2
bot_cmd "interact $CLERK" 'SCREEN [0-9]+ Testville Post Office' || true
sleep 1
check "the approval switch turns on" bot_cmd "click 24" 'ITEM [0-9]+/24 Approval: on'
sleep 1
check "the form now asks to apply" bot_cmd "click 11" 'DIALOG Buy an address: Testville \| value=[^|]* \| Apply, Suggest another'
bot "input value Ivy Lodge"
check "applying puts it before the postmaster" bot_cmd "press Apply" 'CHAT .*put your application for Ivy Lodge'
bot_cmd "interact $CLERK" 'SCREEN [0-9]+ Testville Post Office' || true
sleep 1
check "the Applications menu lists it" bot_cmd "click 20" 'ITEM [0-9]+/0 Ivy Lodge'
check "left-clicking approves it" bot_cmd "click 0" 'CHAT .*has approved your application for Ivy Lodge'
check "the approved deed comes to the inventory" wait_log "$BOT_LOG" 'ITEM 0/[0-9]+ Deed of Address: Ivy Lodge' 10
bot "close"
console "postal office testville approval default"

# Central: the Postmaster General greets the bot and opens Central's counter; a player (the bot, deopped, in creative,
# where a dig breaks at once) can't break Central's chest, and the attempt is logged.
console "minecraft:tp $BOT_NAME 0.5 -60 3"
check "the Postmaster General greets the bot at Central" wait_log "$BOT_LOG" 'CHAT <Postmaster General> Good day, PostalBot. Central Post Office' 15
check "right-clicking him opens Central's counter" bot_cmd "interact -0.5 -60 0.5" 'SCREEN [0-9]+ Central Post Office'
sleep 1
check "Central's counter shows the postage rates" grep -qE 'ITEM [0-9]+/11 Postage rates' "$BOT_LOG"
check "and the network" grep -qE 'ITEM [0-9]+/13 The network' "$BOT_LOG"
check "and a charter, which needs approval" grep -qE 'ITEM [0-9]+/22 Apply for a charter' "$BOT_LOG"
bot "close"

# Charters at Central's counter (docs/design/central-office.md §3): apply for Ashby, an admin grants it, the charter
# comes to the bot's hand (the inventory cleared first, so it lands there), and the bot opens Ashby on a chest 90
# blocks from the other offices.
console "minecraft:setblock 90 -60 60 minecraft:chest[facing=south]"
console "minecraft:clear $BOT_NAME"
bot_cmd "interact -0.5 -60 0.5" 'SCREEN [0-9]+ Central Post Office' || true
sleep 1
check "Apply for a charter opens the form" bot_cmd "click 22" 'DIALOG Charter a post office \| value= \| Apply'
bot "input value Ashby"
check "applying puts it before the Postmaster General" bot_cmd "press Apply" 'CHAT .*application for the Ashby Post Office is with the Postmaster General'
check "the counter shows the application" bot_cmd "interact -0.5 -60 0.5" 'ITEM [0-9]+/22 Your charter application'
bot "close"
console "postal charter approve ashby"
check "the grant reaches the bot" wait_log "$BOT_LOG" 'CHAT .*The Postmaster General has approved your application for the Ashby Post Office' 10
check "the charter is in its hand" wait_log "$BOT_LOG" 'ITEM 0/36 Charter of Ashby Post Office' 10
console "minecraft:tp $BOT_NAME 90 -60 63"
sleep 2
check "using the charter on a chest opens Ashby" bot_cmd "use 90 -60 60 south" 'CHAT .*The Ashby Post Office is open'
check "everyone hears of it" wait_log "$BOT_LOG" 'CHAT A new post office has opened in Ashby! Chartered to PostalBot' 5
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "postal clerk list"
check "Ashby is one of the offices" wait_log "$SERVER_LOG" 'Ashby.*Postmaster of Ashby' 5 "$from"

# Office sales (docs/design/central-office.md §3): a second player, PostalBuyer, joins. PostalBot, Ashby's owner, offers it
# to them and withdraws the offer; offers it again for 100; PostalBuyer accepts at Central's counter (100 to the seller,
# 5% to Central) and Ashby is theirs; then PostalBuyer sells it back to Central, confirming in the form.
BUYER_LOG="$WORK_DIR/buyer.log"
: > "$BUYER_LOG"
mkfifo "$WORK_DIR/buyer.fifo"
"$BOT_JAVA" -cp "$BOT_CP" ci.bot.Bot 127.0.0.1 "$PORT" PostalBuyer < "$WORK_DIR/buyer.fifo" > "$BUYER_LOG" 2>"$WORK_DIR/buyer.err" &
BUYER_PID=$!
exec 5>"$WORK_DIR/buyer.fifo"
buyer() { echo "$*" >&5; }
buyer_cmd() { # instruction regex: as bot_cmd, for the buyer
    local from; from=$(( $(lines "$BUYER_LOG") + 1 ))
    buyer "$1"
    wait_log "$BUYER_LOG" "$2" 20 "$from"
}
check "a second player joins" wait_log "$BUYER_LOG" ' JOINED' 60
console "eco give PostalBuyer 1000"
console "minecraft:tp PostalBuyer 0.5 -60 3"
sleep 2
check "Ashby's owner offers it" bot_cmd "/postal office ashby sell PostalBuyer 50" 'CHAT .*Ashby is offered to PostalBuyer for'
check "the buyer hears of the offer" wait_log "$BUYER_LOG" 'CHAT .*PostalBot offers you the Ashby Post Office' 10
check "the owner withdraws it" bot_cmd "/postal office ashby sell cancel" 'CHAT .*offer of Ashby to PostalBuyer is withdrawn'
check "the buyer hears it's withdrawn" wait_log "$BUYER_LOG" 'CHAT .*withdrawn the offer of the Ashby Post Office' 10
bot_cmd "/postal office ashby sell PostalBuyer 100" 'CHAT .*Ashby is offered to PostalBuyer for' || true
check "the offer shows at Central's counter" buyer_cmd "interact -0.5 -60 0.5" 'ITEM [0-9]+/24 Offers to you'
check "it opens the offers" buyer_cmd "click 24" 'ITEM [0-9]+/0 Ashby Post Office'
check "accepting makes Ashby the buyer's" buyer_cmd "click 0" 'CHAT .*the Ashby Post Office is yours'
check "the seller hears and is paid" wait_log "$BOT_LOG" 'CHAT .*PostalBuyer has bought the Ashby Post Office; \$100.* has been paid to you' 10
check "the sale is recorded" grep -q 'PostalBuyer bought ashby from PostalBot for 100' "$SERVER_LOG"
check "the buyer's counter offers to sell Ashby back" buyer_cmd "interact -0.5 -60 0.5" 'ITEM [0-9]+/15 Your post offices'
check "it lists Ashby" buyer_cmd "click 15" 'ITEM [0-9]+/0 Ashby Post Office'
check "selling back asks to confirm" buyer_cmd "click 0" 'DIALOG Sell Ashby back to Central \| value= \| Sell'
buyer "input value Ashby"
check "confirmed, Ashby goes back to the server" buyer_cmd "press Sell" "CHAT .*the Ashby Post Office is the server's again"
buyer "quit"
wait "$BUYER_PID" 2>/dev/null || true
BUYER_PID=""
console "minecraft:tp $BOT_NAME 0.5 -60 3"
sleep 2
console "deop $BOT_NAME"
console "gamemode creative $BOT_NAME"
sleep 2
check "a player can't break Central's chest" bot_cmd "dig 0 -60 0" "CHAT .*Central's chest and sign belong to the Post Office"
sleep 1
check "the attempt is logged" grep -q "PostalBot tried to break Central's chest" "$SERVER_LOG"
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "execute if block 0 -60 0 minecraft:chest"
check "Central's chest still stands" wait_log "$SERVER_LOG" 'Test passed' 5 "$from"
# Central's notice board (docs/design/central-office.md §6): right-clicking a sign gives its panel in full in chat; a
# player (still deopped) can't break one.
console "minecraft:fill -3 -60 6 -1 -59 6 minecraft:stone"
console "minecraft:setblock -2 -59 5 minecraft:oak_wall_sign[facing=north]"
console "minecraft:setblock -1 -59 5 minecraft:oak_wall_sign[facing=north]"
console "postal board add postage -2 -59 5"
console "postal board add notices -1 -59 5"
console "postal bank policy rates.letter_local 6"
sleep 2
check "right-clicking the postage sign gives every rate" bot_cmd "use -2 -59 5 north" 'CHAT .*A letter, to another server'
sleep 1
check "right-clicking the notices sign gives the notice in full" bot_cmd "use -1 -59 5 north" 'CHAT .*[0-9]+/[0-9]+ A letter, within an office: was .*, now '
sleep 1
check "a player can't break a notice board sign" bot_cmd "dig -2 -59 5" "CHAT .*The notice board belongs to the Post Office"
# Postal maps (docs/design/central-office.md §6): the network card at Central's counter sells a map; held in the bot's
# hand, it arrives drawn, labelled with Central and the offices.
console "minecraft:clear $BOT_NAME"
sleep 1
check "Central's counter offers a network map" bot_cmd "interact -0.5 -60 0.5" 'ITEM [0-9]+/13 The network'
check "clicking it sells one" bot_cmd "click 13" 'CHAT .*Here is your map of the network'
check "the map arrives drawn, with Central on it" wait_log "$BOT_LOG" 'MAP [0-9]+ pixels=16384 labels=.*Central' 15
check "and Testville" grep -qE 'MAP [0-9]+ pixels=16384 labels=.*Testville' "$BOT_LOG"
console "gamemode survival $BOT_NAME"
console "op $BOT_NAME"
console "postal stop"
sleep 2

# ---- P.O. boxes (docs/design/po-boxes.md, phase 1: leases) ----------------------------------------------------------
# Too far from the office, renting is refused; at Testville the bot rents Box 1 (5 a day, the first day paid); a second
# is refused; the office lists it; a Postal day charges the rent; the price changes; the bot releases it. Then with no
# money and four Postal days the lease runs up arrears and lapses after the 3-day grace.
console "minecraft:tp $BOT_NAME 0.5 -60 3"
sleep 2
check "a box can't be rented away from its office" bot_cmd "/pobox rent testville" 'CHAT .*Rent a box at the office'
console "minecraft:tp $BOT_NAME 20 -59 4"
sleep 2
check "the bot rents a box at Testville" bot_cmd "/pobox rent testville" 'CHAT .*You rent Box 1 at the Testville Post Office, for .*5(\.00)? a day'
check "/pobox shows it" bot_cmd "/pobox" 'CHAT .*Box 1 at the Testville Post Office'
check "a second box is refused" bot_cmd "/pobox rent testville" 'CHAT .*You already rent Box 1 at Testville'
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "pobox list testville"
check "the office lists the box" wait_log "$SERVER_LOG" 'Box 1: .*PostalBot' 5 "$from"
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "postal bank newday"
check "a Postal day charges the rent" wait_log "$SERVER_LOG" 'box rent .*5(\.00)?' 10 "$from"
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "pobox price testville 2.5"
check "the office sets its own rent" wait_log "$SERVER_LOG" 'now costs \$2\.50 a day' 5 "$from"
check "the bot releases its box" bot_cmd "/pobox release" 'CHAT .*You no longer rent Box 1 at Testville'
check "the bot rents again" bot_cmd "/pobox rent testville" 'CHAT .*You rent Box 1'
console "eco set $BOT_NAME 0"
sleep 1
for _ in 1 2 3; do console "postal bank newday"; sleep 2; done
check "unpaid rent is owed, and the renter told" grep -qE 'CHAT .*You owe .* for your Box 1 at Testville' "$BOT_LOG"
console "postal bank newday"
check "after the grace the lease lapses" wait_log "$BOT_LOG" 'CHAT .*Your Box 1 at Testville has lapsed' 10
check "/pobox shows no box" bot_cmd "/pobox" "CHAT .*You don't rent a P.O. box"
console "pobox price testville default"
console "eco give $BOT_NAME 10000"

# ---- P.O. boxes, phase 2: delivery -----------------------------------------------------------------------------------
# A barrel beside Testville's office becomes its box cabinet. Box mail needs a rented box (and an attention naming its
# renter); the postman files it when he's back at the office, the renter hears, /pobox counts it and the box can't be
# released; opening the cabinet shows the letter, a click takes it into the inventory, and then the box can go.
console "minecraft:setblock 18 -60 3 minecraft:barrel"
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "pobox cabinet testville 18 -60 3"
check "the barrel becomes Testville's box cabinet" wait_log "$SERVER_LOG" "Testville Post Office's box cabinet" 5 "$from"
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "execute if block 18 -60 4 minecraft:oak_wall_sign"
check "with a [Postal_Box] sign" wait_log "$SERVER_LOG" 'Test passed' 5 "$from"
console "deop $BOT_NAME"
console "gamemode creative $BOT_NAME"
sleep 1
check "a player can't break the cabinet" bot_cmd "dig 18 -60 3" "CHAT .*The box cabinet belongs to the Testville Post Office"
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "execute if block 18 -60 3 minecraft:barrel"
check "it still stands" wait_log "$SERVER_LOG" 'Test passed' 5 "$from"
console "gamemode survival $BOT_NAME"
console "op $BOT_NAME"
check "opening it without a box says how to rent one" bot_cmd "use 18 -60 3 up" "CHAT .*You don't rent a box at the Testville Post Office"
check "the bot rents Box 1" bot_cmd "/pobox rent testville" 'CHAT .*You rent Box 1'
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "postal testletter testville testville box2"
check "mail for a box nobody rents is refused" wait_log "$SERVER_LOG" "Box 2 at Testville isn't rented" 5 "$from"
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "postal testletter testville testville box1 Steve"
check "mail for a box must name its renter" wait_log "$SERVER_LOG" "(is PostalBot's|No player called Steve)" 5 "$from"
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "postal testletter testville testville box1 $BOT_NAME"
check "a letter for Box 1 is handed in" wait_log "$SERVER_LOG" 'Test letter [0-9a-f-]+ handed in at Testville for Testville, Box1' 5 "$from"
console "postal start"
check "the postman files it, and the renter hears" wait_log "$BOT_LOG" "CHAT .*There's mail in your Box 1 at the Testville Post Office" 240
console "postal stop"
sleep 2
check "/pobox counts it" bot_cmd "/pobox" 'CHAT .*Box 1 at the Testville Post Office.* 1 letter\(s\) waiting'
check "the box can't be released while it holds mail" bot_cmd "/pobox release" 'CHAT .*still holds 1 letter'
check "opening the cabinet shows the letter" bot_cmd "use 18 -60 3 up" 'ITEM [0-9]+/0 Book Testville by Box1'
check "the cabinet is the bot's box" grep -qE 'SCREEN [0-9]+ Box 1, Testville' "$BOT_LOG"
from=$(( $(lines "$BOT_LOG") + 1 ))
bot "click 0"
check "clicking takes the letter into the inventory" wait_log "$BOT_LOG" 'ITEM [0-9]+/([3-9][0-9]|2[7-9]) Book Testville by Box1' 10 "$from"
sleep 1
check "then the box can be released" bot_cmd "/pobox release" 'CHAT .*You no longer rent Box 1 at Testville'

# ---- P.O. boxes, phase 3: a closed box's mail goes back --------------------------------------------------------------
# Two letters are filed in the bot's box: one from the bot itself (it owns Rose Cottage), one with no sender. Evicting
# the box sends the first back to Rose Cottage (via Testville's chest); the second, from no one, expires.
BOT_UUID=$(grep -oE "UUID of player $BOT_NAME is $UUID_RE" "$SERVER_LOG" | head -1 | grep -oE "$UUID_RE$")
check "the bot rents Box 1 again" bot_cmd "/pobox rent testville" 'CHAT .*You rent Box 1'
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "postal testletter testville testville box1 $BOT_NAME from:$BOT_UUID"
sleep 1
console "postal testletter testville testville box1 $BOT_NAME"
sleep 1
check "two letters for Box 1 are handed in" bash -c 'test "$(tail -n +"$1" "$2" | grep -c "handed in at Testville for Testville, Box1")" -ge 2' _ "$from" "$SERVER_LOG"
from=$(( $(lines "$BOT_LOG") + 1 ))
console "postal start"
check "the postman files them" wait_log "$BOT_LOG" "CHAT .*There's mail in your Box 1" 240 "$from"
console "postal stop"
sleep 3
check "/pobox counts both" bot_cmd "/pobox" 'CHAT .*Box 1 at the Testville Post Office.* 2 letter\(s\) waiting'
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "pobox evict testville 1"
check "the evicted box's letter goes back to its sender's address" wait_log "$SERVER_LOG" \
    'from a closed box goes back to its sender at testville rose' 10 "$from"
check "the one with no sender expires" grep -qE 'from a closed box has no sender: it expired' <(tail -n +"$from" "$SERVER_LOG")
check "the renter hears its letters went back" wait_log "$BOT_LOG" 'CHAT .*2 letter\(s\) left in your Box 1 at Testville went back' 10
check "and the sender why" grep -qE 'CHAT .*A letter of yours came back \(Box 1 at Testville was closed\)' "$BOT_LOG"
from=$(( $(lines "$SERVER_LOG") + 1 ))
console "execute if items block 20 -60 0 container.* minecraft:written_book[minecraft:written_book_content~{author:\"rose_cottage\"}]"
check "the returned letter is in Testville's chest" wait_log "$SERVER_LOG" 'Test passed' 5 "$from"

# ---- Mass mailers (docs/design/central-office.md §9) -----------------------------------------------------------------
# The bot subscribes to Testville's list. Holding a signed book it plans a mailing to Testville's subscribers (one
# mailbox: its own Rose Cottage), sends it, and the [Distribution] copy is in Rose Cottage's chest. Unsubscribed, the
# list reaches nobody. Without Towny there's no town to mail; an admin can mail every address on the server.
check "the bot subscribes to Testville's list" bot_cmd "/postal subscribe testville" "CHAT .*You're on the Testville Post Office's mailing list"
check "/postal lists shows it" bot_cmd "/postal lists" 'CHAT .*the Testville Post Office'
console "minecraft:clear $BOT_NAME"
console 'minecraft:give '"$BOT_NAME"' minecraft:written_book[minecraft:written_book_content={title:"Town News",author:"PostalBot",pages:["Hello, subscribers!"]}]'
sleep 1
check "a mailing to Testville's subscribers reaches one mailbox" bot_cmd "/postal mailer testville" \
    "CHAT .*A mailing to the Testville Post Office's subscribers: 1 mailbox "
check "confirming sends it" bot_cmd "/postal mailer testville confirm" 'CHAT .*Your mailing went to 1 mailbox'
from=$(( $(lines "$SERVER_LOG") + 1 ))
console 'execute if items block 14 -60 6 container.* minecraft:written_book[minecraft:written_book_content~{title:"[Distribution]"}]'
check "the copy is in Rose Cottage's mailbox" wait_log "$SERVER_LOG" 'Test passed' 5 "$from"
check "the bot unsubscribes" bot_cmd "/postal unsubscribe testville" "CHAT .*You're off the Testville Post Office's mailing list"
console 'minecraft:give '"$BOT_NAME"' minecraft:written_book[minecraft:written_book_content={title:"More News",author:"PostalBot",pages:["Anyone?"]}]'
sleep 1
check "then the list reaches nobody" bot_cmd "/postal mailer testville" "CHAT .*subscribers: 0 mailboxes"
check "without Towny there's no town to mail" bot_cmd "/postal mailer town" "CHAT .*You don't live in a Towny town"
check "an admin can mail every address on the server" bot_cmd "/postal mailer server" "CHAT .*A mailing to every address on the server: [1-9][0-9]* mailbox"
console "minecraft:clear $BOT_NAME"

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
