#!/usr/bin/env bash
# A headless player (ci/bot) on the running network, through the Velocity proxy: it joins on small, writes and
# addresses a letter to dims (Cinderholm, in the Nether), posts it at Riverside's office, then moves to dims with
# /server and to big, checking along the way that each server sees where the player is and that the letter
# arrives. This is what the console-only check can't do: one player, moving between servers.
#
# Usage: dev/network/player.sh     (or: dev/network/network.sh player)
# Needs: the network set up with --offline (the bot has no Mojang account) and running; Maven (builds the bot).
# Env:   NET_DIR, NET_PORT (as for network.sh), BOT_JAVA (Java 21+ for the bot; default: java),
#        CHECK_MINUTES (how long to wait for delivery; default 12)
set -euo pipefail
[ "${BASH_VERSINFO[0]}" -ge 4 ] || { echo "error: needs bash 4 or newer (macOS: brew install bash)" >&2; exit 1; }

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
NET_DIR="${NET_DIR:-$REPO/dev-network}"
NET_PORT="${NET_PORT:-25630}"
BOT_JAVA="${BOT_JAVA:-java}"
CHECK_MINUTES="${CHECK_MINUTES:-12}"
BOT_NAME=PostalBot
declare -A RCON=([big]=$((NET_PORT + 4)) [small]=$((NET_PORT + 5)) [dims]=$((NET_PORT + 6)))

[ "$(cat "$NET_DIR/online-mode" 2>/dev/null)" = false ] \
    || { echo "the bot needs an offline-mode network: dev/network/network.sh setup --offline" >&2; exit 2; }

rcon() { # server command...
    python3 "$REPO/dev/rcon.py" 127.0.0.1 "${RCON[$1]}" "$(cat "$NET_DIR/rcon-password")" "${@:2}" | sed 's/§.//g'
}
for s in big small dims; do
    rcon "$s" "list" >/dev/null 2>&1 || { echo "$s isn't reachable over RCON; start the network first" >&2; exit 2; }
done

BOT_DIR="$REPO/ci/bot"
if [ ! -d "$BOT_DIR/target/lib" ] || [ -n "$(find "$BOT_DIR/src" "$BOT_DIR/pom.xml" -newer "$BOT_DIR/target/lib" 2>/dev/null)" ]; then
    echo "== Building the bot"
    (cd "$BOT_DIR" && mvn -q -B package)
fi
BOT_CP="$BOT_DIR/target/classes:$BOT_DIR/target/lib/*"

WORK="$NET_DIR/player"
mkdir -p "$WORK"
BOT_LOG="$WORK/bot.log"
BOT_IN="$WORK/bot.fifo"
BOT_PID=""
cleanup() { # only our own bot
    [ -n "$BOT_PID" ] && kill "$BOT_PID" 2>/dev/null || true
    rm -f "$BOT_IN"
}
trap cleanup EXIT
rm -f "$BOT_IN"; mkfifo "$BOT_IN"; : > "$BOT_LOG"
"$BOT_JAVA" -cp "$BOT_CP" ci.bot.Bot 127.0.0.1 "$NET_PORT" "$BOT_NAME" < "$BOT_IN" > "$BOT_LOG" 2>"$WORK/bot.err" &
BOT_PID=$!
exec 4>"$BOT_IN"

lines() { wc -l < "$1" | tr -d ' '; }
wait_log() { # file regex seconds [from-line]
    local i
    for ((i = 0; i < $3 * 2; i++)); do
        tail -n +"${4:-1}" "$1" 2>/dev/null | grep -qE "$2" && return 0
        sleep 0.5
    done
    return 1
}
since() { tail -n +"$1" "$BOT_LOG"; }
bot() { echo "$*" >&4 2>/dev/null || { echo "FAIL: the bot has gone (see $BOT_LOG)"; exit 1; }; }
cmd() { # instruction regex: sends it and waits for chat matching regex
    local from; from=$(( $(lines "$BOT_LOG") + 1 )); bot "$1"; wait_log "$BOT_LOG" "$2" 20 "$from"
}
fail=0
check() { local desc="$1"; shift; if "$@"; then echo "PASS: $desc"; else echo "FAIL: $desc"; fail=1; fi; }
on_server() { rcon "$1" "list" | grep "$BOT_NAME" >/dev/null; }               # server
off_server() { ! on_server "$1"; }
seen_on() { rcon "$1" "postal whois $BOT_NAME" | grep -iE "online on $2" >/dev/null; }   # asking-server where
last_join() { grep -n ' JOINED' "$BOT_LOG" | tail -1 | cut -d: -f1; }
switch() { # server: /server it, and wait for the proxy's new login
    local last; last=$(last_join); bot "/server $1"; wait_log "$BOT_LOG" ' JOINED' 30 "$((last + 1))"
}
UUID_RE='[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'

echo "== $BOT_NAME joins the proxy at port $NET_PORT"
if wait_log "$BOT_LOG" ' JOINED' 60; then echo "PASS: the bot joined through the proxy"; else
    echo "FAIL: the bot couldn't join through the proxy"; cat "$BOT_LOG" "$WORK/bot.err"; exit 1; fi
sleep 3
check "it landed on small" on_server small
for s in big small dims; do rcon "$s" "op $BOT_NAME" >/dev/null; done
sleep 2
check "dims sees it online on small" seen_on dims small

echo "== On small: a letter for dims, Cinderholm (the Nether), posted at Riverside"
rcon small "eco give $BOT_NAME 10000" "minecraft:tp $BOT_NAME 0 -59 88" \
    "minecraft:give $BOT_NAME written_book[written_book_content={title:\"Down below\",author:\"$BOT_NAME\",pages:[\"Hello Cinderholm, from small\"]}]" >/dev/null
sleep 2
check "/addr dims:Cinderholm Ember asks for confirmation" cmd "/addr dims:cinderholm ember" "CHAT .*Enter '/' to confirm"
check "confirming addresses the letter" cmd "/" 'CHAT .*'
sleep 2
from=$(( $(lines "$BOT_LOG") + 1 ))
cmd "/postal track recent" 'CHAT \[Postal\] Recent mail' || true
sleep 1
ID="$(since "$from" | grep -iE "CHAT $UUID_RE cinderholm, ember: POSTED" | head -1 | grep -oE "$UUID_RE" || true)"
check "the letter is tracked as POSTED for dims ($ID)" test -n "$ID"
# Posting: the console copies the letter from the bot's hand into Riverside's office chest, then empties the hand.
rcon small "minecraft:item replace block 0 -60 85 container.0 from entity $BOT_NAME weapon.mainhand" \
    "minecraft:item replace entity $BOT_NAME weapon.mainhand with minecraft:air" >/dev/null

echo "== /server dims"
check "the proxy moved it to dims" switch dims
sleep 4
check "dims lists it" on_server dims
check "small no longer does" off_server small
check "big sees it online on dims" seen_on big dims

echo "== Waiting up to $CHECK_MINUTES minutes for the letter (small -> ship -> dims Central -> Cinderholm)"
state=""
deadline=$(( $(date +%s) + CHECK_MINUTES * 60 ))
while [ -n "$ID" ] && [ "$(date +%s)" -lt "$deadline" ]; do
    from=$(( $(lines "$BOT_LOG") + 1 ))
    cmd "/postal track $ID" "CHAT .* from .*: " >/dev/null || true
    state="$(since "$from" | sed -nE 's/.* from [^ ]+: ([A-Z_]+).*/\1/p' | head -1 || true)"
    [ "$state" = DELIVERED ] && break
    echo "  $(date +%H:%M:%S) ${state:-unknown}"
    sleep 20
done
check "the letter was DELIVERED (seen by the player on dims)" test "$state" = DELIVERED
# RCON shortens item data, so look at the last page of each book in Ember's mailbox (the first is its log).
in_ember() {
    local slot
    for slot in $(seq 0 26); do
        rcon dims "execute in minecraft:the_nether run data get block 20 64 3 Items[$slot].components.\"minecraft:written_book_content\".pages[-1]" \
            | grep "Hello Cinderholm, from small" >/dev/null && return 0
    done
    return 1
}
check "it's in Ember's mailbox in the Nether, with its text" in_ember

echo "== /server big"
check "the proxy moved it to big" switch big
sleep 4
from=$(( $(lines "$BOT_LOG") + 1 ))
cmd "/postal track ${ID:-none}" "CHAT .* from .*: " || true
sleep 1
check "big's /postal track shows the same letter DELIVERED" bash -c "tail -n +$from '$BOT_LOG' | grep -qE ': DELIVERED'"
check "its history: handed to the network on small" bash -c "tail -n +$from '$BOT_LOG' | grep -qE 'IN_NETWORK by CENTRAL on small'"
check "its history: delivered on dims" bash -c "tail -n +$from '$BOT_LOG' | grep -qiE 'DELIVERED by POSTMAN .* on dims'"

bot quit
sleep 1
[ "$fail" -eq 0 ] && echo "Player check passed." || echo "Player check FAILED (bot log: $BOT_LOG)."
exit "$fail"
