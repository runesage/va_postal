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
    "plugins" "postal" "tlist" "alist" "postal start" "${SEED_COMMANDS[@]}" "sleep:3"

# Seed the network the way /setcentral, /setlocal, /setaddr and the route editor would.
CONFIG="$SERVER/plugins/Postal/config.yml"
sed -i "0,/^  Use: 'false'/s//  Use: 'true'/" "$CONFIG"
seed_config "$CONFIG"

echo "== Phase 2: dispatcher starts on the seeded network and runs routes"
run_server "$WORK_DIR/phase2.log" \
    "postal debug" "postal start" "sleep:270" "tlist" "alist Testville" "npc list" "postal stop" "sleep:5"

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
check "phase2: no dispatcher watchdog restart" no_match "Activity timeout for job queue" "$WORK_DIR/phase2.log"

echo
echo "---- Postal output (phase 2) ----"
sed 's/\x1b\[[0-9;]*m//g' "$WORK_DIR/phase2.log" | grep -E "\[Postal\]|Exception|Caused by|WARN\]: +at " | grep -v "Nag author" | awk '{k=substr($0,16)} !seen[k]++' | head -150 || true

exit $fail
