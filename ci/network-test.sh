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
                    "Vehicle": "the test ship"}}
out, block = [], None
for line in open(p).read().split("\n"):
    if line and not line.startswith(" "):
        block = line.split(":")[0]
    key = line.strip().split(":")[0]
    if block in want and key in want[block]:
        line = line[:len(line) - len(line.lstrip())] + key + ": '" + want[block].pop(key) + "'"
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

delivered() { # server-id: its Home mailbox has a written book
    say "$1" "data get block 40 -60 0 Items"
    tail -n 5 "$WORK_DIR/$1.log" | grep -q "written_book"
}
for _ in $(seq 1 60); do
    if delivered beta && delivered alpha; then break; fi
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
say alpha "postal store"
say beta "postal whois nobody_here"
say alpha "postal network"
sleep 2
for s in alpha beta; do stop "$s"; done

# ---- Assertions ---------------------------------------------------------------------------
fail=0
check() { # description, command...
    local desc="$1"; shift
    if "$@"; then echo "PASS: $desc"; else echo "FAIL: $desc"; fail=1; fi
}
no_match() { ! grep -qE "$1" "$2"; }
books_in_mailbox() { # log: written books in the last 'data get block 40 -60 0' answer
    grep -E "40, -60, 0 has the following block data" "$1" | tail -1 | grep -o "written_book" | wc -l
}
for s in alpha beta; do
    log="$WORK_DIR/$s.log"
    check "$s: mail store open on schema 5 as '$s'" grep -q "schema 5, server id '$s'" "$log"
    check "$s: no Postal stack traces" no_match "at .*com\.vodhanel\." "$log"
    check "$s: no command exceptions" no_match "Command exception: /?postal" "$log"
    check "$s: no duplicate server id" no_match "Another server is using Network.Server_id" "$log"
    check "$s: exactly one letter delivered to its Home" test "$(books_in_mailbox "$log")" = 1
done
check "alpha: letter recorded for beta" test -n "$A_ID"
check "beta: letter recorded for alpha" test -n "$B_ID"
check "alpha sees beta's offices in the directory" grep -q "beta / testville: 1 addresses" "$WORK_DIR/alpha.log"
check "alpha's letter: delivered, one record" grep -qE "To .*beta:testville, home.*DELIVERED" "$WORK_DIR/alpha.log"
check "alpha's letter: handed to the network on alpha" grep -qE "IN_NETWORK by central.* on alpha" "$WORK_DIR/alpha.log"
check "alpha's letter: claimed and delivered on beta" grep -qE "DELIVERED by postman testville on beta" "$WORK_DIR/beta.log"
check "beta's letter: delivered on alpha" grep -qE "To .*alpha:testville, home.*DELIVERED" "$WORK_DIR/beta.log"
check "alpha: the ship departed for beta" grep -q "The test ship departs for beta with 1 letter" "$WORK_DIR/alpha.log"
check "beta: the ship from alpha arrived" grep -q "The test ship from alpha has arrived with 1 letter" "$WORK_DIR/beta.log"
check "alpha: /postal network shows the schedule" grep -q "The test ship leaves every 1 min and takes 30s" "$WORK_DIR/alpha.log"
check "beta: unknown player reported" grep -q "No player called nobody_here" "$WORK_DIR/beta.log"

if [ "$fail" -ne 0 ]; then
    echo "Network test FAILED. Logs: $WORK_DIR/alpha.log, $WORK_DIR/beta.log"
    exit 1
fi
echo "Network test passed."
