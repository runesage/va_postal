#!/usr/bin/env bash
# Two Paper servers sharing one MySQL/MariaDB mail store, as behind a Velocity proxy (persistent-state phase P4,
# docs/design/persistent-state.md §17). Both are seeded with the same small network (Testville with Home), so
# each server's office has a namesake on the other. Each posts a letter to the other's Testville/Home at the same
# time, and the test asserts that:
#   - each letter is delivered once, on the other server, and not to the sender's own Testville/Home;
#   - it's one record that moved: both servers' history is on it, and it's DELIVERED;
#   - the directory and the network's players are shared.
#
# Usage: ci/network-test.sh <path-to-postal.jar>
# Env:   JAVA (default: java), WORK_DIR (default: ./network), MYSQL_HOST (127.0.0.1), MYSQL_PORT (3306),
#        MYSQL_DATABASE (postal), MYSQL_USER (postal), MYSQL_PASSWORD (postal). The database must be empty.
#
# Running this accepts the Minecraft EULA for throwaway test servers.
set -euo pipefail

POSTAL_JAR="$(realpath "${1:?usage: network-test.sh <postal.jar>}")"
JAVA="${JAVA:-java}"
WORK_DIR="${WORK_DIR:-network}"
SEED_SIZE=small
# shellcheck source=ci/lib.sh
source "$(dirname "$0")/lib.sh"

mkdir -p "$WORK_DIR/cache"
WORK_DIR="$(realpath "$WORK_DIR")"
CACHE="$WORK_DIR/cache"
download_server_jars "$CACHE"

declare -A PORT=([alpha]=25565 [beta]=25566)
declare -A PID=()

dir() { echo "$WORK_DIR/$1"; }

setup() { # server-id
    local d; d="$(dir "$1")"
    rm -rf "$d"
    install_server "$CACHE" "$d"
    cp "$POSTAL_JAR" "$d/plugins/Postal.jar"
    # Libraries Paper and Citizens downloaded on an earlier run (Citizens' own downloads can fail).
    [ -d "$CACHE/libraries" ] && cp -r "$CACHE/libraries" "$d/"
    if [ -d "$CACHE/citizens-lib" ]; then mkdir -p "$d/plugins/Citizens" && cp -r "$CACHE/citizens-lib" "$d/plugins/Citizens/lib"; fi
    echo "eula=true" > "$d/eula.txt"
    cat > "$d/server.properties" <<EOF
online-mode=false
server-port=${PORT[$1]}
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
max-players=1
EOF
}

start() { # server-id log: boots in the background, console on fd (4 for alpha, 5 for beta)
    local d fifo; d="$(dir "$1")"; fifo="$WORK_DIR/$1.fifo"
    rm -f "$d/logs/latest.log" "$fifo"
    mkfifo "$fifo"
    (cd "$d" && exec "$JAVA" -Xms1G -Xmx2G -jar paper.jar nogui < "$fifo") > "$2" 2>&1 &
    PID[$1]=$!
    if [ "$1" = alpha ]; then exec 4>"$fifo"; else exec 5>"$fifo"; fi
}

wait_up() { # server-id
    for _ in $(seq 1 300); do
        grep -q 'Done (' "$(dir "$1")/logs/latest.log" 2>/dev/null && return 0
        sleep 1
    done
    echo "$1 didn't start"; return 1
}

say() { # server-id command
    if [ "$1" = alpha ]; then echo "$2" >&4; else echo "$2" >&5; fi
    sleep 2
}

stop() { # server-id
    say "$1" stop || true
    for _ in $(seq 1 60); do kill -0 "${PID[$1]}" 2>/dev/null || break; sleep 1; done
    kill -9 "${PID[$1]}" 2>/dev/null || true
    wait "${PID[$1]}" 2>/dev/null || true
    if [ "$1" = alpha ]; then exec 4>&-; else exec 5>&-; fi
    rm -f "$WORK_DIR/$1.fifo"
}

configure() { # server-id: the seed network, MySQL, and this server's id
    local config; config="$(dir "$1")/plugins/Postal/config.yml"
    seed_config "$config"
    python3 - "$config" "$1" "${MYSQL_HOST:-127.0.0.1}" "${MYSQL_PORT:-3306}" "${MYSQL_DATABASE:-postal}" \
        "${MYSQL_USER:-postal}" "${MYSQL_PASSWORD:-postal}" <<'PY'
import sys
p, server, host, port, db, user, password = sys.argv[1:]
want = {"Storage": {"Type": "mysql", "Host": host, "Port": port, "Database": db, "User": user, "Password": password},
        "Network": {"Server_id": server, "Poll_seconds": "3", "Departure_minutes": "1", "Transit_minutes": "0.5",
                    "Vehicle": "the test ship", "Always": "true"}}
out, block = [], None
for line in open(p).read().split("\n"):
    if line and not line.startswith(" "):
        block = line.split(":")[0]
    key = line.strip().split(":")[0]
    if block in want and key in want[block]:
        value = want[block].pop(key)
        # Numbers and booleans unquoted: Bukkit's getInt/getDouble/getBoolean ignore a quoted '3'.
        plain = value in ("true", "false") or value.replace(".", "", 1).isdigit()
        line = line[:len(line) - len(line.lstrip())] + key + ": " + (value if plain else "'" + value + "'")
    out.append(line)
missing = {b: k for b, k in want.items() if k}
assert not missing, "keys not found in config: %s" % missing
open(p, "w").write("\n".join(out))
PY
}

citizens_up() { # server-id: Citizens loaded its libraries (it downloads them on first start, and that can fail)
    ! grep -q "Error occurred while enabling Citizens" "$WORK_DIR/$1-phase1.log"
}

echo "== Phase 1: both servers generate their config and world"
for s in alpha beta; do setup "$s"; done
# One server downloads the libraries Paper and Citizens load at startup; the other gets a copy, so two
# simultaneous downloads can't fail one of them.
start alpha "$WORK_DIR/alpha-phase1.log"
wait_up alpha
citizens_up alpha || { echo "Citizens couldn't load its libraries on alpha (a download failed); re-run."; exit 1; }
rm -rf "$CACHE/libraries" "$CACHE/citizens-lib"
cp -r "$(dir alpha)/libraries" "$CACHE/libraries"
cp -r "$(dir alpha)/plugins/Citizens/lib" "$CACHE/citizens-lib" 2>/dev/null || true
cp -r "$(dir alpha)/libraries" "$(dir beta)/"
mkdir -p "$(dir beta)/plugins/Citizens"
cp -r "$(dir alpha)/plugins/Citizens/lib" "$(dir beta)/plugins/Citizens/" 2>/dev/null || true
start beta "$WORK_DIR/beta-phase1.log"
wait_up beta
citizens_up beta || { echo "Citizens couldn't load its libraries on beta; re-run."; exit 1; }
for s in alpha beta; do
    for cmd in "${SEED_COMMANDS[@]}"; do say "$s" "$cmd"; done
    say "$s" "save-all flush"
done
for s in alpha beta; do stop "$s"; done
for s in alpha beta; do configure "$s"; done

echo "== Phase 2: each server posts a letter to the other's Testville/Home"
for s in alpha beta; do start "$s" "$WORK_DIR/$s.log"; done
for s in alpha beta; do wait_up "$s"; done
sleep 8  # both directories published (a few seconds after start)
say alpha "postal directory"
say alpha "postal start"
say beta "postal start"
sleep 5
say alpha "postal testletter testville beta:testville home"
say beta "postal testletter testville alpha:testville home"
id_of() { grep -oE "Test letter [0-9a-f-]{36} handed in" "$WORK_DIR/$1.log" | head -1 | awk '{print $3}'; }
A_ID="$(id_of alpha)"; B_ID="$(id_of beta)"
echo "alpha's letter: ${A_ID:-none}, beta's letter: ${B_ID:-none}"

has_letter() { # server-id mail-id: that letter (by its mail id) is in the server's Home mailbox now
    say "$1" "data get block 40 -60 0 Items"
    tail -n 5 "$WORK_DIR/$1.log" | grep -q "$2"
}
# Departures every minute, 30 s at sea, then Central and the postman: a few minutes in all.
for _ in $(seq 1 60); do
    if has_letter beta "$A_ID" && has_letter alpha "$B_ID"; then break; fi
    sleep 8
done
for s in alpha beta; do
    say "$s" "postal stop"
    sleep 3
    say "$s" "data get block 40 -60 0 Items"
    say "$s" "data get block 0 -60 0 Items"
done
say alpha "postal track $A_ID"
say beta "postal track $A_ID"
say beta "postal track $B_ID"
say alpha "postal track $B_ID"
say alpha "postal store"
say beta "postal whois nobody_here"
say alpha "postal network"
sleep 2
for s in alpha beta; do stop "$s"; done

# ---- Assertions ---------------------------------------------------------------------------
# Compare without the console's colour codes.
for s in alpha beta; do sed 's/\x1b\[[0-9;]*m//g' "$WORK_DIR/$s.log" > "$WORK_DIR/$s.txt"; done
fail=0
check() { # description, command...
    local desc="$1"; shift
    if "$@"; then echo "PASS: $desc"; else echo "FAIL: $desc"; fail=1; fi
}
no_match() { ! grep -qE "$1" "$2"; }
mailbox() { # server: the last answer to 'data get block 40 -60 0' (its Home mailbox)
    grep -a "40, -60, 0 has the following block data" "$WORK_DIR/$1.txt" | tail -1
}
in_mailbox() { mailbox "$1" | grep -q "$2"; }      # server mail-id
not_in_mailbox() { ! in_mailbox "$1" "$2"; }
A="$WORK_DIR/alpha.txt"; B="$WORK_DIR/beta.txt"
for s in alpha beta; do
    log="$WORK_DIR/$s.txt"
    check "$s: mail store open on schema 5 as '$s'" grep -q "schema 5, server id '$s'" "$log"
    check "$s: no Postal stack traces" no_match "at .*com\.vodhanel\." "$log"
    check "$s: no command exceptions" no_match "Command exception: /?postal" "$log"
    check "$s: no duplicate server id" no_match "Another server is using Network.Server_id" "$log"
done
check "alpha: letter recorded for beta" test -n "$A_ID"
check "beta: letter recorded for alpha" test -n "$B_ID"
check "beta's Home got alpha's letter" in_mailbox beta "$A_ID"
check "alpha's Home got beta's letter" in_mailbox alpha "$B_ID"
check "alpha's own Home did not get alpha's letter" not_in_mailbox alpha "$A_ID"
check "alpha sees beta's offices in the directory" grep -q "beta / testville: 1 addresses" "$A"
check "alpha's letter: delivered, one record" grep -qE "To beta:testville, home from alpha:testville: DELIVERED" "$B"
check "alpha's letter: handed to the network on alpha" grep -qE "AT_CENTRAL -> IN_NETWORK by CENTRAL on alpha \(left on the test ship\)" "$A"
check "alpha's letter: claimed and delivered on beta" grep -qE -e "-> DELIVERED by POSTMAN testville on beta" "$B"
check "beta's letter: delivered on alpha" grep -qE "To alpha:testville, home from beta:testville: DELIVERED" "$A"
check "alpha: the ship departed for beta" grep -q "The test ship departs for beta with 1 letter" "$A"
check "beta: the ship from alpha arrived" grep -q "The test ship from alpha has arrived with 1 letter" "$B"
check "alpha: the purser carried the mail out" grep -q "Purser at Central: All aboard for beta! 1 letter for the voyage." "$A"
check "beta: the purser brought the mail in" grep -q "Purser at Central: Mail from alpha! 1 letter off the ship." "$B"
check "alpha: /postal network shows the schedule" grep -q "The test ship leaves every 1 min and takes 30s" "$A"
check "beta: unknown player reported" grep -q "No player called nobody_here" "$B"

if [ "$fail" -ne 0 ]; then
    echo "Network test FAILED. Logs: $WORK_DIR/alpha.log, $WORK_DIR/beta.log"
    exit 1
fi
echo "Network test passed."
