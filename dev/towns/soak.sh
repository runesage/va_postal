#!/usr/bin/env bash
# Route soak on the "towns" test world: builds the test towns (dev/towns/build_towns.py) on a
# throwaway Paper server, lets the postmen run every route for a while, then reports, per address,
# whether a postman completed the round trip and how long it took.
#
# Usage: dev/towns/soak.sh [postal.jar]     (default: the newest target/va_postal-*.jar)
# Env:   JAVA (Java 25+; default: java), WORK_DIR (default: ./towns-soak),
#        SOAK_SECONDS (how long the postmen run; default 900),
#        SURVEY=1 (throw the hand-made routes away and let Postal survey every route: /postal survey)
#
# Exits non-zero if an address expected to be reachable was never reached (or the reverse).
# Running this accepts the Minecraft EULA for a throwaway test server.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="${1:-$(ls -t "$REPO"/target/va_postal-*.jar 2>/dev/null | head -1)}"
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "no Postal jar; build first (./mvnw package)" >&2; exit 2; }
JAR="$(realpath "$JAR")"
JAVA="${JAVA:-java}"
WORK_DIR="${WORK_DIR:-towns-soak}"
SOAK_SECONDS="${SOAK_SECONDS:-900}"
SURVEY="${SURVEY:-0}"
# shellcheck source=ci/lib.sh
source "$REPO/ci/lib.sh"

mkdir -p "$WORK_DIR/cache"
WORK_DIR="$(realpath "$WORK_DIR")"
CACHE="$WORK_DIR/cache"
SERVER="$WORK_DIR/server"
CONFIG="$SERVER/plugins/Postal/config.yml"
ROUTES="$WORK_DIR/routes.json"

download_server_jars "$CACHE"
rm -rf "$SERVER"
install_server "$CACHE" "$SERVER"
cp "$JAR" "$SERVER/plugins/Postal.jar"
echo "eula=true" > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<'EOF'
online-mode=false
level-type=minecraft\:flat
generate-structures=false
view-distance=6
simulation-distance=6
spawn-protection=0
max-players=1
EOF
mkdir -p "$SERVER/world/datapacks"
python3 "$REPO/dev/towns/build_towns.py" --datapack "$SERVER/world/datapacks/postal_towns" --routes "$ROUTES"

# run_server <log> <timeout> <command>...: boots the server, feeds console commands once it's up, stops it.
run_server() {
    local log="$1" limit="$2"; shift 2
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
    ) | (cd "$SERVER" && timeout "$limit" "$JAVA" -Xms1G -Xmx2G -jar paper.jar nogui) > "$log" 2>&1 || true
}

echo "== Building the towns"
run_server "$WORK_DIR/build.log" 600 "function postal_towns:build" "sleep:10" "save-all flush" "sleep:5"
keep_libraries "$CACHE" "$SERVER"
grep -q "Postal test towns built" "$WORK_DIR/build.log" || { echo "the towns weren't built; see $WORK_DIR/build.log" >&2; exit 1; }

# The towns' offices, addresses and routes, plus quick postman pacing so every route runs several times.
mapfile -t TOWNS < <(python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))))' "$ROUTES")
ALIST_CMDS=()
for town in "${TOWNS[@]}"; do ALIST_CMDS+=("alist $town"); done
if [ "$SURVEY" = 1 ]; then
    python3 "$REPO/dev/towns/build_towns.py" --config "$CONFIG" --no-routes
    SURVEY_CMDS=()
    for town in "${TOWNS[@]}"; do SURVEY_CMDS+=("postal survey $town"); done
    SURVEY_CMDS+=("sleep:45")
else
    python3 "$REPO/dev/towns/build_towns.py" --config "$CONFIG"
    SURVEY_CMDS=()
fi
python3 - "$CONFIG" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path).read()
text = re.sub(r"(?m)^(  Debug: )'false'", r"\1'true'", text)
# Report_nav_probs: log every time a stuck postman is teleported on, and for which address.
for key, value in {"Report_nav_probs": "true", "Postman_cool_sec": "10", "Central_cool_sec": "10", "Residence_cool_ticks": "40",
                   "Heart_beat_ticks": "40", "Heart_beat_auto": "false"}.items():
    line = "  %s: '%s'" % (key, value)
    text, n = re.subn(r"(?m)^  %s: .*$" % key, line, text)
    if n == 0:
        text = re.sub(r"(?m)^Settings:$", "Settings:\n" + line, text, count=1)
open(path, "w").write(text)
PY

echo "== Running the postmen for ${SOAK_SECONDS}s"
run_server "$WORK_DIR/soak.log" "$((SOAK_SECONDS + 600))" \
    "${SURVEY_CMDS[@]}" "postal start" "sleep:$SOAK_SECONDS" "${ALIST_CMDS[@]}" \
    "postal stop" "sleep:5"

if [ "$SURVEY" = 1 ]; then
    echo "== Surveys"
    sed $'s/\x1b\\[[0-9;]*m//g' "$WORK_DIR/soak.log" | grep -aoE "Route [A-Za-z]+, [A-Za-z]+: .*" | sort -u || true
fi
echo "== Results"
python3 - "$ROUTES" "$WORK_DIR/soak.log" <<'PY'
import json, re, sys
from collections import Counter, defaultdict
routes = json.load(open(sys.argv[1]))
log = re.sub(r"\x1b\[[0-9;]*m", "", open(sys.argv[2], errors="replace").read())
seconds = {}
for m in re.finditer(r"(?m)^\[[^\]]*\]: (?:\[Postal\] \[STDOUT\] )?\s+(\S+)\s+\S+\s+Seconds: (\d+)", log):
    seconds[m.group(1).lower()] = int(m.group(2))
# A rescue: the postman got stuck and Postal teleported it on (or reset it at a door).
rescues = Counter()
where = defaultdict(Counter)
for m in re.finditer(r"(Teleport Reset|Soft Reset).*?While servicing\s*:\s*(\S+).*?Waypoint sequence:\s*(\d+)", log, re.S):
    rescues[m.group(2).lower()] += 1
    where[m.group(2).lower()][int(m.group(3))] += 1
bad = 0
print("%-10s %-10s %-9s %-8s %-8s %-9s %s" % ("Town", "Address", "Expected", "Seconds", "Rescues", "Result", "Route"))
for town, addrs in routes.items():
    for addr, info in addrs.items():
        key = addr.lower()
        secs, n = seconds.get(key), rescues.get(key, 0)
        result = "never" if not secs else ("rescued" if n else "walked")
        ok = (result == "walked") == info["reachable"]
        bad += not ok
        note = ""
        if n:
            note = "  (stuck at waypoint %s)" % ", ".join("%d x%d" % (wp, c) for wp, c in sorted(where[key].items()))
        print("%-10s %-10s %-9s %-8s %-8s %-9s %s%s%s" % (
            town, addr, "walk" if info["reachable"] else "fail", "-" if secs is None else secs, n, result,
            info["what"], note, "" if ok else "   <-- UNEXPECTED"))
print("\nwalked: completed with no rescues; rescued: completed only because a stuck postman was teleported on;")
print("never: no round trip completed.")
print("All addresses behaved as expected." if not bad else "%d address(es) did not behave as expected." % bad)
sys.exit(1 if bad else 0)
PY
