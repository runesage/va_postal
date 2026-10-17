# Test plan: persistent mail state, phase P1 (letters)

What P1 promises, and what this plan checks: **a tracked letter is never lost or duplicated by a restart, a
crash or a griefed chest, and Postal always knows where it is.** Parcels aren't tracked until P2; they should
behave exactly as before.

Allow about 90 minutes. Do the sections in order: later ones assume the earlier ones passed. Tick each
check as you go, and note anything odd even if the check passes.

## 0. Setup

```
dev/test-server.sh reset
dev/test-server.sh start --seed
```

This builds the PR's jar and seeds the flat test network: Central at (0, -60, 0) and three streets.
- **Testville** is at z=0, **Riverside** at z=40 and **Hilltop** at z=80.
- On each street the post office chest is at x=20 and the addresses are at x=40–80: Testville's are Home,
  Bakery, Smithy, Library and Farm.

The economy is on and pacing is fast: a postman leaves every ~10 s.

- [ ] The console shows `Mail store: SQLite (postal.db), server id 'main'.`
- [ ] About 2 s after start, it shows `[Postal] Reconciliation: 0 letters checked: ...`
- [ ] `plugins/Postal/postal.db` exists in the server folder.

### How to watch a letter

| Tool | What it shows |
|---|---|
| `/postal track recent` | the 10 most recently changed letters: id, destination, **state**, **where** (`CHEST@world,x,y,z`), and `(moving)` mid-move |
| `/postal track <id>` | one letter's whole history: every state change, who made it (player, postman, central, reconcile, admin) and why |
| `/postal testletter <from> <to> <address>` | hands in a tracked letter at `<from>`'s office chest without writing one (same flow as a player's letter) |
| `/postal talk` | postman chatter, to see when a route starts and ends |
| `/postal bypass` | lets you open Postal chests to look inside (5 min) |
| `/postal reconcile` | runs the reconciliation check now (loaded chunks), instead of waiting for the 5-minute one |
| Console | `[Postal] ...` lines: reconciliation results, stale copies removed, missing letters |

Tip: copy a letter's id from `/postal track recent` (click the chat line or copy from the console). You'll
use ids a lot.

The states, in order: `POSTED` (addressed, still with its sender) → `AT_ORIGIN_BRANCH` → `AT_CENTRAL` (only
between towns) → `AT_DEST_BRANCH` → `OUT_FOR_DELIVERY` → `DELIVERED`. The others are `RETURNED` and
`MISSING`.

### How to crash the server

A clean `stop` saves the world. A crash doesn't, and that's the case P1 exists for. To crash it, run this in
another terminal:

```
pkill -9 -f "paper.jar nogui"
```

On Windows, end the `java` process in Task Manager. Then `dev/test-server.sh start --no-build`.

---

## 1. The happy path

**1a. A letter within a town**
1. Write and sign a book. Stand near Testville's office and run `/addr Testville Bakery`, then confirm.
2. `/postal track recent`: your letter is `POSTED`.
3. Put it in **Home**'s mailbox chest (x=40), like a resident posting mail.
4. Watch `/postal track recent` until the postman has been to Home and then Bakery.
- [ ] States go `POSTED` → `AT_ORIGIN_BRANCH` → `OUT_FOR_DELIVERY` → `DELIVERED`.
- [ ] `/postal track <id>` lists each step with the postman as the actor.
- [ ] The book is in Bakery's chest **once**, and not in Home's or the office chest.
- [ ] The postage settled as before (economy): `/postal bank` shows no postage held afterwards.

**1b. A letter between towns**
1. `/postal testletter Testville Riverside Mill`.
- [ ] It passes through `AT_CENTRAL` and `AT_DEST_BRANCH` before `DELIVERED`.
- [ ] It ends up in Mill's chest (Riverside, x=40) once.

**1c. Re-addressing**
1. Address a book to Testville Smithy but don't post it. Then `/addr Testville Library` with it in hand.
- [ ] `/postal track recent` shows the first id `RETURNED` ("re-addressed") and a new id `POSTED` for Library.
- [ ] Posting it delivers to Library, not Smithy.

**1d. Parcels unchanged**
1. `/package` a chest to Testville Farm and mail the label.
- [ ] It's delivered and `/accept` works as before. Parcels don't appear in `/postal track recent` (that's
  P2).

## 2. Clean restarts

**2a. Restart with letters in an office chest**
1. Run `/postal stop` so the postmen stay put.
2. Run `/postal testletter Testville Testville Farm` three times.
3. `stop` the server, then start it again.
- [ ] The startup reconciliation line counts 3 letters checked, with 0 missing.
- [ ] After `/postal start`, all three are `DELIVERED`, each in Farm's chest once.

**2b. Restart while a postman is out**
1. Hand in a letter for **Hilltop Barracks** (the farthest address), and wait for `OUT_FOR_DELIVERY` in
   `/postal track recent`.
2. `stop` the server cleanly.
3. Start it.
- [ ] The reconciliation line shows `1 interrupted runs returned` (or the letter was already delivered
  before the stop: then it's simply `DELIVERED`).
- [ ] The letter is delivered once after the restart. The history shows `route run ... interrupted` by
  reconcile.

## 3. Crashes (the important part)

Do each one from a clean state. Note the letter id first, every time.

**3a. Crash right after posting**
1. Hand in a letter (`testletter`), and crash within 5 seconds.
2. Start again.
- [ ] The letter isn't lost: it's either in the office chest and later delivered, or the reconcile line
  shows it settled.
- [ ] There's exactly **one** copy anywhere (check the office chest and the destination chest).

**3b. Crash while the postman is carrying it**
1. Hand in a letter for Hilltop Barracks, and wait for `OUT_FOR_DELIVERY`.
2. Crash.
3. Start again.
- [ ] The interrupted run is returned (reconcile line), and the letter is delivered **once**.
- [ ] No copy is left behind in Hilltop's office chest.

**3c. Crash just after delivery (the world rolls back)**

Worlds save every 5 minutes, but the mail store saves instantly. So after a crash, the world can be behind
the store.
1. Run `save-all` in the console, then hand in a letter.
2. Wait until it's `DELIVERED`. Crash **without** saving.
3. Start again.
- [ ] The console says `The world was behind the mail store: moved letter <id> ... forward ...`, **or** the
  letter is already in the destination chest.
- [ ] There's exactly one copy, in the destination chest. It's not in the office chest.

**3d. Crash in the middle of a busy town**
1. Hand in 10 letters to different Testville and Riverside addresses (`testletter` repeatedly), and let the
   postmen run for about 30 seconds.
2. Crash.
3. Start again and let everything finish (`/postal track recent`, then check each one).
- [ ] All 10 end `DELIVERED`.
- [ ] Each destination chest holds the letters addressed to it once. Count them.
- [ ] No letters are left in any office chest or Central's chest.

If any check in section 3 fails, stop and send me the report (below) before going on.

## 4. Grief and duplication

**How these tests work.** Postal only judges a chest when it looks at it:
- at startup;
- every 5 minutes, for chunks that are loaded;
- at the end of a postman's route;
- right away, with **`/postal reconcile`** (the 5-minute check, run now).

Stay near the chests so their chunks are loaded. Run **`/postal stop`** before each test so no postman moves
the letter while you set up, and `/postal start` only when a step says so.

**One thing to know first:** `OUT_FOR_DELIVERY` is a record state only. The book stays in the office chest
until the postman puts it in the mailbox. So "taking a letter that's out for delivery" means taking it from
the office chest.

**4a. Breaking a chest that holds mail**
1. `/postal stop`, then `/postal testletter Testville Testville Farm`. It waits in Testville's office chest
   at (20, -60, 0). Note its id.
2. With `/postal bypass`, break that chest.
3. Restart the server (`stop`, then start). A destroyed chest is only judged at startup, when Postal can be
   sure it's gone and not just in an unloaded chunk.
- [ ] Startup logs `Letter <id> ... is MISSING (its chest is gone ...)`, and `/postal track <id>` shows
  `MISSING`.
4. Put the office back from the console. The sign goes on the chest's **front**, with the text
   `[Postal_Mail]` / `Testville` / `[Local]`:
   ```
   setblock 20 -60 0 minecraft:chest[facing=south]
   setblock 20 -60 1 minecraft:oak_wall_sign[facing=south]{front_text:{messages:["[Postal_Mail]","Testville","[Local]",""]}}
   ```
- [ ] `/postal start`, then `/postal testletter Testville Testville Home`, is delivered as normal.

**4b. Taking a letter out of the office chest**
1. `/postal stop`, then `/postal testletter Testville Testville Farm`. Note its id.
2. With `/postal bypass`, take the letter out of Testville's office chest and keep it in your inventory.
3. Run `/postal reconcile`.
- [ ] The result counts `1 missing`, and `/postal track <id>` shows `MISSING` ("not in its chest").

**4c. Taking a letter while it's out for delivery**
1. `/postal start`, then `/postal testletter Testville Testville Library`. Watch `/postal track recent` until
   it's `OUT_FOR_DELIVERY`.
2. Quickly take it out of Testville's office chest (with `/postal bypass`) before the postman reaches
   Library.
3. Wait for the postman to finish that route (`/postal talk` shows it).
- [ ] The console says `Letter <id> ... is MISSING (not in the office chest when its route ended)`. The
  history shows `MISSING`, not `AT_DEST_BRANCH`.

**4d. A copied letter**

Postal must never route a copy of a tracked letter.
1. `/postal stop`. Then `/postal testletter Testville Testville Farm` (it waits in Testville's office) and
   `/postal testletter Riverside Riverside Mill` (it waits in Riverside's office at (20, -60, 40)).
2. In creative, open Testville's office chest (with `/postal bypass`) and middle-click the Farm letter to
   copy it. Put the copy in **Riverside's** office chest.
3. Run `/postal reconcile`.
- [ ] The console says `Removed a stale copy of letter <id> from world,20,-60,40`, and the copy is gone from
  Riverside's chest.
- [ ] The original is still in Testville's chest. After `/postal start` it's delivered to Farm, once.

**4e. A copy placed in a mailbox**
1. As 4d, but put the copy in **Bakery**'s mailbox chest (x=50) instead, then `/postal start`.
- [ ] Whatever the postman does, the copy is **never delivered** to Farm. Farm ends with exactly one letter
  with that id. If the postman visits Bakery while the original is still waiting, the console says
  `Removing a stale copy of letter <id>` and the copy is gone. If the original is delivered first, the copy
  is left in Bakery as an ordinary book and never routed. Both are correct.

**4f. A delivered letter put back**
1. Take a delivered letter out of its mailbox and put it into another address's mailbox chest.
- [ ] It isn't deleted. The postman leaves it alone: it's the player's book now.

**4g. A fake postal book**
1. `/postal stop`, then `/postal testletter Testville Testville Farm`, so a tracked letter waits in
   Testville's office chest. Postal inspects chests that hold tracked mail.
2. Write and sign a book whose first page contains `Mailed from:`, and put it in that office chest too.
3. Run `/postal reconcile`.
- [ ] The console logs `An untracked postal-looking book is in the chest at world,20,-60,0 ...` and the
  result counts `1 forged books flagged`. Nothing is deleted.

## 5. Load (optional)

1. Run `testletter` 40 times across all three towns (a command block or a macro helps), and let it run for 10
   minutes.
- [ ] No lag spikes (`/tps` or `/mspt` stay normal).
- [ ] Every letter ends `DELIVERED`.

## If something fails

Run `dev/test-server.sh report` and send me:
- the bundle it makes (logs and config);
- the letter id(s) involved, and the output of `/postal track <id>` for each;
- which step you were on, and roughly when (the console time).

`plugins/Postal/postal.db` is also useful if you can attach it.

If you have `sqlite3`, this gives a quick count of where everything is:

```
sqlite3 plugins/Postal/postal.db "select state, count(*) from mail group by state"
```
