# Test plan: persistent mail state, phase P2 (parcels)

What P2 promises, and what this plan checks:
- **A parcel's items survive shipping exactly as they were packed:** enchantments, names, lore, everything.
- **The items exist exactly once,** whatever happens (crashes, copied labels, a second `/accept`).
- **COD and postage follow the parcel's record.**
- **The packed chest stays in the world** until a courier comes to collect it.

Allow about 75 minutes. P1's letter tests still apply, but you don't need to repeat them.

## 0. Setup

```
dev/test-server.sh reset
dev/test-server.sh start --seed
```

This is the same flat test network as P1:
- Central is at (0, -60, 0).
- **Testville**'s street is at z=0 (office x=20; Home, Bakery, Smithy, Library, Farm at x=40–80).
- Riverside's is at z=40 and Hilltop's at z=80.

Run `/postal talk` to see the postmen's chatter.

### Tools

| Command | What it does |
|---|---|
| `/postal track recent` | the newest mail. Parcels show `[parcel]` |
| `/postal track <id>` (or `last`) | a parcel's state, history, **Contents** (each stack, with its name and enchantment count) and COD |
| `/postal testparcel <from> <to> <address> [cod] [retired]` | packs a test parcel (an enchanted, renamed "Test Blade", 32 oak logs and 3 golden apples) in a chest beside `<from>`'s office, locks it, and hands its label in. `retired` adds an item that's "gone from the game" |
| `/postal accept <id> [x y z]` | accepts a **delivered** parcel for its recipient (items in a chest; no COD) |
| `/postal refuse <id>` | refuses a delivered parcel (items go back where it was packed) |
| `/postal recover <id> [x y z]` | rebuilds lost mail from its record |
| `/postal setstate <id> <state>` | forces a state (testing only) |
| `/postal reconcile` | the reconciliation check, now |

`last` works anywhere a mail id does: it means the newest mail. Run `/postal stop` before you set something
up, and `/postal start` when a step says so.

---

## 1. Packaging

**1a. Packing a chest**
1. Place a chest near Testville. Put in an enchanted item you've renamed in an anvil, a stack of anything,
   and a written book.
2. Stand next to it and run `/package Testville Home`, then confirm.
- [ ] The chest is **empty** straight away, has a `[Postal_Ship]` sign, and won't open (it's locked).
- [ ] You're holding the shipping label.
- [ ] `/postal track last` shows `[parcel] ... POSTED`. **Contents** lists your stacks, with your item's name
  and its enchantment count.
- [ ] A hopper placed under the chest pulls nothing out.

**1b. COD**
1. Holding the label, run `/cod 50`.
- [ ] `/postal track last` shows `COD 50`.

**1c. Cancelling before posting**
1. Package another chest, then with its label in hand run `/package cancel`.
- [ ] The items are back in **that** chest, its sign is gone, and it opens normally.
- [ ] The label is gone from your hand, and the postage you paid is refunded (check your balance).
- [ ] `/postal track last` shows `RETURNED` ("cancelled by the sender").
- [ ] `/package cancel` on a label that's already been picked up says it's already in the post.

**1d. Re-addressing**
1. Holding an unposted label, run `/addr Testville Bakery`.
- [ ] It's refused, and tells you to cancel and package again.

## 2. The courier

**2a. With you watching**
1. Package a chest (as in 1a) for Testville Bakery. Stand within about 20 blocks of the chest.
2. Post the label in Home's mailbox (x=40), and `/postal start`.
- [ ] When the postman collects the label at Home, a **Postal Courier** walks up to your packed chest, pauses,
  picks it up (the chest disappears and he's holding one), walks off and vanishes in a puff of smoke.
- [ ] The chest and its sign are gone, and nothing dropped on the ground.

**2b. With nobody near**
1. Package a chest, then go more than 40 blocks away from it before the label is picked up.
- [ ] When you go back, the chest is simply gone. No courier was needed.

**2c. Something left in the chest**
1. Package a chest. Put a hopper **above** it (hoppers can push into it) feeding some cobblestone, then post
   the label.
- [ ] When it's collected, the cobblestone drops on the ground: it isn't lost.

## 3. Delivery, accept and refuse

**3a. Accepting**
1. `/postal stop`, then `/postal testparcel Testville Testville Home`, then `/postal start`. Wait for
   `/postal track last` to show `DELIVERED`.
2. Take the label from Home's mailbox and run `/accept`.
- [ ] A chest appears in front of you with: **Test Blade** (Sharpness V and Unbreaking III, with its lore),
  32 oak logs and 3 golden apples.
- [ ] The label became a statement. `/postal track last` shows `ACCEPTED`.

**3b. Accepting twice**
- [ ] `/accept` again (holding the statement) is refused.

**3c. A copied label**
1. Ship another test parcel to Home. When it's delivered, **craft a copy** of the label (the label plus a
   book and quill on a crafting table). The copy carries the same mail id.
2. `/accept` with the **copy**.
- [ ] It works: you get the items.
3. `/accept` with the **original**.
- [ ] It's refused: "This order has already been filled." Nothing is placed. Whichever label goes first
  wins, and the items come out once.

**3d. COD**
1. `/postal testparcel Testville Testville Home 25`, and wait for `DELIVERED`.
2. `/accept` with the label.
- [ ] You're charged 25. Test parcels have no sender to pay, so check the charge only. With a real
  parcel, the sender is paid even if they're offline.
- [ ] With less than 25 in your balance, `/accept` is refused and nothing is placed.

**3e. Refusing**
1. Ship a test parcel to Home, and wait for `DELIVERED`.
2. `/refuse` with the label.
- [ ] A chest appears where the parcel was packed (beside Testville's office), with the items and the
  statement inside.
- [ ] `/postal track last` shows `REFUSED`. A second `/accept` or `/refuse` is refused.

**3f. An item removed from the game**
1. `/postal testparcel Testville Testville Home retired`. This parcel also carries a paper recorded under an id
   no version of Minecraft has, as if an upgrade had removed it. Wait for `DELIVERED`.
2. `/postal track last`.
- [ ] **Contents** lists `minecraft:postal_retired_item (no longer in the game)` beside the other stacks.
3. `/accept` with the label.
- [ ] You get the Test Blade, logs and apples, and **no** paper.
- [ ] A message says 1 item (`minecraft:postal_retired_item`) no longer exists in this version of Minecraft
  and couldn't be delivered.
- [ ] The console logs a warning naming the item, and `/postal track last` shows `ACCEPTED` with a history
  line "left out (no longer in the game): minecraft:postal_retired_item".

## 4. Crashes

Crash with `pkill -9 -f "paper.jar nogui"`, then `dev/test-server.sh start --no-build`.

**4a. Crash while the label is out for delivery**
1. `/postal testparcel Testville Testville Farm`. Wait for `OUT_FOR_DELIVERY`, then crash.
2. Start again and let it finish.
- [ ] It's `DELIVERED`, with exactly one label in Farm's mailbox and none left in the office.
- [ ] `/accept` gives the items once.

**4b. Crash right after packaging (the world rolls back)**
1. Run `save-all`. Package a chest of items (as in 1a), and keep the label.
2. Log **out** (this saves your inventory, with the label in it), then crash the server **without** saving.
3. Start again and log in. Your label is still in your inventory, and the chest is back as it was: full, and
   without its sign.
4. Post the label in Home's mailbox and `/postal start`.
- [ ] At pickup the console says `Parcel <id> cancelled: the world was rolled back past its packing ...`. The
  label is left in the mailbox, and `/postal track <id>` shows `RETURNED`.
- [ ] The items are only in the chest. There's no way to get them a second time: `/accept` with that label
  is refused.

**4c. Crash after delivery**
1. Run `save-all`. Ship a test parcel and wait for `DELIVERED`. Crash without saving.
2. Start again.
- [ ] The label is in Home's mailbox once (the reconciler may log that it moved it forward), and `/accept`
  works once.

## 5. Lost parcels and recovery

**5a. A label taken from the office**
1. `/postal stop`. Run `/postal testparcel Testville Testville Home`, take the label out of Testville's office
   chest (with `/postal bypass`), and run `/postal reconcile`.
- [ ] The console logs `Parcel <id> ... is MISSING (not in its chest ...)`, and the parcel shows `MISSING`.
2. `/postal recover <id>`.
- [ ] A chest appears in front of you with the parcel's items. It's now `RECOVERED`.
- [ ] Putting the taken label back in a mailbox does nothing: it's never routed, and `/accept` on it is
  refused.

**5b. Forcing states (optional)**
- [ ] `/postal setstate last MISSING`, then `/postal track last`: the history shows "forced by an admin".

## 6. Letters still work

- [ ] `/postal testletter Testville Testville Home` is still delivered.
- [ ] `/postal bank` shows postage held going to 0 after deliveries.

## If something fails

Run `dev/test-server.sh report` and send me the bundle, the mail id(s) with their `/postal track <id>`
output, and which step you were on.
