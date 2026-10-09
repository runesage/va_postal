# Design: persistent mail state (single-server and Velocity)

**Status:** P1 and P2 implemented (letters and parcels on SQLite; see §14 and §15 for where they differ from this design). **Plan reference:** `docs/plans/postal-revival-v3.md` §3 (persistent in-transit state)
and §5 (multi-server exchange).

## 1. Goals

1. **Mail survives everything except grief.** A letter or parcel survives a restart, a crash between two
   inventory writes, a postman despawning or dying, and an unloaded chunk. When mail is lost, it's only because
   someone destroyed a chest. That case is detectable, and it's what the insurance fund pays for.
2. **One codebase for single-server and network builds.** Moving from a single server to Velocity means changing
   config (`storage: mysql`, a `server-id`, enabling the proxy bus), not rewriting the mail system.
3. **A foundation for later features.** The central office (insurance claims, congestion), leaderboards
   (on-time rate, grief incidents) and mass mail all need mail to be a tracked record with a history. That
   record is designed here.
4. **No dupes.** No sequence of crashes, races or concurrent servers can copy a letter or parcel.

**Non-goals:**
- Moving items across servers. That stays out of scope as the plan decided. Letters cross servers as records,
  not items; parcels stay on the server they were posted on (§6).
- Moving the economy to the database. Office balances stay in the server economy (`docs/economy.md`).

## 2. How mail works today, and why it loses things

A letter is a written book with routing data stamped into its pages. The plugin moves it between chest
inventories: the sender's mailbox → branch → Central → destination branch → the address's mailbox. NPCs only
*appear* to carry it, because the move happens in one inventory write when they arrive. Parcels serialize the
chest's contents into the shipping-label book and delete the original chest.

| Failure | What happens today |
|---|---|
| Crash between "add to the next chest" and "remove from this chest" | The mail is duplicated, or with the other order, lost |
| Chest broken, or its chunk regenerated | The mail is gone and nothing records that it existed |
| A parcel with enchanted or named items | Only type, amount and damage survive (the book holds `MATERIAL,qty,id,damage`) |
| A player edits a stamped page or forges a book | Routing and COD data are trusted as written |
| "Where is my mail?" or a lost-mail claim | Can't be answered: no record and no history |

## 3. Core idea: the ledger is the source of truth; chests are views

Every piece of mail gets a **record** in a mail store, with a stable `mail_id` (UUID). The physical book in a
chest carries only that ID, in its PersistentDataContainer (`postal:mail_id`), plus readable pages for the
player. Everything authoritative lives in the store: where the mail is, its state, its contents, its sender,
COD and postage.

- **Move = record first, materialise second.** To move mail from chest A to chest B, Postal:
  1. commits a state transition in the store (`AT_BRANCH@A → OUT_FOR_DELIVERY`), then
  2. updates the world (remove from A, add to B), then
  3. commits a second transition (`→ DELIVERED@B`).

  If the server dies between steps, reconciliation (§7) finishes or rolls back the move from the record. A move
  can never dupe or lose mail.
- **Chests are caches.** On demand, the physical book for any record can be rebuilt from the record's payload
  (§5). That is how griefed or crashed mail is recovered, and how a letter from another server appears in a
  local chest.
- **NPCs never hold custody.** A postman walking a route is a record in `OUT_FOR_DELIVERY` with a `route_run_id`.
  If he dies or despawns, the record is unaffected. The run is resumed or requeued.

## 4. Architecture

```
            ┌───────────────────────── Postal plugin (every server) ─────────────────────────┐
 commands,  │  MailService  (business rules: post, route, deliver, return, refuse, claim)       │
 listeners, │     │                    │                          │                            │
 dispatcher │     ▼                    ▼                          ▼                            │
            │  MailStore           NotificationBus           WorldBridge                      │
            │  (interface)         (interface)               (main-thread chest/book I/O)     │
            │   ├ SqlMailStore      ├ LocalBus (in-process)                                    │
            │   │  ├ SQLite         └ ProxyBus (Velocity plugin messaging)                     │
            │   │  └ MySQL/MariaDB                                                             │
            └─────────────────────────────────────────────────────────────────────────────────┘
```

- **`MailStore`** is the only code that touches the database. There is **one implementation over JDBC**,
  `SqlMailStore`. It is configured for SQLite (single server, the default; one file in the plugin folder) or
  MySQL/MariaDB (shared across servers). The schema and queries are written in the common subset of both
  dialects, so switching backends is a config change.
- **`NotificationBus`** carries *hints* only ("mail arrived for player X", "server Y has inbound mail"). It never
  carries data. `LocalBus` dispatches in-process; `ProxyBus` uses Velocity plugin messaging. Losing a message
  never loses mail, because the store is always polled (§8).
- **`WorldBridge`** owns everything that must run on the main thread: reading and writing chest inventories,
  building book `ItemStack`s from records, and reading `postal:mail_id`.
- **`MailService`** holds the rules. It talks only to these three interfaces. That keeps it unit-testable with
  an in-memory SQLite store and a fake world.

**Threading:** database I/O runs on a small async executor (HikariCP pool). World changes are hopped back to the
main thread. A move is a `CompletableFuture` chain of store transition (async) → world update (main thread) →
store commit (async). The main thread never blocks on the database.

## 5. Data model

The tables are dialect-neutral: UUIDs as `CHAR(36)`, timestamps as epoch-millis `BIGINT`, payloads as `BLOB`/
`LONGBLOB`, and no vendor-specific types. Migrations are numbered SQL files run by a tiny built-in runner
(a `schema_version` table), so they're identical on both backends.

**`mail`**: one row per letter, parcel or distribution copy

| Column | Notes |
|---|---|
| `mail_id` CHAR(36) PK | Also stored in the book's PDC |
| `kind` | `LETTER`, `PARCEL`, `DISTRIBUTION` |
| `state` | See §6 |
| `version` INT | Optimistic lock. Every transition is `UPDATE … WHERE mail_id=? AND version=?` |
| `origin_server`, `dest_server` | `server-id`s. Equal on a single server |
| `origin_office`, `dest_office`, `dest_address` | Postal names, normalised to lowercase |
| `custody_server`, `custody_kind`, `custody_ref` | Where it physically is: `CHEST` + location key, `ROUTE` + run id, or `NONE` (not yet materialised on this server) |
| `sender_uuid`, `attention_uuid` | Replace the fork's page lines 13/14 |
| `cod_amount`, `postage_paid` | DECIMAL. Settled through `docs/economy.md` |
| `payload_format`, `payload` | `LETTER_V1` (JSON: title, author, pages as text components) or `PARCEL_ITEMS_V1` (Paper `ItemStack.serializeAsBytes` list) |
| `mc_data_version` | The server's DataVersion when the payload was written. Parcels never load on a different one (§6) |
| `created_at`, `updated_at`, `due_at` | `due_at` feeds on-time stats and congestion |

**`mail_event`**: append-only history

`(event_id, mail_id, at, server_id, from_state, to_state, actor_kind, actor_ref, detail)`. Every transition writes
one row in the same transaction as the `mail` update. This history is the basis for lost-mail claims, the
insurance fund, leaderboards and `/postal track <id>`.

**`directory_office` / `directory_address`**: the network-wide address book

`(server_id, office, address, owner_uuid, open, location_key)`. A letter on server A can be addressed to an office
on server B only if B has published it here. **Routes stay in each server's `config.yml`**, because they are
server-local geometry. Only the directory is shared. Each server republishes its own rows on start and when
offices or addresses change.

**`route_run`**: a postman run in progress

`(run_id, server_id, office, address, npc_ref, started_at, waypoint, direction)`. The dispatcher uses it to resume
or requeue after a restart or an NPC death. Mail on the run points at it through `custody_ref`.

## 6. State machine

```
POSTED ──▶ AT_ORIGIN_BRANCH ──▶ AT_CENTRAL ──┬─▶ AT_DEST_BRANCH ──▶ OUT_FOR_DELIVERY ──▶ DELIVERED
  (sender's  (branch chest)    (central chest)│        ▲                    │
   mailbox)                                   │        │                    └─▶ (route aborted) ─▶ AT_DEST_BRANCH
                                              └─▶ IN_NETWORK ──(dest server claims)──┘
 any state ──▶ RETURNED / REFUSED / EXPIRED          (letters only; dest_server ≠ origin)
 any physical state ──▶ MISSING ──▶ CLAIMED | RECOVERED
```

- **`IN_NETWORK`** is the only cross-server step. The origin server's Central sets it, and the destination
  server's dispatcher claims it with a CAS update (`WHERE state='IN_NETWORK' AND dest_server=?`). On a single
  server the transition is never taken, so the same code runs with no branches.
- **Items never cross servers (a hard rule, decided).** Only `LETTER` records can enter `IN_NETWORK`. A letter
  crosses as text, and the destination builds a fresh book from it. Parcels, COD and anything carrying items stay
  on their origin server. The rule is enforced at every layer, not just in the UI:
  1. `/package` and `/addr` refuse a destination on another server.
  2. `MailService` refuses to route any non-letter to `IN_NETWORK`.
  3. The store's claim query only matches `kind = 'LETTER'`.
  4. A database `CHECK` constraint, `state <> 'IN_NETWORK' OR kind = 'LETTER'`, rejects it even if code is wrong.
  5. The letter payload format (`LETTER_V1`) has no item fields at all.

  Tests assert each layer independently.
- **`MISSING`** comes from reconciliation (§7): the record says the mail is in chest X, but X no longer holds it.
  The insurance fund pays out from `MISSING` (plan §3). An admin can `RECOVERED`-rebuild the mail from its
  payload instead.

## 7. Reconciliation

Reconciliation runs at startup, on chunk load for chunks that hold postal chests, and periodically:

1. **For each record with custody `CHEST` on this server**, check that the chest holds a book with that
   `mail_id`. If the book is missing, decide by the latest event:
   - a move was in flight: finish it, re-materialising from the payload if needed;
   - nothing was in flight: mark the record `MISSING`, because the chest was griefed.
2. **For each postal-looking book found in a chest with no matching record (or with no `mail_id`)**, it was
   forged or hand-made, because every real piece of mail is created with a record. It's left in place, flagged
   to admins, and never routed.
3. **For each `route_run` with no live postman**, return its mail to `AT_DEST_BRANCH` and requeue the run.

Because every move is "transition, world, commit", step 1 can always tell an interrupted move from a theft.

## 8. Single-server vs Velocity: what changes

| Aspect | Single server (default) | Velocity network |
|---|---|---|
| `storage.type` | `sqlite` (file `plugins/Postal/postal.db`) | `mysql` (shared database) |
| `network.server-id` | `main` (default) | A unique ID per backend, required |
| `NotificationBus` | `LocalBus` | `ProxyBus` |
| Cross-server letters | n/a (the transition is never taken) | `IN_NETWORK` → claimed by the destination |
| Polling | Not needed; local moves are synchronous | Each dispatcher polls `IN_NETWORK` for its `server-id` every N seconds |
| Directory | Written and read locally | Each server publishes its own offices and reads everyone's |
| Code paths | Identical | Identical |

**Why the proxy bus is only a hint channel:** Bukkit plugin messages travel through a connected player. A backend
with nobody online can't receive them. So delivery correctness rests on polling the store, and messages only
make it faster ("you have mail" pings, waking a dispatcher early). An optional Redis bus can be added later
behind the same interface.

**A Velocity companion plugin** (a separate small module) relays `postal:notify` messages between backends. It
holds no state and no database access.

## 9. Configuration

```yaml
Storage:
  Type: sqlite            # sqlite | mysql
  Mysql: { Host: localhost, Port: 3306, Database: postal, User: postal, Password: '' , Pool_size: 6 }
Network:
  Server_id: main         # must be unique per backend once Type is mysql
  Proxy_bus: false        # enable Velocity notifications
  Poll_seconds: 10
```

**Drivers:** HikariCP, the SQLite JDBC driver and the MariaDB driver load through Paper's `libraries:` in
`plugin.yml`, downloaded from Maven Central at startup, so they aren't shaded into the jar.

## 10. No migration: a new mod

There is no import or upgrade path from v4.01 or fork worlds (decided). Postal is treated as a new mod: the
schema starts at version 1 with no import tooling. Old postal books in a world are ordinary books. As a
follow-up, the v4-compatibility shims added during the port can be removed (done in P1):
- the `owner.name` → UUID conversion;
- pre-1.13 material names on parcels;
- legacy armor and highlight IDs in config;
- legacy chest-direction bytes on labels;
- the v4 note in `docs/economy.md`.

## 11. Testing

- **Unit tests:** `MailService` tested against in-memory SQLite and a fake `WorldBridge`. Covers every state
  transition, CAS conflicts (two claimers, only one wins) and crash points (the world update without the
  commit, and vice versa).
- **Backend parity:** the same `MailStore` test suite runs against MySQL in CI (Testcontainers, a MariaDB
  service), so both dialects stay identical.
- **Smoke tests** (`ci/smoke-test.sh`), new phases:
  - kill the server with `kill -9` mid-route, restart, and assert the mail is delivered exactly once;
  - break the address chest, and assert the record goes `MISSING`;
  - post a parcel holding an enchanted, renamed item and assert it arrives intact.
- **Two-server CI** (with phase P4): two Paper backends on one MariaDB, a letter from A to B, and an assert that
  it's delivered on B and that A's record shows the history.

## 12. Phases

| Phase | Scope | Unlocks |
|---|---|---|
| **P1** | `MailStore`/SQLite, schema and migrations, `mail_id` PDC, ledger-first moves for **letters**, reconciliation, `route_run` resume, removal of the v4-compatibility shims | Restart-safe and crash-safe letters; the foundation for everything else |
| **P2** | Parcels on full `ItemStack` payloads, COD and postage settled against records, `MISSING` → claim hooks | Fixes parcel item loss; the insurance fund can be built |
| **P3** | `directory_*` tables, MySQL backend and parity CI, `server-id` (built: §16) | Network-ready storage while still running one server |
| **P4** | `IN_NETWORK` claiming, network players, cross-server addressing, a two-server test (built: §17; the proxy bus deferred) | Cross-server letters |

P1–P3 change nothing for players on a single server beyond reliability. P4 is only switched on by config.

## 13. Decisions

All are decided as of October 2026.

1. **SQLite is the single-server default.** MySQL/MariaDB is used for networks, with the same schema and
   queries.
2. **Letters can cross servers; items and parcels never can.** This is a hard rule, enforced at every layer
   (§6).
3. **Routes stay in each server's `config.yml`.** Only the directory (offices and addresses) is shared.
4. **Velocity plugin messaging for notifications**, through a small stateless relay plugin on the proxy. Polling
   the store guarantees correctness, and Redis can be added later behind the same `NotificationBus`.
5. **Database drivers load through Paper's `libraries:`** in `plugin.yml` (downloaded from Maven Central on
   first start), not shaded into the jar.
6. **A new mod, with no migration** from v4.01 or fork worlds (§10).

## 14. P1 as built

P1 follows this design, with these differences:

- **Synchronous store calls.** SQLite is called on the main thread (one pooled connection, WAL,
  `synchronous=NORMAL`); each call is a single small transaction. The async pipeline arrives with MySQL in P3,
  where network latency makes it necessary.
- **Payload captured at posting.** A letter's `LETTER_V1` payload (title, author, pages) is stored when it's
  addressed, so reconciliation can rebuild a lost book from the record.
- **Custody on events.** `mail_event` rows carry the custody they moved to, so the previous chest is known
  from history.
- **Roll-forward.** The store commits at once but the world saves every few minutes, so after a crash the world
  can be behind the store. When a letter isn't in the chest its record names, reconciliation looks in the chest
  it was in before and moves that copy forward. It only ever moves an existing book, so it can't duplicate
  one. Recently delivered letters (the last hour) are checked the same way.
- **Admin commands.** `/postal track <id | recent>` shows a letter's record and history;
  `/postal testletter <from> <to> <address>` hands in a tracked letter without a player (used by the smoke
  test).
- **Parcels stayed untracked** in P1: only letters got a `mail_id` (parcels followed in P2, below).

## 16. P3 as built

- **MySQL/MariaDB.** `Storage.Type: mysql` opens the same `SqlMailStore` over the MariaDB driver (loaded through
  `plugin.yml` `libraries:`, like SQLite's). Settings: `Storage.Mysql.Host`, `Port`, `Database`, `User`,
  `Password`, `Pool_size` (default 6) and `Properties` (extra JDBC parameters, e.g. `useSsl=true`).
- **Outages.** Connections time out after 1 second, and a lost connection trips a circuit breaker: store calls
  then fail at once (mail stays where it is, and new mail travels untracked, as when the store can't open)
  until a background check every 5 seconds finds the database again. Measured by killing MariaDB for 70
  seconds mid-run: TPS stayed at 19-20, a letter waiting at the office was delivered on the first round after
  the store came back, and the only stall was the first call after the database died (one connection
  timeout).
- **Dialect variants.** The schema stays in the shared subset. A migration may have a variant
  (`V3__wide_payload.mysql.sql` beside `V3__wide_payload.sql`) where the two can't agree: schema 3 widens the
  parcel payload to `LONGBLOB` on MySQL, where a `BLOB` holds only 64 KB (SQLite has no such limit).
- **Directory** (schema 4). `directory_office` and `directory_address`, as in §5 (`is_open`, `is_central`,
  `location_key`). Each server republishes its own rows a few seconds after start and then every minute if
  they changed, in one transaction (delete its rows, insert the new ones). P4 reads it to address mail to
  another server; `/postal directory` shows it.
- **Server registry** (schema 4, `postal_server`). Every server records a heartbeat each minute. If another
  instance has written the same server id since this one last did, two live servers share a
  `Network.Server_id`: the log says so (severe) every minute, and `/postal store` shows it. A server's first
  heartbeat takes the row over, so a restart or a crashed server's leftover row is no false alarm.
- **Store health.** Every store call is timed (`StoreStats`, a proxy around the store): `/postal store` shows the
  backend, schema, call rate, average and slowest call, main-thread time, failures and the registered servers.
- **Still synchronous.** Store calls stay on the calling thread, as in P1, rather than the async pipeline of §4.
  Measured on the SMP load test (12 towns, 240 addresses, 15 minutes) with MariaDB on the same machine: 909
  calls averaging 1.04 ms, 0.9 seconds of main-thread time in all, TPS at least 18.9, the same as on SQLite. A remote database adds its round trip to every call; `/postal store` shows what that costs. The
  async pipeline remains the plan if real networks need it.
- **Parity tests.** The store tests are one contract suite (`MailStoreContract`) run on SQLite and on
  MariaDB (`MariaDbMailStoreTest`, enabled by `POSTAL_TEST_MYSQL_URL`). CI's build job has a MariaDB service, and
  the smoke test runs twice: on SQLite and on MariaDB (`STORAGE=mysql`).

## 17. P4 as built

Cross-server letters, for servers behind a Velocity proxy that share one MySQL/MariaDB mail store. Decided with the
owner: **only letters cross; parcels, COD and items never do**; a letter always goes to a mailbox but can be marked
for any player's attention, whatever server they're on and whether or not they're online; the origin keeps the
postage, at a higher network rate; offices are addressed by name, or `server:office` when the name is ambiguous;
and there are **no duplicate or divergent records**.

- **One row crosses.** A letter for another server has `dest_server` set when it's addressed. It travels this
  server's normal route to Central (office pickup, `AT_CENTRAL`), then:
  1. **Hand-off (origin), on the mail ship's schedule.** Every `Network.Poll_seconds` (default 10) each server
     reads, off the main thread, which letters at its Central are bound elsewhere (`outbound`) and which are on
     their way to it (`in_network`). Letters leave only at a **departure**: every `Network.Departure_minutes`
     (default 10), counted on the clock from the epoch, so every server agrees when the ship sails without
     talking to the others. At a departure the origin settles each letter's postage, then moves it to
     `IN_NETWORK` with the usual two-phase move: begin (custody `NONE`), take the book out of the Central chest,
     and `depart`, which commits with the letter's arrival time (`due_at` = the departure +
     `Network.Transit_minutes`, default 5). Nothing of the letter stays on the origin but its record.
  2. **Claim (destination), when the trip is over.** The destination claims a waiting letter once its `due_at` has
     passed. It claims each
     with one version-checked update that only matches `kind = 'LETTER' AND state = 'IN_NETWORK' AND
     dest_server = <this server>`, so of two claimers only one wins. The claim makes the record this server's
     (`custody_server`) with a move in flight to `AT_CENTRAL` in its Central chest. It then rebuilds the book
     from the record's `LETTER_V1` text, titled with the local office's name so Central routes it as usual,
     and commits. From there the destination's Central and postman deliver it like any other letter.
  The mail id never changes, so `/postal track` on either server shows the whole history, with the server
  that made each step. Reconciliation finishes an interrupted hand-off (the book is taken out of Central and the
  move committed: the record carries the letter) and an interrupted claim (the book is rebuilt at the
  destination, as for any move in flight).
- **The mail ship (RP).** Decided with the owner: town routing stays as it was, and the network gets the
  flavour. The vehicle has a name (`Network.Vehicle`, "the mail ship"). A departure is announced to the server
  ("The mail ship departs for creative with 3 letters; it arrives in 5 min."), as is an arrival ("The mail ship
  from survival has arrived with 2 letters."), unless `Network.Broadcast` is false, when only the log gets them.
  Each is marked by a sound at the Central chest (`Network.Sound`, `block.bell.use`; empty for none).
  `/postal network`, for everyone, shows the schedule, the next departure, the letters waiting at Central for it,
  what's on its way here, and the other servers. A letter that reaches Central just after a departure waits
  for the next one. That's deliberate: it's a schedule.
- **The Central Dispatcher.** A character carries the mail between Central and the ship, so the transfer is something players
  see. At a departure the Central Dispatcher appears a few dozen blocks from Central (`Network.Dispatcher.Distance`, 24), walks to
  the Central chest, opens it, takes the outbound letters in a mailbag and says so to anyone nearby ("All aboard
  for creative! 3 letters for the voyage."), closes the chest and walks off. The bell rings as the ship sails.
  At an arrival they walk in carrying the bag, ring the bell, leave the letters in the chest ("Mail from survival!
  2 letters off the ship.") and walk off empty-handed. There is no dock to build: they come from, and go to, a
  free spot near Central. The name (`Network.Dispatcher.Name`, "&3Central Dispatcher"), the lines (`Network.Dispatcher.Lines.*`),
  the skin (`Settings.Skin.Dispatcher`, the postmaster's bundled skin by default, or `custom`/a player name like the
  others) and the uniform (`Settings.Uniform.Dispatcher.*`: a postal-green cap and coat with the navy trousers and black boots the
  postman and postmaster wear, so they're plainly Post Office staff but neither of the other two; on unless
  `Network.Dispatcher.Uniform` is false) are all configurable. The **transfer itself happens when the Central Dispatcher reaches the chest**: the letters'
  records and books move then. The Central Dispatcher can only make it late, never stop it. With nobody within 48 blocks of
  Central to see it, without Citizens, with `Network.Dispatcher.Enabled: false`, or if anything goes wrong (a walk
  past 30 seconds ends in a teleport, an unloaded chunk, a shutdown), the transfer runs at once. One voyage runs at
  a time; a departure that comes up while the Central Dispatcher is out keeps its slot and goes when they're back. Like the
  parcel courier, the NPC is never saved by Citizens. (In code it's `CentralDispatcher`, apart from
  `VA_Dispatcher`, the scheduler that sends postmen on their rounds.) `Network.Dispatcher.Always` brings them even with nobody
  watching (the network test uses it).
- **Letters only, at every layer** (§6): `/package` refuses another server's office, and `addr_worker` refuses
  a parcel for one; the store refuses to move a non-letter into `IN_NETWORK` or to claim one; the claim query
  matches only `LETTER`; the `CHECK` constraint rejects it in the database; and `LETTER_V1` has no item fields.
  A letter for another server must be tracked: if the store can't record it, it isn't sent (and its postage is
  refunded), since an untracked book could never leave Central.
- **Addressing.** Each server keeps a copy of the directory (§16), refreshed every 30 seconds. A plain office name
  resolves to one of this server's offices first (as before: an exact name or a unique part of one), then to
  another server's office if exactly one server has it. `server:office` picks one explicitly, and when several
  servers have the name, `/addr` lists the `server:office` choices. Addresses at another server's office complete
  from its published addresses. The book's title is `server:office` until it reaches its server.
- **Players across servers** (schema 5, `network_player`): one row per player UUID, with their name, the
  server they were last on, whether they're online, and when last seen. Each server records joins and quits
  (a quit only counts if the player is still recorded on that server, since switching servers can record
  the join first), marks its players offline when it stops, and re-records who is online when it starts. `/addr`,
  `/att` and `/package` find the player for `[player]` online here first, then in this table (most recently
  seen first, so a name that changed hands finds the current owner), then among players who have been on this
  server. An unknown name is now refused, instead of being silently replaced with `[Resident]`. The UUID is
  written on the letter (page 1) and its record. `/postal whois <player>` shows what the network knows.
- **Postage.** A letter to another server holds `Economy.Postage.Letter.Network` (default 10, against 6 out of
  town) and is settled when it leaves the origin's Central, ½ Central and ½ the sending office, since no money
  crosses servers. A letter rebuilt on the destination carries no hold, so its delivery settles nothing there.
- **Fixes found on the way.** A postman delivered any book in his office chest whose address matched his run,
  including another town's mail waiting there for Central if it had a same-named address (and now another
  server's). He now also checks the book is for his office. `by_destination` also checks `dest_server`, so a
  letter waiting to leave can't be marked out for delivery by a namesake office.
- **Not built: the proxy bus.** Notifications through a Velocity relay plugin (§8) would only make delivery quicker,
  and the mail ship's schedule makes the trip take minutes on purpose; polling makes it correct. It stays an option behind the same design, as does a "you have mail" ping to a player online on
  another server.
- **Undeliverable network mail** (an office removed from the destination after the letter was addressed) is claimed
  and waits in the destination's Central chest, with a warning in its log, like a letter for a deleted local office.
- **Tests.** The contract suite gains the cross-server cases on both backends: one row crossing with its
  history, two claimers with one winner, only the addressed server claiming, a local letter never entering the
  network, a parcel never outbound or claimable, local queries ignoring outbound letters, and one player row
  across servers (switching, renaming, stopping). `ci/network-test.sh` runs two Paper servers on one MariaDB, each
  with a Testville/Home, and sends a letter each way at the same time, with 1-minute departures, a 30-second trip
  and the Central Dispatcher forced on. Each letter was delivered once, to the other server's Home and not the sender's
  namesake; each record carries both servers' history (posted, Central and `IN_NETWORK` on the origin; claimed,
  Central, postman and `DELIVERED` on the destination); both ships and both Central Dispatchers announced themselves; and
  the trip took about 2.5 minutes from posting to the mailbox, most of it the schedule and the postman's round.
- **Found by the network test.** Two servers starting together on an empty MySQL database raced to create the schema,
  and one failed to open its store (MySQL commits DDL at once, so the migration transaction couldn't separate
  them). Migrations now hold a named lock (`GET_LOCK('postal_migrate')`), and the contract suite opens three
  stores at once on a fresh database. Also: a server that had just started couldn't address another server's
  office for up to 30 seconds (its directory copy predated the other's publish); a name that isn't in the copy
  now re-reads the directory on the spot, at most every 3 seconds.

## 15. P2 as built

- **Parcels are records.** At `/package` the chest's items go into the parcel's record (`PARCEL_V1`: the label
  as text, the chest's position and facing, and each item's `ItemStack.serializeAsBytes()` with its slot), so
  enchantments, names, lore and everything else survive. The chest is emptied at once, so nothing can be
  duplicated while the label travels, but it stays where it was, locked by its `[Postal_Ship]` sign, for
  immersion.
- **A courier collects the chest.** When the label is posted (left in a mailbox or a post office chest), or at
  its first pickup if that comes first, if a player is within 40 blocks, a postal
  courier NPC walks up to the chest, picks it up and walks off (at most three at once); otherwise the chest is
  simply removed. Anything a hopper pushed in meanwhile is dropped, not lost. The courier is never saved by
  Citizens, and every failure path still removes the chest.
- **The label travels like a letter**, through the same tracked moves, reconciliation and stale-copy removal.
- **Exactly once.** `/accept`, `/refuse` and `/package cancel` are each one version-checked transition
  (`DELIVERED` → `ACCEPTED`/`REFUSED`, `POSTED` → `RETURNED`), made before any items are handed over, so a
  parcel's items come out once. A copied label (crafting copies the book's data, mail id included) is the
  same parcel: whichever label goes first gets the items, and the other is refused. The record, not the label's text, decides what a label can do, so a label rebuilt from its record
  works.
- **COD and postage on the record.** The COD amount is recorded on the parcel (`/accept` charges what the
  record says), and every letter and parcel records its postage hold (`hold_id`, schema V2). A book rebuilt
  from its record gets its hold back, and delivery settles from the record if a book's hold tag was lost: the
  gap left in P1.
- **A crash right after packing.** If the world is rolled back past a `/package` (the chest full again) while
  the label survived (say the player logged out first), the parcel would be duplicated. The chest carries a
  `postal:packed` mark from packing; at the label's first pickup, a chest that has items and no mark means a
  rollback, so the parcel is cancelled (`RETURNED`, postage refunded), the label is never routed, and the chest
  is unlocked with the real items in it.
- **Items removed from the game.** Paper upgrades an item's stored data when it's read back, so renamed ids
  and changed components carry over between Minecraft versions. If an item no longer exists at all, the parcel
  is still opened (accepted, refused, cancelled or recovered) with everything else in it. Whoever opens it is
  told which item was left out (its old id is read from the stored bytes), and the log and the parcel's history
  note it too. The record keeps the item's data, so nothing is erased. `/postal track` lists such an item as
  "no longer in the game".
- **`/package cancel`** (an unposted label in hand) puts the items back in the chest they were packed in
  (unlocking it), or a new chest in front of the sender, and refunds the postage.
- **Claim hook.** `MailMissingEvent` (Bukkit event, `com.vodhanel.minecraft.va_postal.api`) fires whenever a
  letter or parcel is marked `MISSING`, with its id, kind, sender, destination, last place and COD amount: the
  hook for lost-mail claims and an insurance fund. `/postal recover <id>` rebuilds lost mail from its record and
  closes it as `RECOVERED`, so the original (if it turns up) is never routed or accepted.
- **Re-addressing a shipping label** is refused (the destination is part of the record); cancel and package
  again.
- **Test helpers** (admin): `/postal testparcel` (with `retired` for an item gone from the game), `/postal accept|refuse <id>`, `/postal recover <id>`,
  `/postal setstate <id> <state>`; `last` stands for the newest mail id.
- **Fixed from P1:** a letter already `MISSING` was marked `MISSING` again on every reconciliation pass.
