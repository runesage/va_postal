#!/usr/bin/env bash
# Load soak on the "smp" world (dev/towns/build_smp.py): many towns and hundreds of addresses spread over a
# large map, with no routes. Postal surveys every route, then all the post offices run at once while the
# server's TPS and tick times are sampled. Reports:
#   - surveys: how many found a route, the failures, the slowest, the most positions searched;
#   - the server: TPS (1 minute) and tick times through the run;
#   - the postmen: how many addresses got a completed round trip, and the rescues (stuck postmen teleported on).
#
# Usage: dev/towns/load.sh [postal.jar]     (default: the newest target/va_postal-*.jar)
# Env:   JAVA (Java 25+; default: java), WORK_DIR (default: ./smp-load),
#        TOWNS (default 12), ADDRESSES (per town, default 20), LOAD_SECONDS (postmen running; default 900),
#        MIN_TPS (fail below this 1-minute TPS; default 18)
#
# Exits non-zero if a survey failed or the TPS dropped below MIN_TPS.
# Running this accepts the Minecraft EULA for a throwaway test server.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="${1:-$(ls -t "$REPO"/target/va_postal-*.jar 2>/dev/null | head -1)}"
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "no Postal jar; build first (./mvnw package)" >&2; exit 2; }
JAR="$(realpath "$JAR")"
JAVA="${JAVA:-java}"
WORK_DIR="${WORK_DIR:-smp-load}"
TOWNS="${TOWNS:-12}"
ADDRESSES="${ADDRESSES:-20}"
LOAD_SECONDS="${LOAD_SECONDS:-900}"
MIN_TPS="${MIN_TPS:-18}"
# shellcheck source=ci/lib.sh
source "$REPO/ci/lib.sh"

mkdir -p "$WORK_DIR/cache"
WORK_DIR="$(realpath "$WORK_DIR")"
CACHE="$WORK_DIR/cache"
SERVER="$WORK_DIR/server"
CONFIG="$SERVER/plugins/Postal/config.yml"
LIST="$WORK_DIR/towns.json"

download_server_jars "$CACHE"
rm -rf "$SERVER"
install_server "$CACHE" "$SERVER"
cp "$JAR" "$SERVER/plugins/Postal.jar"
echo "eula=true" > "$SERVER/eula.txt"
cat > "$SERVER/server.properties" <<'PROPS'
online-mode=false
level-type=minecraft\:flat
generate-structures=false
view-distance=6
simulation-distance=6
spawn-protection=0
max-players=1
PROPS
mkdir -p "$SERVER/world/datapacks"
python3 "$REPO/dev/towns/build_smp.py" --towns "$TOWNS" --addresses "$ADDRESSES" \
    --datapack "$SERVER/world/datapacks/postal_smp" --list "$LIST"

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
            if [[ "$cmd" == sleep:* ]]; then sleep "${cmd#sleep:}"; else echo "$cmd"; sleep 1; fi
        done
        echo stop
    ) | (cd "$SERVER" && timeout "$limit" "$JAVA" -Xms2G -Xmx3G -jar paper.jar nogui) > "$log" 2>&1 || true
}

echo "== Building $TOWNS towns"
BUILD=("function postal_smp:central" "sleep:3")
for i in $(seq 0 $((TOWNS - 1))); do BUILD+=("function postal_smp:town_$i" "sleep:4"); done
run_server "$WORK_DIR/build.log" 1800 "${BUILD[@]}" "save-all flush" "sleep:10"
keep_libraries "$CACHE" "$SERVER"
built=$(grep -c "Postal SMP built town_" "$WORK_DIR/build.log" || true)
[ "$built" = "$TOWNS" ] || { echo "only $built of $TOWNS towns were built; see $WORK_DIR/build.log" >&2; exit 1; }

python3 "$REPO/dev/towns/build_smp.py" --towns "$TOWNS" --addresses "$ADDRESSES" --config "$CONFIG"
python3 - "$CONFIG" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path).read()
# Every office at once, stuck postmen reported, quick pacing; no debug output (it's a load test).
for key, value in {"Concurrent_Postmen": "true", "Report_nav_probs": "true", "Postman_cool_sec": "10",
                   "Central_cool_sec": "10", "Residence_cool_ticks": "40", "Heart_beat_ticks": "40",
                   "Heart_beat_auto": "false"}.items():
    line = "  %s: '%s'" % (key, value)
    text, n = re.subn(r"(?m)^  %s: .*$" % key, line, text)
    if n == 0:
        text = re.sub(r"(?m)^Settings:$", "Settings:\n" + line, text, count=1)
open(path, "w").write(text)
PY

mapfile -t TOWN_NAMES < <(python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))))' "$LIST")
total=$(python3 -c 'import json,sys; print(sum(len(t["addresses"]) for t in json.load(open(sys.argv[1])).values()))' "$LIST")
RUN=()
for t in "${TOWN_NAMES[@]}"; do RUN+=("postal survey $t"); done
RUN+=("sleep:$((60 + total / 2))")
RUN+=("postal start")
for _ in $(seq 1 $((LOAD_SECONDS / 30))); do RUN+=("sleep:28" "tps" "mspt"); done
for t in "${TOWN_NAMES[@]}"; do RUN+=("alist $t"); done
RUN+=("postal stop" "sleep:5")

echo "== Surveying $total routes, then running every post office for ${LOAD_SECONDS}s"
run_server "$WORK_DIR/load.log" "$((LOAD_SECONDS + total + 900))" "${RUN[@]}"

echo "== Results"
python3 - "$LIST" "$WORK_DIR/load.log" "$MIN_TPS" <<'PY'
import json, re, statistics, sys
towns = json.load(open(sys.argv[1]))
log = re.sub(r"\x1b\[[0-9;]*m|§.", "", open(sys.argv[2], errors="replace").read())
min_tps = float(sys.argv[3])
total = sum(len(t["addresses"]) for t in towns.values())

saved = re.findall(r"Route (\w+), (\w+): (\d+) waypoints over (\d+) blocks.*?\((\d+) positions searched, (\d+) ms\)", log)
failed = re.findall(r"Route (\w+), (\w+): (?!\d+ waypoints)(.*)", log)
print(f"Surveys: {len(saved)} of {total} found a route, {len(failed)} failed")
if saved:
    ms = [int(s[5]) for s in saved]
    pos = [int(s[4]) for s in saved]
    blocks = [int(s[3]) for s in saved]
    slow = max(saved, key=lambda s: int(s[5]))
    print(f"  time: median {statistics.median(ms)} ms, slowest {max(ms)} ms ({slow[0]}, {slow[1]}); "
          f"positions: median {int(statistics.median(pos))}, most {max(pos)}; route length: median "
          f"{int(statistics.median(blocks))} blocks, longest {max(blocks)}")
for t, a, why in failed[:20]:
    print(f"  FAILED {t}, {a}: {why}")

tps = [float(m) for m in re.findall(r"TPS from last 1m, 5m, 15m: \*?([\d.]+)", log)]
mspt = [tuple(float(v) for v in m) for m in re.findall(r"([\d.]+)/([\d.]+)/([\d.]+), [\d.]+/[\d.]+/[\d.]+, [\d.]+/[\d.]+/[\d.]+", log)]
if tps:
    print(f"Server: TPS (1m) min {min(tps):.2f}, median {statistics.median(tps):.2f} over {len(tps)} samples")
if mspt:
    avgs = [m[0] for m in mspt]
    print(f"  tick time (5s avg): median {statistics.median(avgs):.1f} ms, worst {max(avgs):.1f} ms; "
          f"longest single tick {max(m[2] for m in mspt):.1f} ms")

# alist: each town's addresses with their last round trip in seconds (0: none yet).
visited = {}
for block in re.split(r"Addresses for ", log)[1:]:
    town = block.split(":", 1)[0].strip()
    secs = [int(x) for x in re.findall(r"(?m)^\[[^\]]*\]: (?:\[Postal\] (?:\[STDOUT\] )?)?\s+\S+\s+\S+\s+Seconds: (\d+)", block)]
    visited[town] = (sum(1 for x in secs if x > 0), len(secs))
done = sum(v for v, _ in visited.values())
rescues = re.findall(r"(Teleport Reset|Soft Reset).*?Nav recovery for\s*:\s*(\S+).*?While servicing\s*:\s*(\S+)", log, re.S)
print(f"Postmen: {done} of {total} addresses had a round trip, {len(rescues)} rescues")
print("  by town: " + ", ".join(f"{t} {v}/{n}" for t, (v, n) in sorted(visited.items())))
by = {}
for kind, office, addr in rescues:
    by[(office, addr)] = by.get((office, addr), 0) + 1
for (office, addr), n in sorted(by.items(), key=lambda kv: -kv[1])[:15]:
    print(f"  rescued {n}x: {office}, {addr}")
errors = len(re.findall(r"Exception", log))
print(f"Exceptions in the log: {errors}")

bad = []
if failed:
    bad.append(f"{len(failed)} survey(s) failed")
if len(saved) + len(failed) < total:
    bad.append(f"{total - len(saved) - len(failed)} survey(s) never reported (still queued?)")
# Every office runs its addresses in turn: a town with no round trip at all has a frozen postman.
for t, (v, n) in visited.items():
    if n and v == 0:
        bad.append(f"{t}: no round trip at all")
if tps and min(tps) < min_tps:
    bad.append(f"TPS fell to {min(tps):.2f} (< {min_tps})")
print("OK" if not bad else "PROBLEMS: " + "; ".join(bad))
sys.exit(1 if bad else 0)
PY
