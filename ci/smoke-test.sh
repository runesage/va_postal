#!/usr/bin/env bash
# Boots a throwaway Paper server with Postal and its dependencies, seeds a minimal postal network
# (central office, one local office, one address with a route), starts the dispatcher, and fails
# on anything that looks like Postal breaking.
#
# Usage: ci/smoke-test.sh <path-to-postal.jar>
# Env:   JAVA (default: java), WORK_DIR (default: ./smoke), PAPER_VERSION (default below)
#
# Running this accepts the Minecraft EULA for a throwaway test server.
set -euo pipefail

POSTAL_JAR="$(realpath "${1:?usage: smoke-test.sh <postal.jar>}")"
JAVA="${JAVA:-java}"
WORK_DIR="${WORK_DIR:-smoke}"
# shellcheck source=ci/lib.sh
SEED_SIZE=small  # one address, so three deliveries fit in the run
source "$(dirname "$0")/lib.sh"

mkdir -p "$WORK_DIR/cache"
WORK_DIR="$(realpath "$WORK_DIR")"
CACHE="$WORK_DIR/cache"
SERVER="$WORK_DIR/server"

download_server_jars "$CACHE"

rm -rf "$SERVER"
install_server "$CACHE" "$SERVER"
cp "$POSTAL_JAR" "$SERVER/plugins/Postal.jar"
echo "eula=true" > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<'EOF'
online-mode=false
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
max-players=1
EOF

# run_server <log> <command>...: boots the server, feeds console commands once it's up, then stops it.
run_server() {
    local log="$1"; shift
    rm -f "$SERVER/logs/latest.log"
    (
        for _ in $(seq 1 300); do
            grep -q 'Done (' "$SERVER/logs/latest.log" 2>/dev/null && break
            sleep 1
        done
        sleep 5
        for cmd in "$@"; do
            if [[ "$cmd" == sleep:* ]]; then sleep "${cmd#sleep:}"; else echo "$cmd"; sleep 2; fi
        done
        echo stop
    ) | (cd "$SERVER" && timeout 600 "$JAVA" -Xms1G -Xmx2G -jar paper.jar nogui) > "$log" 2>&1 || true
}

echo "== Phase 1: plugin enables, commands respond, world is seeded"
run_server "$WORK_DIR/phase1.log" \
    "plugins" "postal" "tlist" "alist" "postal start" "${SEED_COMMANDS[@]}" \
    "minecraft:item replace block 40 -60 0 container.0 with minecraft:diamond" \
    "minecraft:item replace block 20 -60 0 container.0 with minecraft:emerald" \
    "minecraft:item replace block 0 -60 0 container.0 with minecraft:gold_ingot" "sleep:3"

# Seed the network the way /setcentral, /setlocal, /setaddr and the route editor would.
CONFIG="$SERVER/plugins/Postal/config.yml"
sed -i "0,/^  Use: 'false'/s//  Use: 'true'/" "$CONFIG"
seed_config "$CONFIG"
# The leather uniform is off by default; turn it on here so the dyed-armor path runs too.
python3 - "$CONFIG" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
s, n = re.subn(r"(\n( +)Uniform:\n(?:\2 +.*\n)*?\2 +Enabled: )'false'", r"\1'true'", s)
assert n == 1, "uniform toggle not found in config"
open(p, 'w').write(s)
PY

echo "== Phase 2: dispatcher starts on the seeded network and runs routes"
run_server "$WORK_DIR/phase2.log" \
    "postal debug" "postal start" "sleep:5" "postal testletter testville testville home" "sleep:265" "tlist" "alist Testville" "npc list" "showroute testville home" "/" "sleep:2" "postal stop" "sleep:5" \
    "data get block 40 -60 0 Items" "data get block 20 -60 0 Items" "data get block 0 -60 0 Items" "postal track recent"

# Recorded now: on its next start EssentialsX may purge NPC accounts still at the starting balance
# (Postal recreates them on demand).
central_account_created=no
[ -e "$SERVER/plugins/Essentials/userdata/$CENTRAL_ACCOUNT.yml" ] && central_account_created=yes

# run_server_then_kill <log> <command>...: like run_server, but ends with kill -9 instead of a clean stop.
run_server_then_kill() {
    local log="$1"; shift
    local fifo="$WORK_DIR/console.fifo"
    rm -f "$SERVER/logs/latest.log" "$fifo"
    mkfifo "$fifo"
    (cd "$SERVER" && exec "$JAVA" -Xms1G -Xmx2G -jar paper.jar nogui < "$fifo") > "$log" 2>&1 &
    local pid=$!
    exec 3>"$fifo"
    for _ in $(seq 1 300); do
        grep -q 'Done (' "$SERVER/logs/latest.log" 2>/dev/null && break
        sleep 1
    done
    sleep 5
    for cmd in "$@"; do
        if [[ "$cmd" == sleep:* ]]; then sleep "${cmd#sleep:}"; else echo "$cmd" >&3; sleep 2; fi
    done
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    exec 3>&-
    rm -f "$fifo"
}

# Phases 3-4: a letter survives a hard kill (possibly mid-route, with the world behind the mail store) and is
# delivered exactly once after the restart.
echo "== Phase 3: post a letter, then kill -9 the server"
run_server_then_kill "$WORK_DIR/phase3.log" "postal start" "sleep:10" "postal testletter testville testville home" \
    "save-all flush" "sleep:40"
echo "== Phase 4: restart; reconciliation and the postman finish the job"
run_server "$WORK_DIR/phase4.log" "postal start" "sleep:150" "postal stop" "sleep:3" \
    "postal track recent" "data get block 40 -60 0 Items" "data get block 20 -60 0 Items"

# Phases 5-6: a letter whose office chest is destroyed is detected as MISSING.
echo "== Phase 5: post a letter and destroy the office chest holding it"
run_server "$WORK_DIR/phase5.log" "postal testletter testville testville home" "setblock 20 -60 0 minecraft:air" "sleep:2"
echo "== Phase 6: restart; reconciliation marks it MISSING"
run_server "$WORK_DIR/phase6.log" "sleep:5" "postal track recent" "setblock 20 -60 0 minecraft:chest[facing=south]" "sleep:2"

# ---- Assertions ---------------------------------------------------------------------------
fail=0
check() { # description, command...
    local desc="$1"; shift
    if "$@"; then echo "PASS: $desc"; else echo "FAIL: $desc"; fail=1; fi
}
no_match() { ! grep -qE "$1" "$2"; }

for phase in phase1 phase2; do
    log="$WORK_DIR/$phase.log"
    check "$phase: Postal enabled" grep -q "Enabling Postal" "$log"
    check "$phase: no enable failure" no_match "Error occurred while enabling Postal" "$log"
    check "$phase: no Postal stack traces" no_match "at .*com\.vodhanel\." "$log"
    check "$phase: no command exceptions" no_match "Command exception: /?(postal|tlist|alist|showroute)" "$log"
done
check "phase1: unconfigured start refused cleanly" grep -q "could not compile town list" "$WORK_DIR/phase1.log"
check "phase2: economy hooked" grep -q "for economy\." "$WORK_DIR/phase2.log"
check "phase2: dispatcher started" grep -q "VA_Postal started" "$WORK_DIR/phase2.log"
check "phase2: Central office account created" test "$central_account_created" = yes
check "phase2: postman walked the route to the address" grep -q "Arrived at address" "$WORK_DIR/phase2.log"
# Three deliveries: the first creates Home's postal log, the next two update it (the second update reads
# a page Minecraft has trimmed of blank lines, which used to crash and send the postman into a loop).
check "phase2: three deliveries (log created, then updated twice)" \
    test "$(grep -c "Arrived at address" "$WORK_DIR/phase2.log")" -ge 3
check "phase2: existing postal log updated" grep -q "Postal log exists" "$WORK_DIR/phase2.log"
check "phase2: postman never stuck on a completed waypoint" no_match "wtr_waypoint_completed is true" "$WORK_DIR/phase2.log"
check "phase2: round trip recorded for Home" grep -qE "Home +Server +Seconds: [1-9]" "$WORK_DIR/phase2.log"
check "phase2: NPC skins and uniform configured cleanly" no_match "Unknown uniform item|needs a texture and signature" "$WORK_DIR/phase2.log"
# Each chest started with an item in slot 0, where Postal installs its postal log on the first visit.
# The log used to overwrite that slot, destroying e.g. a letter delivered on a new mailbox's first visit.
items_of() { sed $'s/\x1b\\[[0-9;]*m//g' "$WORK_DIR/phase2.log" | grep -E "\]: $1, -60, 0 has the following block data" | tail -1; }
check "phase2: postal log install kept Home's existing item" grep -q "minecraft:diamond" <(items_of "40")
check "phase2: postal log install kept Testville's existing item" grep -q "minecraft:emerald" <(items_of "20")
check "phase2: postal log install kept Central's existing item" grep -q "minecraft:gold_ingot" <(items_of "0")
check "phase2: Home has its postal log" grep -qi "postal log" <(items_of "40")
check "phase2: console /showroute highlights the route" grep -q "Waypoints have been highlighted for everyone online" "$WORK_DIR/phase2.log"
plain() { sed $'s/\x1b\\[[0-9;]*m//g' "$WORK_DIR/$1.log"; }
# Tracked letters (see /postal testletter, /postal track).
check "phase2: the store opened" grep -q "Mail store: SQLite" "$WORK_DIR/phase2.log"
check "phase2: a tracked letter is delivered to Home" grep -qE "testville, home: DELIVERED at CHEST@world,40,-60,0" <(plain phase2)
letter_ids() { plain phase4 | grep -oE "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} testville, home: DELIVERED" | cut -d' ' -f1; }
check "phase4: both letters (before and across the kill) are delivered" test "$(letter_ids | sort -u | wc -l)" -ge 2
exactly_once() {
    local id home office
    for id in $(letter_ids | sort -u); do
        home=$(plain phase4 | grep -E "\]: 40, -60, 0 has the following block data" | tail -1 | grep -o "$id" | wc -l)
        office=$(plain phase4 | grep -E "\]: 20, -60, 0 has the following block data" | tail -1 | grep -o "$id" | wc -l)
        [ "$home" -eq 1 ] && [ "$office" -eq 0 ] || { echo "    letter $id: $home in Home, $office at the office"; return 1; }
    done
}
check "phase4: each letter is in Home exactly once, none left at the office" exactly_once
check "phase4: reconciliation ran at startup" grep -q "Reconciliation:" "$WORK_DIR/phase4.log"
check "phase6: a letter whose chest was destroyed is MISSING" grep -qE "testville, home: MISSING at CHEST@world,20,-60,0" <(plain phase6)
for phase in phase3 phase4 phase5 phase6; do
    check "$phase: no Postal stack traces" no_match "at .*com\.vodhanel\." "$WORK_DIR/$phase.log"
done
check "phase2: no dispatcher watchdog restart" no_match "Activity timeout for job queue" "$WORK_DIR/phase2.log"
# Postal recreates its NPCs on every start; Citizens must not save them (saved copies came back as idle
# duplicates on each restart).
check "phase2: no PostMan/PostMaster saved by Citizens" \
    test "$(grep -c "name: '&cPost" "$SERVER/plugins/Citizens/saves.yml" 2>/dev/null || true)" -eq 0

echo
echo "---- Postal output (phase 2) ----"
sed 's/\x1b\[[0-9;]*m//g' "$WORK_DIR/phase2.log" | grep -E "\[Postal\]|Exception|Caused by|WARN\]: +at " | grep -v "Nag author" | awk '{k=substr($0,16)} !seen[k]++' | head -150 || true

exit $fail
