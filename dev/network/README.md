# Three-server test network

`dev/network/network.sh` runs a small Postal network on your machine: three Paper servers behind a Velocity
proxy, sharing one MariaDB mail store, as a real multi-server setup would. Join the proxy with a client and
move between the servers with `/server`, or let `check` post letters across it from a script.

| Server | World | What it's for |
|---|---|---|
| **big** | the SMP world (`dev/towns/build_smp.py`): 12 towns x 20 houses over ~1,300 x 1,300 blocks | a busy survival server: many offices, long routes, every route surveyed |
| **small** | the test towns (`dev/towns/build_towns.py`): Hillcrest, Riverside, Woodvale, Stonegate | a small server with hand-made routes past stairs, bridges, doors, ladders and a canal |
| **dims** | one network over three dimensions (`dev/towns/build_dims.py`) | mail between the Overworld, the Nether and the End |

On **dims**:
- **Central** and **Brookfield** are in the Overworld.
- **Cinderholm** is in the Nether, in a sealed blackstone cavern. A lava channel crosses the town, with one
  bridge over it (Brimstone and Wither are on the far side).
- **Voidreach** is in the End, on a platform floating in the void at x=400, away from the main island. Outlook
  stands on an islet reached by a one-wide bridge over the void.

Central's postman carries mail between dimensions: a letter from Brookfield to Voidreach goes to Central in
the Overworld, and from there to Voidreach's office in the End. Inside a town the postmen walk as usual.
Routes on big and dims are surveyed by Postal during `setup`, so the survey runs in all three dimensions too.

**Needs:** bash 4+, Java 25+ (`JAVA=/path/to/java` if `java` is older), python3, curl, `timeout` and Docker (for
MariaDB; or point it at your own, empty, database with `MYSQL_HOST` and friends). On macOS, `brew install bash
coreutils` for the first and `timeout`. About 8 GB of free memory for the four processes.

```bash
dev/network/network.sh setup --op YourName   # once: downloads, builds the worlds, surveys the routes (~15 min)
dev/network/network.sh start                 # MariaDB, the three servers and the proxy, in the background
```

Join at `localhost:25630`. You start on **small**; switch with `/server big`, `/server small` or `/server dims`.
You're opped on all three and start with 100,000 in each server's economy. The worlds are flat, creative and
peaceful (no hostile mobs, even in the Nether and the End).

Getting to the towns on **dims**: `/execute in minecraft:the_nether run tp @s 6 64 1` (Cinderholm's office) and
`/execute in minecraft:the_end run tp @s 406 64 1` (Voidreach's office). Brookfield is at `46 -60 41`.

`setup --offline` lets any username join without a Mojang account (for bots, or a client without one), so the
proxy then only listens on this machine (127.0.0.1). The servers' own ports and RCON always do.

## Commands

| Command | Does |
|---|---|
| `setup [--offline] [--op NAME] [--no-build] [--accept-eula] [--with-gate]` | asks you to accept the Minecraft EULA (`--accept-eula` skips the question, for scripts), then builds Postal, downloads Paper, the plugins and Velocity, starts a fresh MariaDB, builds the three worlds and surveys their routes; `--with-gate` adds The Gate (below) |
| `start` | starts everything in the background and starts each server's dispatcher |
| `stop` | stops the proxy, the servers and MariaDB (only the processes `start` recorded; data is kept) |
| `status` | what's running, and on which ports |
| `cmd SERVER COMMAND...` | runs console commands over RCON and prints the replies, e.g. `cmd dims postal network` |
| `log SERVER` | follows a server's log (`big`, `small`, `dims` or `proxy`) |
| `check` | posts test letters between every pair of servers and between the dimensions, waits for them and reports (below) |
| `gate` | checks The Gate on each server and prints how to dial between them (needs `setup --with-gate`) |
| `player` | a headless player moves through the proxy from server to server with a letter it posted (below; needs `setup --offline`) |
| `reset` | stops everything and deletes the servers and the MariaDB container (downloads and your `setup` options are kept) |

Everything lives in `dev-network/` (git-ignored; `NET_DIR` to move it).

## Ports

| Port | What |
|---|---|
| 25630 | the proxy: join here |
| 25631 / 25632 / 25633 | big / small / dims (game ports; reachable only through the proxy) |
| 25634 / 25635 / 25636 | big / small / dims RCON (`cmd` uses these) |
| 3307 | MariaDB (`postal-net-mariadb` container, database `postal_net`, user and password `postal`) |

`NET_PORT` moves the whole block (the servers take the six ports after the proxy's).

## The check

`dev/network/network.sh check` (with the network running) posts these test letters and waits up to
`CHECK_MINUTES` (12) for them all to be delivered:

| From | To | Crosses |
|---|---|---|
| small, Hillcrest | big, the first town's first house | server to server |
| big, first town | dims, Cinderholm Forge | server to server, into the Nether |
| dims, Voidreach | small, Riverside Doorstep | from the End to another server |
| small, Riverside | dims, Voidreach Shulker | another server into the End |
| dims, Brookfield | dims, Voidreach Outlook | Overworld to the End, over the void bridge |
| dims, Cinderholm | dims, Brookfield Mill | Nether to the Overworld |
| dims, Voidreach | dims, Cinderholm Brimstone | End to the Nether, across the lava |

Then it reports each letter's state (from `/postal track`) and, for each server, any Postal stack traces and
stuck-postman resets logged during the check. It fails if a letter wasn't delivered or a server threw.

Letters between servers go by each server's mail ship (`/postal network`): `setup` sets it to leave every
minute and take 30 seconds, so a cross-server letter takes a few minutes in all.

## The player check

`dev/network/network.sh player` joins a headless player (`ci/bot`, built with Maven on first use) through the
proxy. It needs a network set up with `--offline`, since the bot has no Mojang account. The player:

1. lands on **small**, and **dims** sees it online there (`/postal whois`);
2. addresses a written book to `dims:Cinderholm Ember` with `/addr` (Cinderholm is in the Nether), and the
   console drops it in Riverside's office chest;
3. moves to **dims** with `/server dims`: dims lists it, small doesn't, and big sees it online on dims;
4. waits on dims, checking `/postal track`, until the letter is delivered, and it's in Ember's mailbox in the
   Nether with its text;
5. moves to **big**, where `/postal track` shows the same record: handed to the network on small, delivered
   on dims.

## The Gate (optional)

[The Gate](https://www.spigotmc.org/resources/the-gate.70247/) is a Stargate plugin: players dial a gate from a
lectern and step through to another gate, on this server or, in its Bungee mode, on another one. It's here to try
the longer-term server setup, where players travel between servers by gate. Postal doesn't use it or know about
it; leave out `--with-gate` and nothing of it is installed.

`setup --with-gate`:
- downloads The Gate 0.2.8 (for Minecraft 1.21.1) and checks it against a pinned SHA-256 (`THEGATE_SHA256` in
  `ci/lib.sh`). It's closed source and last updated in August 2024, so a new upload stops setup rather than
  running unseen code;
- puts it on all three servers in Bungee mode: they share their gates through the network's MariaDB (tables
  `thegate_*` in `postal_net`), and The Gate moves players with the BungeeCord plugin channel, which Velocity
  answers (`bungee-plugin-message-channel = true`), so no BungeeCord or proxy plugin is needed;
- builds one gate per server at `-30 -60 30` in its Overworld, south-west of Central, before the routes are
  surveyed so postmen go round it: **big `1111111`, small `2222222`, dims `3333333`**.

`network.sh gate` checks each server: The Gate enabled, its database connected, Bungee mode under the server's
Velocity name, saving to MySQL, all three gates known, no stack traces. It ends with the dialing steps.

On Paper 26.1.2 The Gate warns at startup that it supports only 1.21.1, then runs. It reaches into Minecraft's
internals, which change between versions, so the animations or the teleport itself may misbehave on this
version even though everything above passes: dialing and stepping through are the parts to try in game.

Dialing (only a player can: The Gate dials from a GUI and has no dial command):
1. Go to the gate: `/tp @s -30 -60 26` on any server.
2. `/gatetools`, take the Gate Crystal (quartz), place a lectern near the gate and right-click it with the
   crystal: the lectern becomes the gate's DHD.
3. Right-click the lectern, type another server's address on the keypad and press the red button.
4. Once the horizon settles, walk through: the proxy moves you to that server, in front of its gate.

## What only a person can check

- Posting a letter by hand (the player check's console drops it in the chest), and reading it on another server.
- The compass (`/gps`) and route display in the Nether and the End.
- Watching a postman cross the lava bridge in Cinderholm and the void bridge in Voidreach.
- With `--with-gate`: dialing a gate and stepping through to another server.
