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
    "postal debug" "postal start" "sleep:270" "tlist" "alist Testville" "npc list" "postal stop" "sleep:5" \
    "data get block 40 -60 0 Items" "data get block 20 -60 0 Items" "data get block 0 -60 0 Items"

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
    check "$phase: no command exceptions" no_match "Command exception: /(postal|tlist|alist)" "$log"
done
check "phase1: unconfigured start refused cleanly" grep -q "could not compile town list" "$WORK_DIR/phase1.log"
check "phase2: economy hooked" grep -q "for economy\." "$WORK_DIR/phase2.log"
check "phase2: dispatcher started" grep -q "VA_Postal started" "$WORK_DIR/phase2.log"
check "phase2: Central office account created" test -e "$SERVER/plugins/Essentials/userdata/$CENTRAL_ACCOUNT.yml"
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
check "phase2: no dispatcher watchdog restart" no_match "Activity timeout for job queue" "$WORK_DIR/phase2.log"
# Postal recreates its NPCs on every start; Citizens must not save them (saved copies came back as idle
# duplicates on each restart).
check "phase2: no PostMan/PostMaster saved by Citizens" \
    test "$(grep -c "name: '&cPost" "$SERVER/plugins/Citizens/saves.yml" 2>/dev/null || true)" -eq 0

echo
echo "---- Postal output (phase 2) ----"
sed 's/\x1b\[[0-9;]*m//g' "$WORK_DIR/phase2.log" | grep -E "\[Postal\]|Exception|Caused by|WARN\]: +at " | grep -v "Nag author" | awk '{k=substr($0,16)} !seen[k]++' | head -150 || true

exit $fail
