# Postal Revival — Project Plan (v3)

> **Status (October 2026).** The plan is kept as written below; the notes here record progress and decisions.
>
> - **Done:**
>   - §1–2 (the decompile is on `decompiled-original`; the API port is finished)
>   - the economy port (office NPC accounts, see `docs/economy.md`)
>   - the fork-regression sweep
>   - CI with a headless smoke test, and a local test server (`dev/`)
> - **Infrastructure changes from this plan:**
>   - GitHub (`runesage/va_postal`) instead of GitLab
>   - Maven (with `mvnw`) instead of Gradle
> - **Next:** persistent state, designed in `docs/design/persistent-state.md`. It has to support single-server
>   and Velocity builds without a rewrite.
> - **Decisions:**
>   - Postal is treated as a new mod, with no migration from v4.01 or fork worlds
>   - persistent state: SQLite for a single server, MySQL/MariaDB for networks; routes stay per server; Velocity
>     plugin-messaging notifications; drivers loaded through Paper `libraries:` (see the design doc §13)
>   - in-transit mail survives restarts and NPC deaths; the insurance fund covers grief only
>   - items never cross servers; the shared DB is the source of truth and the proxy carries only notifications
>   - no code is shared with the bank plugin; Postal exposes a generic "deliver this item" call
>   - no Vault bank API
> - **Open:**
>   - the licence (original CC BY-NC-SA 3.0; the fork's terms unchecked)
>   - whether joining a branch mailing list requires a mailbox there

**Goal:** Recover, understand, and port the abandoned `va_postal` Bukkit plugin to a modern Paper/Spigot API, preserving Citizens2 + Vault integration, giving the central post office real functional weight, and adding upgrade/ranking/mass-mail/cross-server features on top.

**Sources in scope:**
- Original: `va_postal v4.01` (dev.bukkit.org/projects/postal) — Spigot 1.12.2, package root `com.vodhanel.minecraft.va_postal`
- Fork: `Postal: Forwarded` — `github.com/ShadowJonathan/va_postal`, incomplete API port, known open bugs

---

## 0. Repo & Infra Setup

- New GitLab project on `binaryphoenix.net`, e.g. `postal-revival`
- Branches: `upstream-fork`, `decompiled-original` (reference only), `main`
- GitLab MCP (`@zereight/mcp-gitlab`, `--use-pipeline=true`)
- CI: Maven/Gradle build against Paper API
- Seed issue tracker from known fork bugs (Dynmap NPE, `/setroute` errors, route pathfinding failures, book-in-hand detection)

## 1. Decompile & Inventory

- Decompile v4.01 with Vineflower onto `decompiled-original`
- Structure map: packages, class responsibilities, `plugin.yml`
- Diff fork against decompiled original to see what's ported vs. untouched
- Deliverable: `MIGRATION_NOTES.md`

## 2. API Surface Audit

- Enumerate NMS imports, deprecated `Material`/`Chunk`/block-state calls, changed event constructors
- Cross-reference against current Citizens2 API and Vault `Economy` interface
- Deliverable: API compatibility matrix (old call → modern equivalent → risk level)

## 3. Central Office Redesign

Design: [`docs/design/central-office.md`](../design/central-office.md) (clerk, charters, rates, inspections, notice board, maps).

- Sets base postage rate; local offices discount only within an admin-defined band
- Congestion mechanic — heavy routing volume slows delivery server-wide, admin-tunable
- **Failure insurance fund** (not "expected loss") — pays out only on verified custody failures: griefed chest, NPC death mid-route, crash-interrupted transaction. Normal operation should never lose mail.
- **Persistent in-transit state** — shipments/letters survive server restarts and NPC death/despawn, recoverable on restart rather than defaulting to a claim payout. Treat this as a hard requirement, not a nice-to-have — the insurance fund is the fallback for the cases this can't cover (grief), not a substitute for it.
- Forced player-initiated visits: charter registration, lost-mail claims, rate disputes — all handled in person at central
- Central-initiated events: inspector NPC dispatches to under-performing/flagged branches; courier NPC delivers rate-change notices to every branch

## 4. New Feature Set

**Mailbox upgrades**
- Player-purchasable capacity tiers via Vault, straightforward economy sink, no new external dependency

**Scoped mass mailers**
- Three tiers of sender authority: admin (server-wide), Towny mayor (town-scoped, reuses existing mayor/staff Towny rank), branch owner (scoped to a branch's *registered* recipient list)
- Requires a new concept: branch subscriber/member registry, distinct from transactional account holders — decide early whether joining a branch's mailing list requires a mailbox there or is a separate opt-in
- Reuses existing `/distr` bulk-mail mechanic as the delivery backbone

**Ranking systems**
- Leaderboards: busiest local office, highest on-time delivery rate, lowest loss/grief incidents
- Shared stats-tracking module design (can be reused conceptually by the bank mod's leaderboards, though the two plugins stay fully independent — no code coupling, just a similar internal pattern)

**Bank statement delivery (cross-mod soft integration)**
- If the bank mod is installed, Postal can serve as its delivery mechanism for statements/reminders — a generic "deliver this book-item to this player" call, not a hard dependency. Postal has no awareness of bank internals; it just delivers an item it's handed.

## 5. Multi-Server Exchange (Currency + Letters Only)

Biggest architectural lift in this plan — scoped deliberately to avoid the hardest problems.

- Target **Velocity** (modern BungeeCord successor) as the proxy layer if you're running multiple backend servers
- **No physical item transfer across servers** — sidesteps cross-server inventory/item serialization entirely
- Authoritative state (letter records, delivery status) lives in a **shared backend database** (MySQL/MariaDB) that every server instance reads/writes directly — this is the source of truth, not the proxy
- Velocity plugin messaging channels used only for **lightweight cross-server notifications** ("you have mail waiting on Server B"), never for the actual data
- A letter addressed cross-server is written to the shared DB with a destination-server tag; the destination server's dispatcher picks it up on its own polling/tick cycle — no direct server-to-server delivery handoff needed

## 6. Subsystem Port (Claude Code agent breakdown)

| Agent | Scope |
|---|---|
| **build-foundations** | Gradle/Paper scaffold, `plugin.yml`, dependency wiring, CI green-build loop |
| **citizens-npc** | Postman/PostMaster route walking, dispatcher, inspector/courier NPC roles |
| **economy-vault** | `P_Economy` port, mailbox upgrade purchases |
| **world-state** | Mailboxes, post offices, packages, addressing/database, chunk manager, persistent in-transit state |
| **central-office** | Rate-setting, congestion mechanic, insurance fund, charter registration flow |
| **mass-mail** | Scoped mailer tiers (admin/mayor/branch), subscriber registry |
| **ranking** | Stats tracking, leaderboard generation |
| **multiserver** | Shared DB schema, Velocity notification layer, cross-server letter routing |
| **integrations** | Dynmap, Towny, WorldGuard, bank-mod soft integration — test last |
| **reviewer** | Reviews each port against the API compatibility matrix before MR |

**Recommended build order:** Vault/economy → world-state/addressing (with persistent state from the start, not bolted on later) → central office core → mass-mail → ranking → Citizens NPCs → multiserver → optional integrations. Multiserver deliberately comes late — it should be built against an already-stable single-server core, not developed in parallel with it.

## 7. Known Bug Triage

Bugs present in both original and fork are core logic bugs, not porting artifacts:
- `P_Dynmap` recurring `NullPointerException`
- Route pathfinding failures
- Dispatcher job-queue timeout/restart loop

## 8. Licensing

Original: CC BY-NC-SA 3.0. Fork: CC Attribution. Confirm exact fork license terms before deciding your revival's license.

---

## Suggested First Session

1. GitLab repo setup + import both source trees
2. API audit on Vault economy subsystem as pipeline proof of concept
3. Get that subsystem compiling clean before scaling up
