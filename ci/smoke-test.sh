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
PAPER_VERSION="${PAPER_VERSION:-26.1.2}"

# Pinned dependency builds. Bump deliberately; Citizens dev builds are tied to MC versions.
CITIZENS_URL="https://ci.citizensnpcs.co/job/Citizens2/4256/artifact/dist/target/Citizens-2.0.44-b4256.jar"
VAULT_URL="https://cdn.modrinth.com/data/ayRaM8J7/versions/qZgRzoYs/VaultUnlocked-2.20.3.jar"
ESSENTIALS_URL="https://cdn.modrinth.com/data/hXiIvTyT/versions/nY6VN1XH/EssentialsX-2.22.0.jar"

# Office account UUIDs Postal derives (see OfficeAccounts / OfficeAccountsTest).
CENTRAL_ACCOUNT="2ca1fae9-c175-239e-9eb2-df89430f098b"

mkdir -p "$WORK_DIR/cache"
WORK_DIR="$(realpath "$WORK_DIR")"
CACHE="$WORK_DIR/cache"
SERVER="$WORK_DIR/server"

fetch() { # url dest
    if [ ! -s "$2" ]; then
        echo "Downloading $1"
        curl -fsSL --retry 3 -o "$2.part" "$1"
        mv "$2.part" "$2"
    fi
}

paper_url() {
    curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds" | python3 -c '
import sys, json
builds = json.load(sys.stdin)
stable = [b for b in builds if b.get("channel") == "STABLE"] or builds
print(stable[0]["downloads"]["server:default"]["url"])'
}

fetch "$(paper_url)" "$CACHE/paper-$PAPER_VERSION.jar"
fetch "$CITIZENS_URL" "$CACHE/Citizens.jar"
fetch "$VAULT_URL" "$CACHE/VaultUnlocked.jar"
fetch "$ESSENTIALS_URL" "$CACHE/EssentialsX.jar"

rm -rf "$SERVER"
mkdir -p "$SERVER/plugins"
cp "$CACHE/paper-$PAPER_VERSION.jar" "$SERVER/paper.jar"
cp "$CACHE/Citizens.jar" "$CACHE/VaultUnlocked.jar" "$CACHE/EssentialsX.jar" "$SERVER/plugins/"
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

# Flat world: the surface to stand on is y=-60. Chests face south; their signs hang on the south side.
SIGN_LOCAL='minecraft:oak_wall_sign[facing=south]{front_text:{messages:["[Postal_Mail]","Testville","[Local]",""]}}'
SIGN_HOME='minecraft:oak_wall_sign[facing=south]{front_text:{messages:["[Postal_Mail]","Testville","Home",""]}}'

echo "== Phase 1: plugin enables, commands respond, world is seeded"
run_server "$WORK_DIR/phase1.log" \
    "plugins" "postal" "tlist" "alist" "postal start" \
    "forceload add -16 -16 64 16" \
    "setblock 0 -60 0 minecraft:chest[facing=south]" \
    "setblock 20 -60 0 minecraft:chest[facing=south]" \
    "setblock 20 -60 1 $SIGN_LOCAL" \
    "setblock 40 -60 0 minecraft:chest[facing=south]" \
    "setblock 40 -60 1 $SIGN_HOME" \
    "sleep:3"

# Seed the network the way /setcentral, /setlocal, /setaddr and the route editor would.
CONFIG="$SERVER/plugins/Postal/config.yml"
sed -i "0,/^  Use: 'false'/s//  Use: 'true'/" "$CONFIG"
cat >> "$CONFIG" <<'EOF'
Postoffice:
  Central:
    Location: world,0.0,-60.0,2.0
  Local:
    Testville:
      Location: world,20.0,-60.0,2.0
Address:
  Testville:
    Home:
      Residence:
        Location: world,40.0,-60.0,2.0
      Route:
        '0':
          Location: world,20.0,-60.0,2.0
        '1':
          Location: world,25.0,-60.0,2.0
        '2':
          Location: world,30.0,-60.0,2.0
        '3':
          Location: world,35.0,-60.0,2.0
        '4':
          Location: world,40.0,-60.0,2.0
EOF

echo "== Phase 2: dispatcher starts on the seeded network and runs routes"
run_server "$WORK_DIR/phase2.log" \
    "postal debug" "postal start" "sleep:110" "tlist" "alist Testville" "npc list" "postal stop" "sleep:5"

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
check "phase2: round trip recorded for Home" grep -qE "Home +Server +Seconds: [1-9]" "$WORK_DIR/phase2.log"
check "phase2: no dispatcher watchdog restart" no_match "Activity timeout for job queue" "$WORK_DIR/phase2.log"

echo
echo "---- Postal output (phase 2) ----"
sed 's/\x1b\[[0-9;]*m//g' "$WORK_DIR/phase2.log" | grep -E "\[Postal\]|Exception|Caused by|WARN\]: +at " | grep -v "Nag author" | awk '{k=substr($0,16)} !seen[k]++' | head -150 || true

exit $fail
