# Shared by ci/smoke-test.sh (CI) and dev/test-server.sh (local test server) so both always run
# the same Paper and plugin builds and seed the same test network. Source it; don't run it.

# Paper 26.x needs Java 25. EssentialsX 2.22.0 supports up to 26.1.2.
PAPER_VERSION="${PAPER_VERSION:-26.1.2}"
REQUIRED_JAVA=25

# Pinned dependency builds. Bump deliberately; Citizens dev builds are tied to MC versions.
CITIZENS_URL="https://ci.citizensnpcs.co/job/Citizens2/4256/artifact/dist/target/Citizens-2.0.44-b4256.jar"
VAULT_URL="https://cdn.modrinth.com/data/ayRaM8J7/versions/qZgRzoYs/VaultUnlocked-2.20.3.jar"
ESSENTIALS_URL="https://cdn.modrinth.com/data/hXiIvTyT/versions/nY6VN1XH/EssentialsX-2.22.0.jar"

# Office account UUIDs Postal derives (see OfficeAccounts / OfficeAccountsTest).
CENTRAL_ACCOUNT="2ca1fae9-c175-239e-9eb2-df89430f098b"

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

# download_server_jars <cache-dir>: Paper + Citizens + VaultUnlocked + EssentialsX into the cache.
download_server_jars() {
    mkdir -p "$1"
    fetch "$(paper_url)" "$1/paper-$PAPER_VERSION.jar"
    fetch "$CITIZENS_URL" "$1/Citizens.jar"
    fetch "$VAULT_URL" "$1/VaultUnlocked.jar"
    fetch "$ESSENTIALS_URL" "$1/EssentialsX.jar"
}

# install_server <cache-dir> <server-dir>: lays out a server from the cached jars.
install_server() {
    mkdir -p "$2/plugins"
    cp "$1/paper-$PAPER_VERSION.jar" "$2/paper.jar"
    cp "$1/Citizens.jar" "$1/VaultUnlocked.jar" "$1/EssentialsX.jar" "$2/plugins/"
}

# java_major <java-binary>: prints the major version, e.g. 25 (and 8 for Java 8's "1.8.0").
java_major() {
    local v
    v="$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9.]*\).*/\1/p' | head -1)"
    case "$v" in
        1.*) v="${v#1.}" ;;
    esac
    echo "${v%%.*}"
}

# ---- Seed test network ---------------------------------------------------------------------
# A flat world (surface y=-60) with Central at the origin and one street per town. On each street
# the post office chest is at x=20 and the addresses at x=40,50,60,70,80; every chest faces south
# with its sign in front (z+1), and routes run along z+2 from the post office to the address, one
# waypoint every 5 blocks.
#
#   SEED_SIZE=full (default): 3 towns x 5 addresses
#     Testville  z=0   Home, Bakery, Smithy, Library, Farm
#     Riverside  z=40  Mill, Docks, Inn, Chapel, Market
#     Hilltop    z=80  Manor, Tower, Lodge, Orchard, Barracks
#   SEED_SIZE=small: Testville with Home only (what the CI smoke test runs; quick to cycle)
#
# SEED_COMMANDS (run in the server console) and seed_config (the matching Postal config) both
# follow SEED_SIZE as it is when this file is sourced.

SEED_SIZE="${SEED_SIZE:-full}"
SEED_TOWNS=(Testville Riverside Hilltop)
declare -A SEED_TOWN_Z=([Testville]=0 [Riverside]=40 [Hilltop]=80)
declare -A SEED_ADDRESSES=(
    [Testville]="Home Bakery Smithy Library Farm"
    [Riverside]="Mill Docks Inn Chapel Market"
    [Hilltop]="Manor Tower Lodge Orchard Barracks"
)
SEED_ADDRESS_X=(40 50 60 70 80)
SEED_PO_X=20

# seed_towns / seed_addresses <town>: the parts of the network SEED_SIZE includes.
seed_towns() {
    if [ "$SEED_SIZE" = small ]; then echo Testville; else echo "${SEED_TOWNS[@]}"; fi
}
seed_addresses() {
    if [ "$SEED_SIZE" = small ]; then echo Home; else echo "${SEED_ADDRESSES[$1]}"; fi
}

seed_sign() { # line1 line2 line3
    printf 'minecraft:oak_wall_sign[facing=south]{front_text:{messages:["%s","%s","%s",""]}}' "$1" "$2" "$3"
}

seed_network() {
    local town z i addr x
    if [ "$SEED_SIZE" = small ]; then
        SEED_COMMANDS=("forceload add -16 -16 64 16")
    else
        SEED_COMMANDS=("forceload add -16 -16 96 96")
    fi
    SEED_COMMANDS+=("setblock 0 -60 0 minecraft:chest[facing=south]")
    for town in $(seed_towns); do
        z=${SEED_TOWN_Z[$town]}
        SEED_COMMANDS+=("setblock $SEED_PO_X -60 $z minecraft:chest[facing=south]"
                        "setblock $SEED_PO_X -60 $((z + 1)) $(seed_sign "[Postal_Mail]" "$town" "[Local]")")
        i=0
        for addr in $(seed_addresses "$town"); do
            x=${SEED_ADDRESS_X[$i]}
            SEED_COMMANDS+=("setblock $x -60 $z minecraft:chest[facing=south]"
                            "setblock $x -60 $((z + 1)) $(seed_sign "[Postal_Mail]" "$town" "$addr")")
            i=$((i + 1))
        done
    done
}
seed_network

# seed_config <postal-config.yml>: appends the seed network's offices, addresses and routes.
seed_config() {
    local town z i addr x wx n
    {
        echo "Postoffice:"
        echo "  Central:"
        echo "    Location: world,0.0,-60.0,2.0"
        echo "  Local:"
        for town in $(seed_towns); do
            z=${SEED_TOWN_Z[$town]}
            echo "    $town:"
            echo "      Location: world,$SEED_PO_X.0,-60.0,$((z + 2)).0"
        done
        echo "Address:"
        for town in $(seed_towns); do
            z=${SEED_TOWN_Z[$town]}
            echo "  $town:"
            i=0
            for addr in $(seed_addresses "$town"); do
                x=${SEED_ADDRESS_X[$i]}
                echo "    $addr:"
                echo "      Residence:"
                echo "        Location: world,$x.0,-60.0,$((z + 2)).0"
                echo "      Route:"
                n=0
                for ((wx = SEED_PO_X; wx <= x; wx += 5)); do
                    echo "        '$n':"
                    echo "          Location: world,$wx.0,-60.0,$((z + 2)).0"
                    n=$((n + 1))
                done
                i=$((i + 1))
            done
        done
    } >> "$1"
}
