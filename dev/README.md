# Local test server

`dev/test-server.sh` runs a Paper server on your machine with Postal and its dependencies, so you
can join with a Minecraft client and test by hand. It uses the same Paper and plugin builds as CI
(pinned in `ci/lib.sh`), and Postal's debug output is switched on.

**Needs:** curl, python3 and bash (on Windows, use WSL or Git Bash). Maven isn't needed: the build
uses the repo's Maven Wrapper (`./mvnw`), which downloads the pinned Maven on first use. Paper needs Java 25+:
if `java` on your PATH is older, `setup` downloads a private Temurin JDK 25 into `dev-server/jdk` and
uses it for the build and the server. Your system Java is left alone. To use a specific Java instead,
set `JAVA=/path/to/java`.

```bash
dev/test-server.sh setup                 # once: downloads, asks you to accept the EULA, asks your username
dev/test-server.sh start --seed          # builds Postal, installs it, starts the server
```

Join at `localhost:25565`. You're opped automatically on every start (the username is saved in
`dev-server/op-player`; change it with `--op NAME`). The world is flat, creative and peaceful.

Players start with 100,000 in the economy (set `DEV_BALANCE` to change it), so you can test buying
offices and paying postage without `/eco`. Essentials only writes its config on its first start, so
on a brand-new server this applies from the second start; an account that joined before then is
topped up once on the next start. To reset your balance at any time while the server is running:
`dev/test-server.sh money [AMOUNT]`.

`--seed` builds a test network at the world origin on first use: Central plus three towns, each a
street with a post office and five addresses, all with routes.

| Where | What |
|---|---|
| `0 -60 0` | Central post office chest |
| street at `z=0` | **Testville**: post office `20 -60 0`; Home `40`, Bakery `50`, Smithy `60`, Library `70`, Farm `80` (x) |
| street at `z=40` | **Riverside**: post office `20 -60 40`; Mill, Docks, Inn, Chapel, Market at x = 40..80 |
| street at `z=80` | **Hilltop**: post office `20 -60 80`; Manor, Tower, Lodge, Orchard, Barracks at x = 40..80 |

Every chest faces south with its `[Postal_Mail]` sign in front of it, and each route runs along the
street (`z+2`) from the post office to the address. Each town has its own postman. Use
`SEED_SIZE=small` for just Testville and Home (what CI runs). An already-seeded server keeps its old
network: run `dev/test-server.sh reset`, then `start --seed`, to get this one.

The dispatcher starts automatically once the network is seeded, so a postman walks the route within
a minute or two. Build your own offices and routes with the normal commands (`/setcentral`,
`/setlocal`, `/setaddr`, `/setroute`) alongside it.

## Reporting a problem

```bash
dev/test-server.sh report
```

This bundles `dev-server/reports/postal-report-<time>.tar.gz` with the server logs from the last day,
Postal's config, Citizens' NPC data and the exact versions/commit. It also writes `excerpt.txt`: just
Postal output, warnings and stack traces. Share the tarball or paste the excerpt, and say what you did
and what you expected.

## Other commands

| Command | Does |
|---|---|
| `start --no-build` | start without rebuilding Postal |
| `reset` | fresh world: wipes worlds, Postal config, NPCs and Essentials user data |
| `reset --all` | delete the whole server (downloads are kept) |
| `setup --offline` | `online-mode=false`, for clients without an account. Private networks only. |

Environment: `JAVA` (path to Java 25+, overrides the auto-detected/downloaded one), `DEV_DIR` (default `dev-server/`), `PAPER_VERSION`.

The server has RCON enabled on port 25575 with a random password (in `server.properties`). The script
uses it to send the op/seed commands while your console stays interactive. Don't expose that port.
