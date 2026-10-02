# Design: persistent mail state (single-server and Velocity)

**Status:** draft for review. **Plan reference:** `docs/plans/postal-revival-v3.md` §3 (persistent in-transit state)
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
- **Parcels never enter `IN_NETWORK`.** Item bytes are only safe within one server and one DataVersion, which
  matches the plan's "no physical item transfer across servers". A parcel addressed to another server is refused
  at `/package` time, with a clear message.
- **`MISSING`** comes from reconciliation (§7): the record says the mail is in chest X, but X no longer holds it.
  The insurance fund pays out from `MISSING` (plan §3). An admin can `RECOVERED`-rebuild the mail from its
  payload instead.

## 7. Reconciliation

Reconciliation runs at startup, on chunk load for chunks that hold postal chests, and periodically:

1. **For each record with custody `CHEST` on this server**, check that the chest holds a book with that
   `mail_id`. If the book is missing, decide by the latest event:
   - a move was in flight: finish it, re-materialising from the payload if needed;
   - nothing was in flight: mark the record `MISSING`, because the chest was griefed.
2. **For each postal book found in a chest with no matching record (or with no `mail_id`)**, it's a legacy v4
   or fork book, or a forgery:
   - legacy books are imported once (a best-effort parse of the old pages) during the migration;
   - after that, untracked books are left in place and flagged, never routed.
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

## 10. Migration from v4 and fork worlds

The first start with the store enabled runs a one-time import:

1. Scan every configured office and address chest for postal books.
2. For each book, create a record from its stamped pages (best effort), assign a `mail_id` and write it to the
   PDC.
3. Parcels in transit get their lossy contents imported as-is, and the record is marked `import_lossy`.

The import is idempotent: books that already have a `mail_id` are skipped. A dry-run command reports what it
would import.

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
| **P1** | `MailStore`/SQLite, schema and migrations, `mail_id` PDC, ledger-first moves for **letters**, reconciliation, `route_run` resume, legacy import | Restart-safe and crash-safe letters; the foundation for everything else |
| **P2** | Parcels on full `ItemStack` payloads, COD and postage settled against records, `MISSING` → claim hooks | Fixes parcel item loss; the insurance fund can be built |
| **P3** | `directory_*` tables, MySQL backend and parity CI, `server-id` | Network-ready storage while still running one server |
| **P4** | `ProxyBus`, the Velocity relay plugin, `IN_NETWORK` claiming, a two-server CI | Cross-server letters |

P1–P3 change nothing for players on a single server beyond reliability. P4 is only switched on by config.

## 13. Decisions to confirm

1. **SQLite as the single-server default.** Flat files would mean a second implementation and a rewrite for
   MySQL; SQLite shares the SQL.
2. **Letters cross servers; parcels don't.** Item bytes aren't portable across servers and versions. This is
   the plan's "no physical item transfer", applied strictly.
3. **Routes stay in `config.yml`.** Only the directory (offices and addresses) is shared.
4. **Plugin-messaging bus first, Redis optional later.** Polling guarantees correctness either way.
5. **Driver loading through Paper's `libraries:`**, rather than shading into the jar.
6. **The legacy import parses old pages best-effort and marks lossy parcels.** The alternative is a clean
   start that leaves old books where they are.
