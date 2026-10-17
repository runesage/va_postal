# Test plan: persistent mail state, phase P4 (letters between servers)

What P4 promises, and what this plan checks:
- **A letter can go to another server's office**, by name or `server:office`, and is delivered there exactly once.
- **Only letters cross.** Parcels and items never leave the server they were packed on.
- **Any player can be named** on a letter: online or offline, on any server.
- **The origin keeps the postage**, at the network rate.
- **The mail ship** leaves on a schedule and announces departures and arrivals.
- **One record, no copies.** `/postal track` shows the same letter, with its whole history, on both servers.

**Most of this is covered automatically.** The store tests cover the claim race, letters-only, and one player row
across servers, on SQLite and MariaDB. `ci/network-test.sh` runs two Paper servers on one MariaDB, each with its
own Testville/Home, and sends a letter each way at once.

What the automated tests can't cover:
- real players moving between servers through Velocity;
- the `/addr` conversation, with its confirmations and messages;
- your economy.

- **Running a single server?** Nothing here needs testing. P4 changes nothing until a second server shares the
  database. The one exception is `[player]` on `/addr`: an unknown name is now refused rather than silently
  becoming `[Resident]` (step 3.3).
- **Running a network?** Do sections 1 to 4, about 20 minutes.

## 0. Setup

You need two Paper servers behind a Velocity proxy (or two servers you join directly), sharing one MySQL/MariaDB
database. Set each server's `config.yml` as follows:

```yaml
Storage:
  Type: 'mysql'
  Mysql: { Host: ..., Database: 'postal', User: ..., Password: ... }   # the same on both
Network:
  Server_id: 'survival'      # different on each server, e.g. 'survival' and 'creative'
  Poll_seconds: 10
  Departure_minutes: 2       # 10 by default; shorter for testing
  Transit_minutes: 1         # 5 by default
  Vehicle: 'the mail ship'
```

Each server needs a Central and at least one office with an address. Give both servers an office with the
**same name**, e.g. both have Testville. Below, *A* is the server you write on and *B* is the other.

## 1. The network sees both servers

1. On A, `/postal store`.
- [ ] It shows both servers, and `Failures: 0`.
2. On A, `/postal directory`.
- [ ] B's offices are listed, as `B / office: N addresses`.
3. Join B, then on A run `/postal whois <your name>`.
- [ ] It says you're online on B. Leave B and run it again.
- [ ] Offline, last seen on B.

## 2. A letter to another server

1. On A, write and sign a book. Then run `/addr B:Testville Home <a friend's name>`, where the friend is offline
   or on B.
- [ ] The confirmation reads `B:Testville, Home, <friend> (online on B)` (or `last seen …`).
- [ ] It says the letter goes to another server, and that postage is the network rate.
2. Confirm with `/`, and post it in a mailbox or at the post office. Once it reaches A's Central, run
   `/postal network` on A.
- [ ] It shows the next departure and `1 letter waiting at Central (B: 1)`.
- [ ] At the departure, standing near A's Central: the **Central Dispatcher** walks in from a few dozen blocks away, opens the
  chest, takes the letter (now holding a mailbag), says `All aboard for B! 1 letter for the voyage.`, closes
  the chest and walks off. A bell rings as they leave. A broadcasts `The mail ship departs for B with 1 letter;
  it arrives in 1 min.`
- [ ] A minute later, standing near B's Central: the Central Dispatcher walks in with the bag, a bell rings, they leave the
  letter in the chest (`Mail from A! 1 letter off the ship.`) and walk off empty-handed. B broadcasts `The mail
  ship from A has arrived with 1 letter.`
- [ ] The Central Dispatcher wears a postal-green cap and coat with navy trousers and black boots: clearly Post Office staff,
  and distinguishable from both the postman (light-blue shirt) and the postmaster (maroon).
- [ ] Away from Central (more than 48 blocks), the same departure happens with no Central Dispatcher: the letter just leaves
  on time.
- [ ] Soon after, it's delivered to Home **on B**, not to A's own Testville/Home.
- [ ] The letter on B has the right title, text and author, and names your friend.
3. `/postal track last` on A, and `/postal track <id>` on B.
- [ ] Both show the same letter, `DELIVERED`, with steps `on A` up to `IN_NETWORK` and `on B` after.
4. With an economy:
- [ ] You were charged the network rate (default 10).
- [ ] A's sending office was paid its half when the letter left A.
- [ ] No refund came later.

## 3. Names

1. On A, run `/addr Testville Home` while holding a letter.
- [ ] A's own Testville (a plain name prefers this server's office).
2. If a third server also has a Testville, run `/addr Testville …` for a name only other servers have.
- [ ] It lists `server:office` choices.
3. Run `/addr Testville Home nobody_like_this`.
- [ ] `No player called nobody_like_this is known on this network.`

## 4. Only letters cross

1. Next to a chest with something in it, run `/package B:Testville Home`.
- [ ] `Parcels can't be sent to another server; only letters can.` Nothing is packed, and you aren't charged.
2. Package a chest for A's own Testville, then try to re-address the label with `/addr B:Testville Home`.
- [ ] Refused: a shipping label can't be re-addressed.

## 5. Optional: the hard cases

1. **B is down.** Stop B, then send a letter from A to B.
- [ ] It leaves A's Central (`/postal track` says `IN_NETWORK`). It's delivered once B is back.
2. **A crash mid-hand-off.** This is covered by reconciliation and the store tests. To try it, `kill -9` A
   right after a letter reaches A's Central, then restart A.
- [ ] The letter is either still at A's Central or `IN_NETWORK`. It is never in both places, and never
  delivered twice.
