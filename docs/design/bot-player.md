# Headless bot player for CI

Today CI (`ci/smoke-test.sh`) drives the server only from its console. Anything that needs a player is untested:
the compass target set by `/gps`, `/postal test`, route walking checks. This describes the bot that fills that gap.

## Research: client libraries that can join Paper 26.1.2

Checked 2026-10-10. Paper 26.1.2 speaks protocol 775 (the 26.1 protocol; 26.1.x are patch releases).

| | MCProtocolLib | Mineflayer / node-minecraft-protocol |
|---|---|---|
| Language | Java 21+ | Node.js |
| Latest | `org.geysermc.mcprotocollib:protocol:26.1-1` (a `26.2-SNAPSHOT` and `26.3-SNAPSHOT` also exist) | mineflayer 4.39.0, minecraft-protocol 1.68.0, minecraft-data 3.117.0 |
| 26.1.x | Yes. Codec reports Minecraft `26.1`, protocol 775. Verified against a real Paper 26.1.2 server (below). | Yes. Both list `26.1` as supported (and node-minecraft-protocol's default); mineflayer lists it as tested. Not run here. |
| Licence | MIT | MIT |
| Distribution | Not on Maven Central. GeyserMC's repo `https://repo.opencollab.dev/maven-releases/`; transitive deps also need `https://maven.lenni0451.net/everything/` (MinecraftAuth). | npm |
| In GitHub Actions | `actions/setup-java` + `mvn package` (already present for the plugin build); no extra runtime. | `actions/setup-node` + `npm ci`. |
| Level | Low level: packets in, packets out. No world, inventory or pathfinding model. | High level: world, entities, inventory, pathfinding plugins. |

### Recommendation: MCProtocolLib

It supports 26.1.2, it is Java/Maven like the rest of the repo, CI already has a JDK, and everything the planned
tests need is one packet away (chat/system messages, set-default-spawn-position for the compass). If a future
test needs walking, digging or inventory logic, Mineflayer's world model is the better tool and the control
protocol below (stdin lines in, event lines out) would carry over to a Node bot unchanged.

Caveats of MCProtocolLib: it publishes releases per Minecraft version only (a new Minecraft means waiting for or
bumping to a new tag; the pin is `mcprotocollib.version` in `ci/bot/pom.xml`), and the opencollab repository is a
third-party host (CI downloads are cached by Maven's local repo, which is worth caching in Actions).

## What is implemented

`ci/bot/` is a separate small Maven project. It is not a module of the plugin pom, is not a dependency of it, and
is not shaded into the plugin jar.

- `ci/bot/src/main/java/ci/bot/Bot.java`: `Bot <host> <port> <name>` logs in offline-mode and stays connected.
  - Stdin, one instruction per line: `/cmd args` sends that chat command (a lone `/` sends the empty command, which
    Postal reads as "confirm"), `quit` disconnects.
  - Stdout, one event per line, `<epoch-millis> <EVENT> <detail>`: `JOINED`, `COMPASS <dim> <x> <y> <z>` (the
    "set default spawn position" packet, i.e. `Player#setCompassTarget`), `CHAT <text>` (system/player/disguised
    chat flattened to plain text; translatable messages show their key and arguments), `SENT`, `DISCONNECTED`, `ERROR`.
- `ci/bot-test.sh <postal.jar>`: reuses `ci/lib.sh` (Paper, Citizens, Vault, EssentialsX downloads, the seeded
  Testville network and its config). Boot 1 builds the seed world and lets Postal write its config; boot 2 keeps the
  server on a console fifo, starts the bot on a second fifo, and runs the scenario:
  1. bot joins, console `op PostalBot`
  2. `/gps testville` says it is ready and the compass has not moved
  3. `/` confirms; the compass target becomes Testville's post office (`minecraft:overworld 20 -60 2`, the seeded
     location)
  4. `/tlist` answers and the compass target does **not** change (the bug fixed in PR #37)

Note the confirmation step: `/gps` registers the pending command and Postal's `PlayerCommandPreprocessEvent`
handler rewrites the next command `/` into the registered one (`BukkitListener`).

### Running locally

```
mvn -q -o package                       # the plugin jar, in target/
JAVA=/path/to/java25 PORT=25620 WORK_DIR=/tmp/bot-work ci/bot-test.sh target/va_postal-*.jar
```

`JAVA` is the Java 25 runtime for Paper (the smoke test's setting); the bot and its Maven build use the default
`java`/`mvn` (21+). `BOT_JAVA` overrides the bot's java. The script builds the bot on first use (needs network for
Maven; the bot's dependencies land in `ci/bot/target/lib`). Output ends with the PASS/FAIL list and the bot's full
event log; the exit status is non-zero on any FAIL. Server log: `$WORK_DIR/server.log`, bot events:
`$WORK_DIR/bot.log`.

The script restarts the server (up to 3 tries) when a plugin fails to enable: Citizens downloads libraries on first start through the sandbox proxy and that sometimes fails, which also takes Postal down. Local result (Paper 26.1.2 build 74, MCProtocolLib 26.1-1): all checks pass, on two runs (one of them after such a retry); the bot joins in about 1 s after the
server is up and the scenario takes a few seconds.

## In CI

The `bot` job in `.github/workflows/ci.yml` runs `ci/bot-test.sh` on Java 25 alongside the smoke tests, caching
Maven's repository and the server downloads, and uploads `bot-logs` on every run.

The test was checked against a build with PR #37 reverted: it fails on the two `/tlist` checks (the compass jumps to
`0 0 -12550820`, the old reset point) and passes with the fix.

### Original sketch

Add a job (or a step in the `smoke` job, which already has Java 25 and the cached `smoke/cache`):

```yaml
- uses: actions/setup-java@v4          # a second JDK is not needed: Java 25 also runs the bot and Maven
- name: Cache bot dependencies
  uses: actions/cache@v4
  with: { path: ~/.m2/repository, key: bot-${{ hashFiles('ci/bot/pom.xml') }} }
- name: Run bot test
  env: { PAPER_VERSION: "26.1.2", WORK_DIR: bot-work, PORT: "25565" }
  run: ci/bot-test.sh dist/va_postal-*.jar
- uses: actions/upload-artifact@v4
  if: always()
  with:
    name: bot-logs
    path: |
      bot-work/*.log
      bot-work/server/logs/
```

(With only Java 25 on the runner, set `JAVA=java` and let `BOT_JAVA` default; both then use it. `mvn` needs
`release 21`, which Java 25 satisfies.) Share the Paper/plugin download cache with the smoke test by pointing
`WORK_DIR` at the same cache directory or by copying `smoke/cache` to `bot-work/cache`.

## Not done yet / next steps

- Signed chat messages (non-command chat) are not sent; only commands. Commands are sent unsigned, which is fine on
  an offline-mode server with `enforce-secure-profile=false`.
- The bot does not track its position, inventory or the world. `/postal test` and route-walking checks need at least
  position tracking (`ClientboundPlayerPositionPacket`, `ClientboundMoveEntity*`) and would use the server console
  (`tp`) to place the bot; Mineflayer is the fallback if those checks need real pathfinding.
- Further scenarios to add: `/gps <town> <address>`, `/gps` for a different world, permission-denied paths (a
  non-op bot), `/go`.
