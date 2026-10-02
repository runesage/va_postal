# Local test server

`dev/test-server.sh` runs a Paper server on your machine with Postal and its dependencies, so you
can join with a Minecraft client and test by hand. It uses the same Paper and plugin builds as CI
(pinned in `ci/lib.sh`), and Postal's debug output is switched on.

**Needs:** Java 25+ (https://adoptium.net), Maven, curl, python3 and bash. On Windows, run it from
WSL or Git Bash.

```bash
dev/test-server.sh setup                 # once: downloads, asks you to accept the EULA, asks your username
dev/test-server.sh start --seed          # builds Postal, installs it, starts the server
```

Join at `localhost:25565`. You're opped automatically on every start (the username is saved in
`dev-server/op-player`; change it with `--op NAME`). The world is flat, creative and peaceful.

`--seed` builds a small working network at the world origin on first use, the same one CI tests:

| Where | What |
|---|---|
| `0 -60 0` | Central post office chest |
| `20 -60 0` | Testville local post office (chest + sign) |
| `40 -60 0` | Home address (chest + sign) |
| `20..40 -60 2` | route from Testville to Home |

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

Environment: `JAVA` (path to Java 25+), `DEV_DIR` (default `dev-server/`), `PAPER_VERSION`.

The server has RCON enabled on port 25575 with a random password (in `server.properties`). The script
uses it to send the op/seed commands while your console stays interactive. Don't expose that port.
