#!/usr/bin/env bash
# Boots a throwaway Paper server with Postal and its dependencies, seeds a minimal postal network
# (central office, one local office, one address with a route), starts the dispatcher, and fails
# on anything that looks like Postal breaking.
#
# Usage: ci/smoke-test.sh <path-to-postal.jar>
# Env:   JAVA (default: java), WORK_DIR (default: ./smoke), PAPER_VERSION (default below)
#        STORAGE=mysql runs it on MySQL/MariaDB instead of SQLite: MYSQL_HOST (default 127.0.0.1), MYSQL_PORT
#        (3306), MYSQL_DATABASE (postal), MYSQL_USER (postal), MYSQL_PASSWORD (postal). The database must be
#        empty: the store's tables are created on the first start.
#
# Running this accepts the Minecraft EULA for a throwaway test server.
set -euo pipefail

POSTAL_JAR="$(realpath "${1:?usage: smoke-test.sh <postal.jar>}")"
JAVA="${JAVA:-java}"
WORK_DIR="${WORK_DIR:-smoke}"
STORAGE="${STORAGE:-sqlite}"
# shellcheck source=ci/lib.sh
SEED_SIZE=small  # one address, so three deliveries fit in the run
OWNER_UUID="4b1d3a6e-1c2f-4d5e-9a7b-0c1d2e3f4a5b"  # a made-up player who owns Testville and Home in phase 3
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

echo "== Phase 2: dispatcher starts on the seeded network and runs routes"
run_server "$WORK_DIR/phase2.log" \
    "postal debug" "postal start" "sleep:5" "postal testletter testville testville home" "postal testparcel testville testville home retired" "sleep:263" "tlist" "alist Testville" "npc list" "showroute testville home" "/" "sleep:2" "postal bank" "postal bank newday" "sleep:1" "postal stop" "sleep:5" \
    "data get block 40 -60 0 Items" "data get block 20 -60 0 Items" "data get block 0 -60 0 Items" "postal track recent" \
    "postal accept last 30 -60 5" "sleep:1" "data get block 30 -60 5 Items" "postal accept last 31 -60 5" \
    "postal track last" "execute if block 20 -60 -4 minecraft:air run say parcel chest collected" \
    "postal store" "postal directory" "postal directory main"

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
    "postal testparcel testville testville home" \
    "save-all flush" "sleep:40"
echo "== Phase 4: restart; reconciliation and the postman finish the job"
run_server "$WORK_DIR/phase4.log" "postal start" "sleep:150" "postal stop" "sleep:3" \
    "postal track recent" "data get block 40 -60 0 Items" "data get block 20 -60 0 Items"

# Phases 5-6: a letter whose office chest is destroyed is detected as MISSING.
echo "== Phase 5: post a letter and destroy the office chest holding it"
run_server "$WORK_DIR/phase5.log" "postal testletter testville testville home" "setblock 20 -60 0 minecraft:air" "sleep:2"
echo "== Phase 6: restart; reconciliation marks it MISSING"
run_server "$WORK_DIR/phase6.log" "sleep:5" "postal track recent" "setblock 20 -60 0 minecraft:chest[facing=south]" "sleep:2"


# Phase 7 checks the full-reserve numbers with player-owned property, which earlier phases can't have: an
# owned mailbox's sign must name its owner, so ownership would break the delivery checks above.
echo "== Phase 7: reserves with a player-owned office and address"
python3 - "$CONFIG" "$OWNER_UUID" <<'PY'
import sys
path, uuid = sys.argv[1], sys.argv[2]
text = open(path).read()
owner = "%s  Owner:\n%s    Uuid: " + uuid + "\n"
text = text.replace("    Testville:\n      Location:", "    Testville:\n" + owner % ("    ", "    ") + "      Location:", 1)
text = text.replace("    Home:\n      Residence:", "    Home:\n" + owner % ("    ", "    ") + "      Residence:", 1)
open(path, "w").write(text)
PY
# Fund Central (20000) and Testville (2000) directly in EssentialsX's saved balances (server is stopped).
python3 - "$SERVER/plugins/Essentials/userdata" <<'PY'
import glob, re, sys
for path in glob.glob(sys.argv[1] + "/*.yml"):
    text = open(path).read()
    for name, money in (("postal-central", "20000"), ("postal-po-testville", "2000")):
        if re.search(r"(?m)^last-account-name: %s$" % name, text):
            text = re.sub(r"(?m)^money: .*$", "money: '%s'" % money, text)
            open(path, "w").write(text)
PY
run_server "$WORK_DIR/phase7.log" "postal bank" "postal office testville" "postal office testville withdraw 5" \
    "postal bank newday" "sleep:1" "postal bank policy upkeep.base 5000" "postal bank newday" "sleep:1" \
    "postal office testville" "postal bank policy dividend.cap 0.9" "postal bank report" "postal bank" "sleep:1"

# Phase 8 (#13): `postal start` after /setcentral but before the first /setlocal. The config is cut back to the
# settings above the seeded network plus Central, so there is no local office and no Address section.
echo "== Phase 8: postal start with Central set but no local office or addresses"
cp "$CONFIG" "$WORK_DIR/config.full.yml"
python3 - "$CONFIG" <<'PY'
import sys
p = sys.argv[1]
text = open(p).read()
head = text.split("\nPostoffice:\n", 1)[0]
open(p, "w").write(head + "\nPostoffice:\n  Central:\n    Location: world,0.0,-60.0,2.0\n")
PY
run_server "$WORK_DIR/phase8.log" "postal start" "sleep:3" "postal stop" "sleep:2" "postal speed NaN" "postal speed"
cp "$WORK_DIR/config.full.yml" "$CONFIG"

# Phase 9 (#12): owners who are not online. The smoke server has no players, so any owner is offline. Home is owned
# by a player the server has seen (their saved player data supplies the name), Testville by one it has never seen.
# Starting the dispatcher rewrites Testville's office sign, and /tlist and /alist list the owners.
echo "== Phase 9: offline owners are listed and signed by account name"
KNOWN_UUID="7c2e9f10-5a4b-4c3d-8e6f-1a2b3c4d5e6f"
KNOWN_NAME="PostalOwner"
python3 - "$CONFIG" "$OWNER_UUID" "$KNOWN_UUID" "$KNOWN_NAME" "$SERVER/world/players/data" <<'PY'
import gzip, os, struct, sys
path, unknown, known, name, datadir = sys.argv[1:]
# The server learns an offline player's name from their player data (Bukkit's lastKnownName), so write a
# minimal file for the known owner: {bukkit: {lastKnownName: <name>}}.
def tag_str(key, val):
    k, v = key.encode(), val.encode()
    return b"\x08" + struct.pack(">H", len(k)) + k + struct.pack(">H", len(v)) + v
inner = tag_str("lastKnownName", name) + b"\x00"
nbt = b"\x0a\x00\x00" + b"\x0a" + struct.pack(">H", 6) + b"bukkit" + inner + b"\x00"
os.makedirs(datadir, exist_ok=True)
with gzip.open(os.path.join(datadir, known + ".dat"), "wb") as f:
    f.write(nbt)
text = open(path).read()
# Phase 7 gave both Testville and Home the unknown owner; Home's (the last one in the file) goes to the known player.
old = "Uuid: " + unknown
i = text.rindex(old)
open(path, "w").write(text[:i] + "Uuid: " + known + text[i + len(old):])
PY
run_server "$WORK_DIR/phase9.log" "tlist testville" "alist Testville" "postal start" "sleep:15" \
    "data get block 20 -60 1 front_text.messages" "data get block 40 -60 1 front_text.messages" \
    "postal stop" "sleep:3"

# Phase 10 (#12): an owner entry with no usable UUID (hand-edited or damaged config) reads as no owner.
echo "== Phase 10: an owner entry without a usable UUID is treated as no owner"
python3 - "$CONFIG" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path).read()
text, n = re.subn(r"(\n( +)Owner:\n\2 +Uuid: )[0-9a-f-]+", r"\1not-a-uuid", text)
assert n == 2, n
open(path, "w").write(text)
PY
run_server "$WORK_DIR/phase10.log" "tlist testville" "alist Testville" "postal start" "sleep:15" \
    "data get block 20 -60 1 front_text.messages" "data get block 40 -60 1 front_text.messages" \
    "postal stop" "sleep:3"

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
check "phase2: /postal bank lists Central and the office" \
    grep -qE "Central .*balance .*owes .*target" "$WORK_DIR/phase2.log"
check "phase2: /postal bank shows Testville's reserve" grep -qE "Testville.*Server \|" "$WORK_DIR/phase2.log"
check "phase2: a Postal day runs on demand" grep -q "Postal day: Central" "$WORK_DIR/phase2.log"
check "phase2: no blanket distribution" no_match "Daily distribution of central proceeds" "$WORK_DIR/phase2.log"
# Owned: Testville (office price 5000, seeded with its 500 floor) and Home (address price 500).
# Testville owes 250 and keeps 250 + 500; Central owes (5000 - 500) + 250 = 4750 and targets 9750.
sed $'s/\x1b\\[[0-9;]*m//g' "$WORK_DIR/phase7.log" > "$WORK_DIR/phase7.txt"
check "phase7: Central owes the office price less the seed, plus its half of the address" \
    grep -qE "Central .*balance \\\$20,000 owes \\\$4,750 target \\\$9,750" "$WORK_DIR/phase7.txt"
# Day 1: upkeep 50 + 5 x 1 address = 55 from Testville's 2000 (it keeps 750). Central's surplus over 9750 is
# 10305; half of it is the dividend pool, but no office worked, so it all carries.
check "phase7: day 1 collects upkeep and carries an unearned dividend" grep -qF \
    'Postal day: Central $20,055 (owes $4,750, target $9,750); upkeep $55, swept $0, seeded $0, dividends $0 of $5,152.50, arrears $0' \
    "$WORK_DIR/phase7.txt"
check "phase7: policy changes upkeep at runtime" grep -q "upkeep.base set to 5000." "$WORK_DIR/phase7.txt"
# Day 2: upkeep 5005 due, but Testville only has 1945 - 750 = 1195 above its reserve: it pays that and owes 3810.
check "phase7: upkeep never touches the reserve; the rest is arrears" grep -qF \
    'upkeep $1,195, swept $0, seeded $0' "$WORK_DIR/phase7.txt"
check "phase7: arrears block withdrawals" grep -qF '(upkeep arrears $3,810: withdrawals blocked)' "$WORK_DIR/phase7.txt"
check "phase7: policy rejects a dividend cap that would make self-mailing pay" \
    grep -q "dividend.cap must be between 0 and 0.45." "$WORK_DIR/phase7.txt"
check "phase7: flow report lists closed days" grep -q "Day to .*internal: upkeep" "$WORK_DIR/phase7.txt"
# Closed economy: Central + Testville held 22000 before and still do (21250 + 750).
check "phase7: no money created or destroyed" grep -qE "Central balance \\\$21,250 .*" "$WORK_DIR/phase7.txt"
check "phase7: Testville ends exactly at its reserve" grep -qE "Testville: .* \| \\\$750 \| \\\$750 " "$WORK_DIR/phase7.txt"
check "phase7: Testville keeps its half of the address refund plus the floor" grep -qF '$750 ($250 + $500)' "$WORK_DIR/phase7.txt"
check "phase7: console sees an office's position" grep -qE "Testville: balance .* reserve \\\$750 \\(owes \\\$250 for 1 player-owned address" "$WORK_DIR/phase7.txt"
check "phase7: only the owner moves an office's money" grep -q "Only the office's owner can move its money, in game." "$WORK_DIR/phase7.txt"
check "phase7: no Postal stack traces" no_match "at .*com\\.vodhanel\\." "$WORK_DIR/phase7.txt"
check "phase2: console /showroute highlights the route" grep -q "Waypoints have been highlighted for everyone online" "$WORK_DIR/phase2.log"
plain() { sed $'s/\x1b\\[[0-9;]*m//g' "$WORK_DIR/$1.log"; }
# Tracked letters (see /postal testletter, /postal track).
if [ "$STORAGE" = mysql ]; then
    check "phase2: the store opened on MySQL" grep -q "Mail store: MySQL" "$WORK_DIR/phase2.log"
else
    check "phase2: the store opened" grep -q "Mail store: SQLite" "$WORK_DIR/phase2.log"
fi
# P3: the store's health and the network directory.
check "phase2: /postal store reports no failures" grep -qE "Failures: 0" <(plain phase2)
check "phase2: /postal store lists this server" grep -qE "Server main \(this one\): last seen [0-9]+ s ago" <(plain phase2)
check "phase2: the directory has Testville and its address" grep -qE "main / testville: 1 addresses" <(plain phase2)
check "phase2: the directory lists Home" grep -qE "^\[[^]]*\]: +home$" <(plain phase2)
check "phase2: a tracked letter is delivered to Home" grep -qE "testville, home: DELIVERED at CHEST@world,40,-60,0" <(plain phase2)
# Parcels (P2): items held in the record, the label routed like a letter, items handed over exactly once.
check "phase2: a parcel's label is delivered to Home" grep -qE "\[parcel\] testville, home: DELIVERED at CHEST@world,40,-60,0" <(plain phase2)
parcel_items() { plain phase2 | grep -E "\]: 30, -60, 5 has the following block data" | tail -1; }
check "phase2: the parcel's renamed sword arrives intact" grep -q "Test Blade" <(parcel_items)
check "phase2: the parcel's enchantments arrive intact" grep -qE 'sharpness"?: ?5' <(parcel_items)
check "phase2: the parcel's other stacks arrive" grep -qE 'oak_log.*count: ?32|count: ?32.*oak_log' <(parcel_items)
# The parcel also carries an item recorded under an id this Minecraft doesn't have (as if an upgrade removed it).
check "phase2: an item gone from the game is left out" no_match "minecraft:paper" <(parcel_items)
check "phase2: the recipient is told what was left out" grep -q "(minecraft:postal_retired_item) no longer exists in this version of Minecraft" <(plain phase2)
check "phase2: the log names the left-out item" grep -q "left out: minecraft:postal_retired_item" <(plain phase2)
check "phase2: the parcel's history notes the left-out item" grep -q "left out (no longer in the game): minecraft:postal_retired_item" <(plain phase2)
check "phase2: a parcel is accepted only once" grep -q "Can't accept it" <(plain phase2)
check "phase2: the packed chest was collected" grep -q "parcel chest collected" <(plain phase2)
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
parcel_id() { plain phase4 | grep -oE "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12} \[parcel\] testville, home: DELIVERED" | head -1 | cut -d' ' -f1; }
check "phase4: the parcel posted across the kill is delivered" test -n "$(parcel_id)"
parcel_once() {
    local id home
    id=$(parcel_id)
    home=$(plain phase4 | grep -E "\]: 40, -60, 0 has the following block data" | tail -1 | grep -o "$id" | wc -l)
    [ "$home" -eq 1 ] || { echo "    parcel $id: $home labels in Home"; return 1; }
}
check "phase4: its label is in Home exactly once" parcel_once
check "phase6: a letter whose chest was destroyed is MISSING" grep -qE "testville, home: MISSING at CHEST@world,20,-60,0" <(plain phase6)
for phase in phase3 phase4 phase5 phase6; do
    check "$phase: no Postal stack traces" no_match "at .*com\.vodhanel\." "$WORK_DIR/$phase.log"
done
check "phase2: no dispatcher watchdog restart" no_match "Activity timeout for job queue" "$WORK_DIR/phase2.log"
# Postal recreates its NPCs on every start; Citizens must not save them (saved copies came back as idle
# duplicates on each restart).
check "phase2: no PostMan/PostMaster saved by Citizens" \
    test "$(grep -c "name: '&cPost" "$SERVER/plugins/Citizens/saves.yml" 2>/dev/null || true)" -eq 0


# Phase 8 (#13): the unconfigured start is refused with guidance, not an exception.
check "phase8: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase8.log"
check "phase8: no exception on postal start" no_match "NullPointerException|Command exception: /?postal|at .*com\.vodhanel\." "$WORK_DIR/phase8.log"
check "phase8: postal start aborts cleanly" grep -q "could not compile town list" "$WORK_DIR/phase8.log"
check "phase8: postal start says a local post office is needed" grep -q "cannot find a local post office to service" "$WORK_DIR/phase8.log"
check "phase8: the dispatcher did not start" no_match "VA_Postal started" "$WORK_DIR/phase8.log"
check "phase8: postal speed refuses NaN" grep -q "Speed factor must be 0.5 - 2.0" "$WORK_DIR/phase8.log"
check "phase8: the speed factor is unchanged after NaN" no_match "Current speed factor: NaN" "$WORK_DIR/phase8.log"

# Phase 9 (#12): offline owners show by account name; a player the server has never seen shows a placeholder.
check "phase9: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase9.log"
check "phase9: no exception or stack trace" no_match "NullPointerException|Command exception: /?(postal|tlist|alist)|at .*com\.vodhanel\." "$WORK_DIR/phase9.log"
check "phase9: dispatcher started with offline owners" grep -q "VA_Postal started" "$WORK_DIR/phase9.log"
check "phase9: /tlist names Home's offline owner" grep -qE "Home[.]+ +$KNOWN_NAME" <(plain phase9)
check "phase9: /alist names Home's offline owner" grep -qE "^.*Home +$KNOWN_NAME" <(plain phase9)
check "phase9: Testville's sign carries the placeholder for a never-seen owner" grep -qF "\"${OWNER_UUID:0:15}\"" <(plain phase9)

# Phase 10 (#12): no usable owner UUID reads as no owner.
check "phase10: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase10.log"
check "phase10: no exception or stack trace" no_match "NullPointerException|Command exception: /?(postal|tlist|alist)|at .*com\.vodhanel\." "$WORK_DIR/phase10.log"
check "phase10: dispatcher started" grep -q "VA_Postal started" "$WORK_DIR/phase10.log"
check "phase10: /tlist lists the office and address as server-owned" grep -qE "Home[.]+ +server" <(plain phase10)

echo
echo "---- Postal output (phase 2) ----"
sed 's/\x1b\[[0-9;]*m//g' "$WORK_DIR/phase2.log" | grep -E "\[Postal\]|Exception|Caused by|WARN\]: +at " | grep -v "Nag author" | awk '{k=substr($0,16)} !seen[k]++' | head -150 || true

exit $fail
