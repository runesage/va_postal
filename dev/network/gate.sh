#!/usr/bin/env bash
# Checks The Gate on the running network (set up with dev/network/network.sh setup --with-gate): on each server
# it's enabled in Bungee mode under its Velocity name, keeps its gates in the shared MySQL database, and sees all
# three gates (one per server). Then prints how to dial between them in game, which only a player can do: The Gate
# dials from a lectern GUI and has no dial command.
#
# Usage: dev/network/gate.sh     (or: dev/network/network.sh gate)
# Env:   NET_DIR, NET_PORT (as for network.sh)
set -euo pipefail
[ "${BASH_VERSINFO[0]}" -ge 4 ] || { echo "error: needs bash 4 or newer (macOS: brew install bash)" >&2; exit 1; }

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
NET_DIR="${NET_DIR:-$REPO/dev-network}"
NET_PORT="${NET_PORT:-25630}"
ESC=$'\x1b'
declare -A RCON=([big]=$((NET_PORT + 4)) [small]=$((NET_PORT + 5)) [dims]=$((NET_PORT + 6)))
declare -A GATE_ADDRESS=([big]=1111111 [small]=2222222 [dims]=3333333)   # as in network.sh

[ -f "$NET_DIR/with-gate" ] || { echo "The Gate isn't installed: dev/network/network.sh setup --with-gate" >&2; exit 2; }

rcon() { # server command...
    python3 "$REPO/dev/rcon.py" 127.0.0.1 "${RCON[$1]}" "$(cat "$NET_DIR/rcon-password")" "${@:2}" >/dev/null
}
for s in big small dims; do
    rcon "$s" "list" 2>/dev/null || { echo "$s isn't reachable over RCON; start the network first" >&2; exit 2; }
done

log_of() { echo "$NET_DIR/$1/logs/latest.log"; }
lines() { wc -l < "$1" | tr -d ' '; }
since() { tail -n +"$2" "$(log_of "$1")" | sed "s/${ESC}\[[0-9;]*m//g"; }   # server from-line, colours stripped

fail=0
check() { local desc="$1"; shift; if "$@"; then echo "PASS: $desc"; else echo "FAIL: $desc"; fail=1; fi; }
logged() { since "$1" 1 | grep -aqE "$2"; }       # server regex: anywhere in this run's log
said() { echo "$3" | grep -aqE "$2"; }            # server regex output

for s in big small dims; do
    echo "== $s"
    check "The Gate enabled" logged "$s" "Enabling The_Gate"
    check "its database connected" logged "$s" "\[The_Gate\] Database status: Connected"
    from=$(( $(lines "$(log_of "$s")") + 1 ))
    rcon "$s" "thegate syncdb"
    sleep 3
    rcon "$s" "thegate"
    sleep 2
    out="$(since "$s" "$from")"
    check "Bungee mode on, as server $s" said "$s" "Using bungee: true" "$out"
    check "server name $s" said "$s" "Server name: $s\b" "$out"
    check "saving to MySQL" said "$s" "Used save format: MYSQL" "$out"
    count="$(echo "$out" | sed -nE 's/.*Gate count: ([0-9]+\/[0-9]+).*/\1/p' | tail -1)"
    check "all three gates known (gate count $count)" said "$s" "Gate count: 3/3" "$out"
    traces="$(since "$s" 1 | grep -acE "at thegate\." || true)"
    check "no The Gate stack traces ($traces lines)" test "$traces" -eq 0
done

echo
if [ "$fail" -eq 0 ]; then echo "The Gate check passed."; else echo "The Gate check FAILED (logs: $NET_DIR/*/logs/latest.log)."; fi
cat <<EOF

To travel by gate (in game; only a player can dial):
  1. Each server has a gate at -30 -60 30 in its Overworld, south-west of Central (/tp @s -30 -60 26).
     Addresses: big ${GATE_ADDRESS[big]}, small ${GATE_ADDRESS[small]}, dims ${GATE_ADDRESS[dims]}.
  2. /gatetools, take the Gate Crystal (quartz) and right-click a lectern placed near the gate: that's its DHD.
  3. Right-click the lectern, dial another server's address on the keypad, press the red button.
  4. When the horizon settles, walk through: the proxy moves you to that server, in front of its gate.
EOF
exit "$fail"
