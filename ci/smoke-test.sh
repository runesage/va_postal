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

# Phase 11: a local office saved in a world that no longer exists must not crash `postal start`.
echo "== Phase 11: an office whose Location names a missing world"
python3 - "$CONFIG" <<'PY'
import re, sys
p = sys.argv[1]
text = open(p).read()
# Testville's Location line, wherever it sits in its block (phase 7 put an Owner section in front of it).
new = re.sub(r"(\n    Testville:\n(?:      .*\n)*?      Location: '?)world,", r"\1gone_world,", text, count=1)
assert new != text
open(p, "w").write(new)
PY
run_server "$WORK_DIR/phase11.log" "postal start" "sleep:10" "postal stop" "sleep:3"
cp "$WORK_DIR/config.full.yml" "$CONFIG"

# Phase 12: stop and start the dispatcher again within its 10 s stop clean-up. The old clean-up used to fire
# into the new run, deleting its postmen and nulling their array ("Exception creating NPC ... wtr_npc is null").
echo "== Phase 12: quick stop/start cycles, and a start while running"
run_server "$WORK_DIR/phase12.log" "postal start" "sleep:15" \
    "postal stop" "sleep:1" "postal start" "sleep:1" "postal stop" "sleep:1" "postal start" "sleep:1" \
    "postal start" "sleep:25" "postal stop" "sleep:12"

# Phase 13: address deeds (docs/design/deeds.md), driven from the console with the test helper, which uses a
# server-held deed on a chest just as a player's right-click would. A chest the postman can reach becomes an
# address, with its sign and a surveyed route; one sealed in a glass room (wide enough that no place within the
# surveyor's two blocks of the mailbox is outside) is refused and no address is made; a name the
# office already has can't be deeded; the office suggests house names.
echo "== Phase 13: address deeds"
run_server "$WORK_DIR/phase13.log" \
    "setblock 44 -60 6 minecraft:chest[facing=south]" \
    "fill 24 -61 4 34 -55 16 minecraft:glass hollow" "setblock 29 -60 9 minecraft:chest[facing=south]" \
    "postal deed names testville 5" \
    "postal deed test testville Rose Cottage 44 -60 6" "sleep:20" \
    "postal deed test testville ivy_lodge 29 -60 9" "sleep:20" \
    "postal deed test testville home 44 -60 6" \
    "alist testville" "data get block 44 -60 7 front_text" "sleep:2"

# Phase 14: the office clerk (docs/design/deeds.md §2). With the mail running, Testville's clerk stands at the side
# of its chest (20 -60 0, facing south: the west side, 19 -60 0); pushed away, it's back at its post within a few
# seconds; placed elsewhere it stands there; with the mail stopped it's gone. The office's own address price is set.
# The clerk's click, counter menu and dialog are player-only (bot test). The selectors name no type: type=player
# only searches the player list, which Citizens keeps its NPCs off.
CLERK_AT="x=19.5,y=-60,z=0.5,distance=..1"
echo "== Phase 14: the office clerk"
run_server "$WORK_DIR/phase14.log" \
    "postal start" "sleep:15" "postal clerk list" \
    "execute if entity @e[$CLERK_AT]" \
    "postal office testville price 25" "postal office testville price" \
    "minecraft:tp @e[$CLERK_AT] 26 -60 12" "sleep:8" \
    "execute if entity @e[$CLERK_AT]" \
    "postal clerk place testville 23 -60 4" "sleep:3" \
    "execute if entity @e[x=23.5,y=-60,z=4.5,distance=..1]" \
    "postal clerk reset testville" "postal stop" "sleep:12" \
    "execute if entity @e[$CLERK_AT]" "postal clerk list" "sleep:2"

# Phase 15: address applications (docs/design/deeds.md §3.2). Testville switches approval on; two applications
# (held by the server, at a free price) wait; a name under application can't be applied for twice; one is approved
# (issued), the other declined (its name free again, so it can be applied for anew); approval goes back to the default.
echo "== Phase 15: address applications"
run_server "$WORK_DIR/phase15.log" \
    "postal office testville approval" "postal office testville approval on" "postal office testville price 0" \
    "postal deed apply server testville bramble_cottage" "postal deed apply server testville Bramble Cottage" \
    "postal deed apply server testville holly_lodge" "postal office testville applications" \
    "postal office testville approve bramble_cottage" "postal office testville deny holly_lodge Too near the mill" \
    "postal office testville applications" "postal deed list server" \
    "postal deed apply server testville holly_lodge" "postal office testville approval default" "sleep:2"

# Phase 16: Central's clerk and protection (docs/design/central-office.md §2). With the mail running the Postmaster
# General stands at the west side of Central's chest (0 -60 0, facing south): -0.5 -60 0.5. A hopper under the chest
# takes nothing from it; TNT beside the chest leaves the chest and its sign (0 -60 1) standing but takes the planks
# next to them. With the mail stopped the clerk is gone. Then the crater and the hopper are filled in: the later phases
# need Central's ground (Postal finds its chest from the spot in front of it).
CENTRAL_CLERK="x=-0.5,y=-60,z=0.5,distance=..1"
echo "== Phase 16: Central's clerk and protection"
run_server "$WORK_DIR/phase16.log" \
    "postal start" "sleep:15" "postal clerk list" "execute if entity @e[$CENTRAL_CLERK]" \
    "minecraft:item replace block 0 -60 0 container.5 with minecraft:paper" \
    "setblock 0 -61 0 minecraft:hopper" "sleep:4" "data get block 0 -61 0 Items" "data get block 0 -60 0 Items" \
    "setblock 1 -60 1 minecraft:oak_planks" "summon minecraft:tnt 1 -60 0 {fuse:0}" "sleep:3" \
    "execute if block 0 -60 0 minecraft:chest" "execute if block 0 -60 1 #minecraft:signs" \
    "execute if block 1 -60 1 minecraft:oak_planks" \
    "postal stop" "sleep:12" "execute if entity @e[$CENTRAL_CLERK]" \
    "fill -5 -63 -5 6 -62 6 minecraft:dirt replace minecraft:air" \
    "fill -5 -61 -5 6 -61 6 minecraft:grass_block replace minecraft:air" "setblock 0 -61 0 minecraft:grass_block" "sleep:2"

# Phase 17: charters (docs/design/central-office.md §3), from the console with server-held charters. Approval is on by
# default: an application waits; its name is reserved; an existing office's name is refused; the application is
# declined. With autoapprove on, a charter opens Brookfield on a chest 70 blocks out (its sign, its config, the
# announcement); one too near Testville and Central is refused.
echo "== Phase 17: charters"
run_server "$WORK_DIR/phase17.log" \
    "postal charter autoapprove" "postal charter apply server Brookfield" "postal charter apply server brookfield" \
    "postal charter apply server Testville" "postal charter pending" "postal charter deny brookfield Too near the river" \
    "postal charter pending" "postal charter autoapprove on" \
    "setblock 80 -60 40 minecraft:chest[facing=south]" "postal charter test Brookfield 80 -60 40" "sleep:2" \
    "setblock 30 -60 -6 minecraft:chest[facing=south]" "postal charter test Ashford 30 -60 -6" \
    "postal charter autoapprove off" "data get block 80 -60 41 front_text" "postal clerk list" "sleep:2"

# Phase 18: rates (docs/design/central-office.md §2). The out-of-town letter rate is changed by policy; Testville sets a
# postage discount (refused above the server's maximum, then accepted); with the mail running, Central's postman
# leaves the notice of the new rate in Testville's chest on his next visit, once.
echo "== Phase 18: rates"
run_server "$WORK_DIR/phase18.log" \
    "postal bank policy rates.letter_out_town 9" "postal bank policy rates.max_discount 30" \
    "postal office testville discount 40" "postal office testville discount 20" "postal office testville discount" \
    "postal start" "sleep:60" "postal stop" "sleep:12" "data get block 20 -60 0 Items" \
    "postal office testville discount none" "postal bank policy" "sleep:2"

# Phase 19: office stats (docs/design/central-office.md §7). The earlier phases delivered letters and parcels to Home and
# lost one at Testville (phase 5); the roll-up summarises them into office_stats, and the leaderboards and Testville's
# numbers show them.
echo "== Phase 19: office stats"
run_server "$WORK_DIR/phase19.log" \
    "postal stats refresh" "sleep:5" "postal stats" "postal stats testville" "postal stats testville 1" \
    "postal stats nowhere" "sleep:2"

# Phase 20: Central's notice board (docs/design/central-office.md §6). Six wall signs on a stone wall north-west of
# Central's chest: the first two take the next panels (postage, parcels), the rest are named. A sign far from Central
# and a block that isn't a sign are refused. A rate change posts a notice; the signs show the rates, Testville on the
# busiest board, the notice, and are waxed. One is taken off the board.
echo "== Phase 20: the notice board"
BOARD_SIGNS=()
for xy in "-5 -59" "-4 -59" "-3 -59" "-5 -60" "-4 -60" "-3 -60"; do
    BOARD_SIGNS+=("setblock ${xy% *} ${xy#* } -3 minecraft:oak_wall_sign[facing=south]")
done
run_server "$WORK_DIR/phase20.log" \
    "fill -5 -60 -4 -3 -59 -4 minecraft:stone" "${BOARD_SIGNS[@]}" \
    "setblock 60 -60 60 minecraft:oak_sign" "postal board add 60 -60 60" "postal board add 10 -60 10" \
    "postal stats refresh" "sleep:5" \
    "postal board add -5 -59 -3" "postal board add -4 -59 -3" "postal board add busiest -3 -59 -3" \
    "postal board add on_time -5 -60 -3" "postal board add fewest_lost -4 -60 -3" "postal board add notices -3 -60 -3" \
    "postal bank policy rates.letter_local 5" "postal board list" \
    "data get block -5 -59 -3 front_text" "data get block -3 -59 -3 front_text" "data get block -3 -60 -3 front_text" \
    "data get block -3 -59 -3 is_waxed" "postal board remove -3 -60 -3" "postal board list" "sleep:2"

# Phase 21: Postal maps (docs/design/central-office.md §6). The network map hangs in an item frame beside the notice
# board; a map for nobody is refused from the console; the map is listed. After a restart its renderer is put back.
echo "== Phase 21: Postal maps"
run_server "$WORK_DIR/phase21.log" \
    "setblock -2 -59 -4 minecraft:stone" "summon minecraft:item_frame -2 -59 -3 {Facing:3b}" "sleep:1" \
    "postal map frame -2 -59 -3" "postal map network" "postal map nowhere x" "postal map list" \
    "data get entity @e[type=minecraft:item_frame,limit=1,sort=nearest,x=-2,y=-59,z=-3] Item" "sleep:2"
run_server "$WORK_DIR/phase21b.log" "sleep:3" "postal map list" "sleep:1"

# Phase 22: Branch of the Month (docs/design/central-office.md §6). Before any award, Testville leads; with a bonus
# bigger than anything Central holds above its target, the award is made and the bonus skipped; the sign taken off the board
# in phase 20 goes back as the award panel; Testville is the holder.
echo "== Phase 22: Branch of the Month"
run_server "$WORK_DIR/phase22.log" \
    "postal award" "postal bank policy awards.bonus 1000000000" "postal award now" "postal bank policy awards.bonus 0" "postal board add award -3 -60 -3" \
    "data get block -3 -60 -3 front_text" "postal award" "sleep:2"

# Phase 23: return to sender (issue #19), on one server. A letter from Home's owner for a server that doesn't exist
# ("!" skips the directory) leaves on a fast ship and is never collected; after Network.Return_after_days (here about
# 26 s) its own server brings it back, and the postman delivers it to the sender's mailbox, Home, with a "Returned to
# sender" page, and it ends RETURNED.
echo "== Phase 23: return to sender"
python3 - "$CONFIG" "$OWNER_UUID" <<'PY'
import re, sys
path, uuid = sys.argv[1], sys.argv[2]
text = open(path).read()
i = text.rindex("Uuid: ")  # Home's owner (the last owner in the file): the letter's sender
j = text.index("\n", i)
text = text[:i] + "Uuid: " + uuid + text[j:]
want = {"Poll_seconds": "3", "Departure_minutes": "0.1", "Transit_minutes": "0.05", "Return_after_days": "0.0003"}
head, rest = text.split("\nNetwork:\n", 1)
lines = rest.split("\n")
for k, (key, value) in enumerate(list(want.items())):
    for n, line in enumerate(lines):
        if not line.startswith(" "):
            break
        if line.startswith("  " + key + ":"):
            lines[n] = "  " + key + ": " + value
            want.pop(key)
            break
for key, value in want.items():
    lines.insert(0, "  " + key + ": " + value)
open(path, "w").write(head + "\nNetwork:\n" + "\n".join(lines))
PY
run_server "$WORK_DIR/phase23.log" \
    "postal start" "sleep:10" "postal testletter testville elsewhere:nowhere! home from:$OWNER_UUID" "sleep:240" \
    "postal track last" "data get block 40 -60 0 Items" "postal stop" "sleep:5"

# Phase 24: inspections (docs/design/central-office.md §5). Testville lost a letter in phase 5, so the hourly check
# (its first run is a minute after a start) has already sent an inspector in an earlier phase by itself. Here one is
# sent at once anyway, goes with Central's postman, and leaves a report in Testville's chest. The report is kept
# (/postal inspect) and shown on a new board sign. An unknown office is refused.
echo "== Phase 24: inspections"
run_server "$WORK_DIR/phase24.log" \
    "postal stats refresh" "sleep:5" "postal inspect testville" "postal inspect testville now" "postal inspect nowhere" \
    "postal start" "sleep:60" "postal stop" "sleep:10" "data get block 20 -60 0 Items" "postal inspect testville" "postal inspect" \
    "setblock -2 -60 -4 minecraft:stone" "setblock -2 -60 -3 minecraft:oak_wall_sign[facing=south]" \
    "postal board add inspections -2 -60 -3" "data get block -2 -60 -3 front_text" "sleep:2"

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
    check "$phase: no watchdog dump" no_match "The server has not responded for" "$log"
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
    check "$phase: no watchdog dump" no_match "The server has not responded for" "$WORK_DIR/$phase.log"
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
check "phase9: console /tlist lists the first office too" grep -qE "TESTVILLE[.]+ +${OWNER_UUID}" <(plain phase9)
check "phase9: /alist names Home's offline owner" grep -qE "^.*Home +$KNOWN_NAME" <(plain phase9)
check "phase9: Testville's sign carries the placeholder for a never-seen owner" grep -qF "\"${OWNER_UUID:0:15}\"" <(plain phase9)

# Phase 10 (#12): no usable owner UUID reads as no owner.
check "phase10: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase10.log"
check "phase10: no exception or stack trace" no_match "NullPointerException|Command exception: /?(postal|tlist|alist)|at .*com\.vodhanel\." "$WORK_DIR/phase10.log"
check "phase10: dispatcher started" grep -q "VA_Postal started" "$WORK_DIR/phase10.log"
check "phase10: /tlist lists the office and address as server-owned" grep -qE "Home[.]+ +server" <(plain phase10)
check "phase11: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase11.log"
check "phase11: no NullPointerException or Postal stack trace" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase11.log"
check "phase12: no NPC errors or Postal stack traces after quick restarts" \
    no_match "Exception creating NPC|NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase12.log"
check "phase12: a start while running is refused" grep -q "Dispatcher is already running" "$WORK_DIR/phase12.log"
check "phase13: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase13.log"
check "phase13: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase13.log"
check "phase13: the office suggests house names" grep -q "Suggestions for Testville: [A-Z]" <(plain phase13)
check "phase13: a deed opened Rose Cottage" grep -q "Welcome to Rose Cottage, Testville" <(plain phase13)
check "phase13: Rose Cottage has its mailbox sign" grep -qi "rose_cottage" <(plain phase13 | grep "has the following block data")
check "phase13: Rose Cottage has a surveyed route" python3 - "$CONFIG" <<'PY'
import sys
lines = open(sys.argv[1]).read().split("\n")
start = next(i for i, l in enumerate(lines) if l.strip().lower() == "rose_cottage:")
depth = len(lines[start]) - len(lines[start].lstrip())
block = []
for l in lines[start + 1:]:
    if l.strip() and len(l) - len(l.lstrip()) <= depth:
        break
    block.append(l.strip())
assert "Route:" in block and "'0':" in block, block
PY
check "phase13: a chest the postman can't reach is refused" grep -q "couldn't reach this chest" <(plain phase13)
check "phase13: no address is made for it" no_match "ivy_lodge" "$CONFIG"
check "phase13: a name the office has can't be deeded" grep -q "already has an address called Home" <(plain phase13)
check "phase14: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase14.log"
check "phase14: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase14.log"
check "phase14: Testville's clerk is on duty beside its chest" \
    grep -q "Testville: Postmaster of Testville, at 19 -60 0 (beside the office chest), on duty" <(plain phase14)
# In order: at its post; moved away; back at its post; at the place it was given; gone with the mail stopped.
check "phase14: the clerk stands at its post, goes back when moved, stands where placed, and leaves with the mail" \
    test "$(grep -oE "Test (passed|failed)|Teleported [0-9]+ entit" "$WORK_DIR/phase14.log" | cut -c1-11 | tr '\n' ' ')" \
    = "Test passed Teleported  Test passed Test passed Test failed "
check "phase14: the office sets its own address price" grep -q "now costs .*25" <(plain phase14)
check "phase14: the price shows as the office's" grep -q "costs .*25.* (the office's price)" <(plain phase14)
check "phase14: and listed off duty" grep -q "off duty (the mail isn't running)" <(plain phase14)
check "phase15: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase15.log"
check "phase15: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase15.log"
check "phase15: approval is off by default" grep -q "Testville are sold straight away (the server's default)" <(plain phase15)
check "phase15: the office turns it on" grep -q "Testville need the postmaster's approval (the office's choice)" <(plain phase15)
check "phase15: an application waits" grep -q "for Bramble Cottage, Testville by Server, waits for the postmaster" <(plain phase15)
check "phase15: a name under application is reserved" grep -q "No application: someone already holds a deed for Bramble Cottage" <(plain phase15)
check "phase15: the office lists both applications" grep -q "Applications at Testville: 2" <(plain phase15)
check "phase15: one is approved" grep -q "Approved Bramble Cottage for Server" <(plain phase15)
check "phase15: one is declined" grep -q "Declined Holly Lodge for Server" <(plain phase15)
check "phase15: then none wait" grep -q "Applications at Testville: 0" <(plain phase15)
check "phase15: the approved one is issued" grep -qE "Bramble Cottage, Testville issued" <(plain phase15)
check "phase15: the declined one is denied" grep -qE "Holly Lodge, Testville denied" <(plain phase15)
check "phase15: a declined name can be applied for again" \
    test "$(grep -c "for Holly Lodge, Testville by Server, waits for the postmaster" <(plain phase15))" -eq 2
check "phase15: approval back to the server's default" test "$(grep -c "are sold straight away (the server's default)" <(plain phase15))" -eq 2
check "phase16: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase16.log"
check "phase16: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase16.log"
check "phase16: the Postmaster General is on duty at Central" \
    grep -q "Central: Postmaster General, at -1 -60 0 (beside the office chest), on duty" <(plain phase16)
# In order: the clerk at his post; the chest and the sign survive the TNT; the planks beside them don't; the clerk
# is gone with the mail stopped.
check "phase16: TNT leaves Central's chest and sign, takes the planks; the clerk leaves with the mail" \
    test "$(grep -oE "Test (passed|failed)" "$WORK_DIR/phase16.log" | tr '\n' ' ')" \
    = "Test passed Test passed Test passed Test failed Test failed "
check "phase16: a hopper under Central's chest takes nothing" grep -qE -e "-61, 0 has the following block data: \[\]" <(plain phase16)
check "phase16: the chest keeps its paper" grep -qE "0, -60, 0 has the following block data: .*minecraft:paper" <(plain phase16)
check "phase17: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase17.log"
check "phase17: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase17.log"
check "phase17: charters need approval by default" grep -q "Charter applications wait for an admin's approval" <(plain phase17)
check "phase17: an application waits" grep -q "Charter application [0-9a-f]* for Brookfield by Server, waits for a decision" <(plain phase17)
check "phase17: its name is reserved" grep -q "No charter: someone already holds a charter for Brookfield" <(plain phase17)
check "phase17: an existing office's name is refused" grep -q "No charter: there's already a post office called Testville" <(plain phase17)
check "phase17: the application is listed" grep -q "Charter applications: 1" <(plain phase17)
check "phase17: and declined" grep -q "Declined the charter for Brookfield (Server)" <(plain phase17)
check "phase17: then none wait" grep -q "Charter applications: 0" <(plain phase17)
check "phase17: autoapprove sells charters straight away" grep -q "Charters are sold straight away" <(plain phase17)
check "phase17: a charter opens Brookfield" grep -q "The Brookfield Post Office is open" <(plain phase17)
check "phase17: the opening is announced" grep -q "A new post office has opened in Brookfield" <(plain phase17)
check "phase17: Brookfield has its sign" grep -qi "brookfield" <(plain phase17 | grep "80, -60, 41 has the following block data")
check "phase17: Brookfield is in the config, two blocks in front of its chest" grep -qiE "location: '?world,80\.0,-60\.0,42\.0" "$CONFIG"
# (/tlist lists only offices with addresses; a new office has none yet.)
check "phase17: Brookfield is one of the offices, with its clerk" grep -q "Brookfield: Postmaster of Brookfield" <(plain phase17)
check "phase17: a chest too near another office is refused" grep -qE "too near (Central|the Testville Post Office)" <(plain phase17)

check "phase18: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase18.log"
check "phase18: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase18.log"
check "phase18: the rate is set by policy" grep -q "rates.letter_out_town set to 9" <(plain phase18)
check "phase18: and a notice is promised" grep -q "Central's postman will take a notice of the new rate to each office" <(plain phase18)
check "phase18: a discount above the maximum is refused" grep -q "allows a discount of at most 30%" <(plain phase18)
check "phase18: Testville gives 20% off" grep -q "Testville's postage discount is now 20%" <(plain phase18)
check "phase18: and shows it" grep -q "Testville's postage discount: 20% (up to 30%)" <(plain phase18)
check "phase18: Central's postman leaves the notice at Testville, once" \
    test "$(grep -c "Central's postman left a rate notice at testville" <(plain phase18))" -eq 1
check "phase18: the notice is in Testville's chest" \
    grep -qE "20, -60, 0 has the following block data: .*Notice of postage rates" <(plain phase18)
check "phase18: the discount is cleared" grep -q "Testville's postage discount is now none" <(plain phase18)
check "phase18: the policy lists the rates" grep -q "rates.letter_out_town: 9 " <(plain phase18)
check "phase19: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase19.log"
check "phase19: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase19.log"
check "phase19: the roll-up writes rows" grep -qE "Stats updated: [1-9][0-9]* office-day" <(plain phase19)
check "phase19: the leaderboards list Testville as busiest" grep -qE "Busiest:" <(plain phase19)
check "phase19: Testville leads" grep -qE "1\. Testville [0-9]+ delivered" <(plain phase19)
check "phase19: Testville's numbers show its deliveries" grep -qE "Delivered: [1-9][0-9]*" <(plain phase19)
check "phase19: and the letter it lost" grep -qE "Lost: [1-9]" <(plain phase19)
check "phase19: an unknown office is refused" grep -q "No post office matches 'nowhere'" <(plain phase19)
check "phase20: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase20.log"
check "phase20: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase20.log"
check "phase20: a sign far from Central is refused" grep -q "The notice board goes at Central" <(plain phase20)
check "phase20: a block that isn't a sign is refused" grep -q "That isn't a sign" <(plain phase20)
check "phase20: the first sign takes the postage panel" grep -q "now shows postage" <(plain phase20)
check "phase20: the next takes the parcels panel" grep -q "now shows parcels" <(plain phase20)
check "phase20: six signs on the board" grep -q "Notice board: 6 sign(s)" <(plain phase20)
check "phase20: the postage sign shows the new local rate" \
    grep -qE -e "-5, -59, -3 has the following block data: .*POSTAGE.*Local +[^0-9 ]*5" <(plain phase20)
check "phase20: the busiest board shows Testville" grep -qE -e "-3, -59, -3 has the following block data: .*BUSIEST.*1 Testville" <(plain phase20)
check "phase20: the notices show the rate change" grep -qE -e "-3, -60, -3 has the following block data: .*NOTICES.*[0-9]+/[0-9]+ New rates" <(plain phase20)
check "phase20: the signs show colours, not codes" no_match "messages: \\[\"&1" <(plain phase20)
check "phase20: the signs are waxed" grep -qE -e "-3, -59, -3 has the following block data: 1b" <(plain phase20)
check "phase20: a sign is taken off the board" grep -q "Notice board: 5 sign(s)" <(plain phase20)
check "phase21: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase21.log"
check "phase21: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase21.log"
check "phase21: none after the restart" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase21b.log"
check "phase21: the network map hangs in the frame" grep -q "The network map hangs at -2 -59 -3" <(plain phase21)
check "phase21: the frame holds a filled map" grep -qE "has the following entity data: .*minecraft:filled_map" <(plain phase21)
check "phase21: a map for nobody is refused" grep -q "Give it to whom" <(plain phase21)
check "phase21: an unknown office is refused" grep -q "No post office matches 'nowhere'" <(plain phase21)
check "phase21: the map is listed" grep -qE "map [0-9]+: the network" <(plain phase21)
check "phase21: after a restart its renderer is back" grep -q "1 Postal map(s) ready" <(plain phase21b)
check "phase22: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase22.log"
check "phase22: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase22.log"
check "phase22: none named before the first award" grep -q "None named yet" <(plain phase22)
check "phase22: Testville leads the period" grep -q "Leading this period: Testville" <(plain phase22)
check "phase22: Testville is named Branch of the Month" grep -q "The Testville Post Office is the Branch of the Month" <(plain phase22)
check "phase22: a bonus Central can't spare is skipped" grep -q "Branch of the Month bonus skipped" <(plain phase22)
check "phase22: the board's award panel shows Testville" \
    grep -qE -e "-3, -60, -3 has the following block data: .*BRANCH OF.*THE MONTH.*Testville" <(plain phase22)
check "phase22: Testville is the holder" grep -q "Holder: Testville" <(plain phase22)
check "phase23: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase23.log"
check "phase23: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase23.log"
check "phase23: the letter for another server is handed in" grep -q "Test letter .* handed in at Testville for elsewhere:" <(plain phase23)
check "phase23: uncollected, it comes back" grep -q "goes back to main: elsewhere didn't collect it in time" <(plain phase23)
check "phase23: it's returned to its sender, RETURNED" grep -qE "home from main:testville: RETURNED|: RETURNED" <(plain phase23)
check "phase23: its history says why" grep -q "returned to sender: elsewhere didn't collect it in time" <(plain phase23)
check "phase23: it's in the sender's mailbox with a Returned to sender page" \
    grep -qE "40, -60, 0 has the following block data: .*Returned to sender" <(plain phase23)
check "phase24: Postal enabled" grep -q "Enabling Postal" "$WORK_DIR/phase24.log"
check "phase24: no Postal stack traces" no_match "NullPointerException|at .*com\.vodhanel\." "$WORK_DIR/phase24.log"
check "phase24: an earlier phase's hourly check sent an inspector by itself" \
    bash -c "cat '$WORK_DIR'/phase{3..23}.log 2>/dev/null | grep -q 'Central sends an inspector to testville'"
check "phase24: an inspector would find the lost letter now" grep -qE "letters? (was|were) lost" <(plain phase24)
check "phase24: an inspector is sent, with the next visit" grep -q "Central's inspector goes to the Testville Post Office" <(plain phase24)
check "phase24: an unknown office is refused" grep -q "No post office matches 'nowhere'" <(plain phase24)
check "phase24: the inspector leaves a report at Testville" grep -q "Central's inspector left a report at testville" <(plain phase24)
check "phase24: the report is in Testville's chest" \
    grep -qE "20, -60, 0 has the following block data: .*Inspection report" <(plain phase24)
check "phase24: it's kept" grep -qE "Last inspected .*: [0-9]+ finding" <(plain phase24)
check "phase24: the board shows it" \
    grep -qE -e "-2, -60, -3 has the following block data: .*INSPECTIONS.*Testville" <(plain phase24)
echo
echo "---- Postal output (phase 2) ----"
sed 's/\x1b\[[0-9;]*m//g' "$WORK_DIR/phase2.log" | grep -E "\[Postal\]|Exception|Caused by|WARN\]: +at " | grep -v "Nag author" | awk '{k=substr($0,16)} !seen[k]++' | head -150 || true

exit $fail
