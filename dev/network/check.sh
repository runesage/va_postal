#!/usr/bin/env bash
# Sends test letters across the running three-server network (dev/network/network.sh start) and waits for them:
# between every pair of servers, and between the Overworld, the Nether and the End on dims. Then reports each
# letter's state, and the Postal errors and stuck postmen in each server's log since the check began.
#
# Usage: dev/network/check.sh     (or: dev/network/network.sh check)
# Env:   NET_DIR, NET_PORT (as for network.sh), CHECK_MINUTES (how long to wait for delivery; default 12)
#
# Exits non-zero if a letter wasn't delivered or a server logged a Postal stack trace.
set -euo pipefail
[ "${BASH_VERSINFO[0]}" -ge 4 ] || { echo "error: needs bash 4 or newer (macOS: brew install bash)" >&2; exit 1; }

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
NET_DIR="${NET_DIR:-$REPO/dev-network}"
NET_PORT="${NET_PORT:-25630}"
CHECK_MINUTES="${CHECK_MINUTES:-12}"
ESC=$'\x1b'
declare -A RCON=([big]=$((NET_PORT + 4)) [small]=$((NET_PORT + 5)) [dims]=$((NET_PORT + 6)))

rcon() { # server command... (colour codes stripped)
    python3 "$REPO/dev/rcon.py" 127.0.0.1 "${RCON[$1]}" "$(cat "$NET_DIR/rcon-password")" "${@:2}" \
        | sed 's/§.//g'
}

for s in big small dims; do
    rcon "$s" "list" >/dev/null 2>&1 || { echo "$s isn't reachable over RCON; start the network first" >&2; exit 2; }
done

first() { # json-file: "Town Address" of the first town and its first address
    python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); t=next(iter(d)); print(t, d[t]["addresses"][0])' "$1"
}
read -r BIG_TOWN BIG_ADDR < <(first "$NET_DIR/big-towns.json")

# from-server  from-office  to  address  what it crosses
LETTERS=(
    "small|Hillcrest|big:$BIG_TOWN|$BIG_ADDR|small -> big"
    "big|$BIG_TOWN|dims:Cinderholm|Forge|big -> dims, into the Nether"
    "dims|Voidreach|small:Riverside|Doorstep|dims, from the End -> small"
    "small|Riverside|dims:Voidreach|Shulker|small -> dims, into the End"
    "dims|Brookfield|Voidreach|Outlook|Overworld -> End (over the void bridge)"
    "dims|Cinderholm|Brookfield|Mill|Nether -> Overworld"
    "dims|Voidreach|Cinderholm|Brimstone|End -> Nether (beyond the lava)"
)

# Log sizes now, so the report only looks at what happens during the check.
declare -A START_LINE
for s in big small dims; do
    START_LINE[$s]=$(wc -l < "$NET_DIR/$s/logs/latest.log")
done

echo "== Posting ${#LETTERS[@]} test letters"
declare -A ID DEST WHAT STATE
n=0
for spec in "${LETTERS[@]}"; do
    IFS='|' read -r from office to addr what <<< "$spec"
    reply="$(rcon "$from" "postal testletter $office $to $addr" 2>&1 || true)"
    id="$(grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' <<< "$reply" | head -1 || true)"
    if [ -z "$id" ]; then
        echo "  FAILED to post ($what): $(tr '\n' ' ' <<< "$reply")"
        continue
    fi
    dest="$from"
    [[ "$to" == *:* ]] && dest="${to%%:*}"
    ID[$n]="$id"; DEST[$n]="$dest"; WHAT[$n]="$what"; STATE[$n]="posted"
    echo "  $id  $what"
    n=$((n + 1))
done

state_of() { # server id: the letter's state, from /postal track on that server
    { rcon "$1" "postal track $2" 2>/dev/null || true; } | sed -nE 's/.* from [^ ]+: ([A-Z_]+).*/\1/p' | head -1 || true
}

echo "== Waiting up to $CHECK_MINUTES minutes for delivery"
deadline=$(( $(date +%s) + CHECK_MINUTES * 60 ))
while :; do
    pending=0
    for ((i = 0; i < n; i++)); do
        [ "${STATE[$i]}" = DELIVERED ] && continue
        st="$(state_of "${DEST[$i]}" "${ID[$i]}")"
        [ -n "$st" ] && STATE[$i]="$st"
        [ "${STATE[$i]}" = DELIVERED ] || pending=$((pending + 1))
    done
    [ "$pending" -eq 0 ] && break
    [ "$(date +%s)" -ge "$deadline" ] && break
    echo "  $(date +%H:%M:%S) $pending still on the way"
    sleep 20
done

echo "== Results"
fail=0
for ((i = 0; i < n; i++)); do
    mark=OK; [ "${STATE[$i]}" = DELIVERED ] || { mark=FAIL; fail=1; }
    printf '  %-4s %-11s %s  (%s)\n' "$mark" "${STATE[$i]}" "${WHAT[$i]}" "${ID[$i]}"
done
[ "$n" -eq "${#LETTERS[@]}" ] || fail=1

for s in big small dims; do
    log="$(tail -n +"$((START_LINE[$s] + 1))" "$NET_DIR/$s/logs/latest.log" | sed "s/${ESC}\[[0-9;]*m//g")"
    traces=$(grep -cE "at .*com\.vodhanel\." <<< "$log" || true)
    rescues=$(grep -cE "Teleport Reset|Soft Reset" <<< "$log" || true)
    echo "  $s: $traces Postal stack trace lines, $rescues stuck-postman resets"
    if [ "$traces" -gt 0 ]; then
        fail=1
        grep -B2 -m1 -E "Exception|Error" <<< "$log" | sed 's/^/      /' | head -6
    fi
done
[ "$fail" -eq 0 ] && echo "Network check passed." || echo "Network check FAILED (logs in $NET_DIR/<server>/logs/latest.log)."
exit "$fail"
