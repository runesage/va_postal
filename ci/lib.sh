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

# java_major <java-binary>: prints the major version, e.g. 25.
java_major() {
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

# ---- Seed test network ---------------------------------------------------------------------
# A central office, one local office ("Testville") and one address ("Home") with a five-waypoint
# route, laid out on a flat world (surface y=-60) around the origin:
#
#   Central chest (0,-60,0)   Testville chest+sign (20,-60,0)   Home chest+sign (40,-60,0)
#   route: (20,-60,2) -> (25) -> (30) -> (35) -> (40,-60,2)
#
# SEED_COMMANDS must run in the server console; seed_config writes the matching Postal config.

SEED_SIGN_LOCAL='minecraft:oak_wall_sign[facing=south]{front_text:{messages:["[Postal_Mail]","Testville","[Local]",""]}}'
SEED_SIGN_HOME='minecraft:oak_wall_sign[facing=south]{front_text:{messages:["[Postal_Mail]","Testville","Home",""]}}'
SEED_COMMANDS=(
    "forceload add -16 -16 64 16"
    "setblock 0 -60 0 minecraft:chest[facing=south]"
    "setblock 20 -60 0 minecraft:chest[facing=south]"
    "setblock 20 -60 1 $SEED_SIGN_LOCAL"
    "setblock 40 -60 0 minecraft:chest[facing=south]"
    "setblock 40 -60 1 $SEED_SIGN_HOME"
)

# seed_config <postal-config.yml>: appends the seed network's offices, address and route.
seed_config() {
    cat >> "$1" <<'EOF'
Postoffice:
  Central:
    Location: world,0.0,-60.0,2.0
  Local:
    Testville:
      Location: world,20.0,-60.0,2.0
Address:
  Testville:
    Home:
      Residence:
        Location: world,40.0,-60.0,2.0
      Route:
        '0':
          Location: world,20.0,-60.0,2.0
        '1':
          Location: world,25.0,-60.0,2.0
        '2':
          Location: world,30.0,-60.0,2.0
        '3':
          Location: world,35.0,-60.0,2.0
        '4':
          Location: world,40.0,-60.0,2.0
EOF
}
