# Postal (va_postal)

A revived Minecraft plugin for Paper 26.1.2: NPC postmen (Citizens) walk routes between a Central post office,
local post offices and player addresses, carrying letters and parcels. Mail state lives in a store (SQLite or
MySQL/MariaDB), money goes through Vault, and post offices can span servers.

## Layout

| Path | What |
|---|---|
| `src/main/java/com/vodhanel/minecraft/va_postal/` | plugin code: `commands/`, `common/` (Util, P_* integrations, dispatchers), `config/` (C_* config accessors), `economy/`, `listeners/`, `mail/`, `navigation/` (routes, Stuck_NPC, Climb, Doorway), `store/` (mail store, SQL dialects) |
| `src/test/java/...` | JUnit 5 unit tests; store tests run on SQLite and (when configured) MariaDB |
| `ci/` | `smoke-test.sh` (boots a real Paper server, 11 phases), `bot-test.sh` + `bot/` (headless player tests), `lib.sh` (pinned Paper/plugin builds), `start-mariadb.sh` |
| `dev/` | `test-server.sh` (local server to join by hand), `rcon.py`; on `claude/routes-design` also `dev/towns/` (towns soak, SMP load test) |
| `docs/` | `commands.md`, `economy.md`, `design/` (economy, persistent-state, routes), `testing/` (per-phase test plans), `plans/` |

Most code is inherited from the original plugin: static methods, `snake_case` names, `Util.cinform`/`pinform`
for output. Match the surrounding style; don't modernise code you aren't changing.

## Build and test

- `mvn -q -o package` builds the jar and runs the unit tests (drop `-o` if a dependency is missing). The pom
  targets Java 21, so the system JDK 21 builds it.
- One test: `mvn -q -o test -Dtest=ReservesTest`. `-q` hides the results; drop it to see "Tests run".
- MariaDB store tests are skipped unless `POSTAL_TEST_MYSQL_URL` is set. Run them locally with:
  ```bash
  ci/start-mariadb.sh postal_test
  POSTAL_TEST_MYSQL_URL='jdbc:mariadb://127.0.0.1:3306/postal_test?user=postal&password=postal' \
      mvn -o test -Dtest=MariaDbMailStoreTest
  docker rm -f postal-mariadb
  ```
- Paper 26.x needs **Java 25 to run**. In cloud sessions the start hook installs it: use `$JAVA25`
  (`/opt/temurin-25/bin/java`). Smoke test: `JAVA=$JAVA25 WORK_DIR=/tmp/smoke ci/smoke-test.sh target/va_postal-*.jar`
  (add `STORAGE=mysql` after starting MariaDB). It takes about 15 minutes.
- There's no linter. CI runs `mvn verify` plus the smoke test on SQLite and MySQL.

## Cloud sessions

`.claude/hooks/session-start.sh` runs at session start. It installs JDK 25, starts the Docker daemon and fills
the Maven cache. If Docker isn't up, run `nohup dockerd >/tmp/dockerd.log 2>&1 &`.

## Test servers: rules

- **Give every server you start its own port** (`server-port` in server.properties). Other sessions and agents
  may be running servers on this machine at the same time. Ranges: 25611-25619 single-route tests,
  25620-25629 bot-player tests, 25565 only for `dev/test-server.sh`.
- **Kill only the PIDs you started.** Never `pkill java` or `kill` every Paper server: that ends other people's
  soaks.
- Keep servers, worlds and downloads under the scratchpad or a git-ignored dir, not in the repo.
- Removing directories: guard variables (`rm -rf "${DIR:?}"`).

## Git and PRs

- One change per branch off `master`; open a PR; CI must be green; **squash merge**. Auto-merge is enabled:
  open small PRs with auto-merge on (squash) and they merge themselves when CI passes.
- **All pathfinding and route-walking changes go on `claude/routes-design`** (PR #11), not master.
- PR #10 (cross-server, `claude/persistent-p4`) and PR #11 belong to the owner to review, test in game and
  merge. Don't merge them, and don't push to them except for merging `master` into them when asked.
- Never rewrite history on a shared branch (no rebase, amend or force-push); use merge commits.
- PR descriptions: what changed and why, how it was tested, and what CI can't cover (anything only a player can
  do: compass, particles, in-game GUIs). Say so explicitly when a fix needs an in-game check.

## CI

`.github/workflows/ci.yml`: **Build and unit tests** (with MariaDB), then **Smoke test (sqlite)**,
**Smoke test (mysql)** and **Bot player test** in parallel. That's about 16 minutes, mostly the smoke tests. Failures on `master` or the weekly
run open a `ci-failure` issue.

When a job fails, read its log before anything else. If it died before any build or test step (image pull,
checkout, runner loss), it's infrastructure: re-run it once and say so on the PR. Otherwise it's real: fix it.
Never skip or weaken a test to get green.

The smoke test drives the server from the console only. Player-only behaviour (compass targets so far) is covered
by the **Bot player test** job: `ci/bot-test.sh` boots a server and joins a headless player built with
MCProtocolLib (`ci/bot/`, `docs/design/bot-player.md`). Run it locally with
`JAVA=$JAVA25 PORT=25620 WORK_DIR=/tmp/bot-work ci/bot-test.sh target/va_postal-*.jar`. Add new player-only checks
there as scenarios, and see a new check fail against the unfixed code before trusting it.

## Gotchas learned the hard way

- `Util.str2location` / `str2block` return null for a missing world or a malformed string: always check.
- Citizens floors navigation targets to the block corner and applies `distanceMargin` per path node. Centring a
  target with +0.5 doesn't change where the pathfinder aims.
- Citizens treats water as standable; Postal swaps in `navigation/DryGround` when `avoidWater` is on.
- Bukkit's `getInt` parses numeric strings too ('1' works), so a quoted number in config.yml isn't a bug.
- Smoke phase 7 inserts an `Owner` block before `Location` in config.yml; YAML edits in later phases must
  allow for it.
- Console senders aren't players: anything using `player.getWorld()` or compass targets needs a player path.

## Agents

Project agents in `.claude/agents/`: `small-fix` (one bug, one PR), `ci-doctor` (diagnose a failed CI run),
`routes` (pathfinding work on the routes branch), `reviewer` (read-only review of a diff before pushing).
