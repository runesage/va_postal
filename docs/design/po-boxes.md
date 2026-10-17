# P.O. Boxes (design draft)

Status: **design agreed, not scheduled.** Independent of the Central Office design.

A player rents a numbered box at a post office and gets mail there instead of (or as well as) at a house: a
first address for new players, a private one, a stable one for nomads and shops, and rent for the office.

## The idea: one cabinet, many boxes

A wall of real chests would look right but runs out of room: every box costs a block, and a busy office needs
dozens. So boxes are **pseudo-physical**, the way an ender chest is:

- Each office has one **box cabinet**: a **barrel** with a sign in front reading `[Postal_Box]` / town. A
  barrel reads as a sorting cabinet and is told apart from house mailboxes (chests) at a glance; it also can't
  join into a double chest or be blocked by a cat sitting on it. It can be dressed up as a wall of little brass
  doors, but that's decoration; only the barrel matters.
- **Opening the cabinet shows each renter their own box**, a 27-slot view of the letters and parcels held for
  them. Two players opening it at once see different boxes.
- **The postman still walks the mail in.** Box mail goes on his round like any address, ending at the cabinet,
  where he files it. Players see delivery happen; there's no instant teleporting of mail.
- **The boxes live in the mail store**, not in the cabinet's inventory. A letter in a box is a record with box
  custody. So there's no limit on the number of boxes, the cabinet's footprint is one block, and nothing is
  lost if the cabinet is broken (rebuild it and the boxes are still there).

## Addressing

- A box is an address of its office named `Box <n>`: `/addr riverside box12` (and `server:riverside box12`
  across servers, through the existing network addressing and directory).
- **A box and a house:** a player can rent a box and own a house address; mail goes to whichever one a letter
  is addressed to.
- **Attention:** in v1 a box has one renter. A letter to a box with `/att` set must name the renter, or the
  sender is told the name doesn't match. Later, shared boxes (below) build on the same field.

## Renting

| | |
|---|---|
| Rent a box | `/pobox rent <office>` at the office: the next free number. The first day is paid up front. |
| Price | Set by the office owner (`/pobox price <office> <amount>`), defaulting to `Boxes.Default_rent` in economy.yml. Server-owned offices use the default. |
| Paid to | The office's account; a server-owned office's rent goes to Central. Recorded as a new economy flow, `BOX_RENT`. |
| Charged | Each Postal day, in the same pass as office upkeep (`P_Day.run`), from the renter's balance. |
| Short of money | Unpaid rent becomes arrears, like office upkeep. The renter is told on login and when it happens. |
| Grace | `Boxes.Grace_days` (default 3) days in arrears, then the lease lapses (below). |
| Limits | `Boxes.Max_per_player` (default 1), `Boxes.Max_per_office` (default unlimited). |
| Leave | `/pobox release`: anything still in the box is handed over (inventory permitting) or waits at the counter. |
| Info | `/pobox`: your box, its number, paid-through date and arrears. `/pobox list <office>` for the owner. |
| Admin | `/pobox evict <office> <n>`: ends a lease (as if it lapsed). |

## Taking mail out

- The cabinet view is **take-only**: items can't be put in, so a box can't be used as free storage (and an
  item can't be duplicated by putting it back). Shift-click or drag takes a letter; the take is a ledger move,
  box → player, recorded as DELIVERED (letters) or handled as a parcel pickup (COD and acceptance work as at a
  house).
- **Full box:** every box is the same size, 27 items (no bigger boxes for more rent). Mail for a full box waits at the office, as mail for a full house
  mailbox does today, and the renter gets a "your box is full" notice.

## When a lease lapses

The box closes and its number becomes free, and its mail goes **back to the senders straight away** (no
extra hold):

- Each letter or parcel in the box, or waiting at the office for it, goes back to its sender as RETURNED, using
  the same return route as issue #19 (undeliverable network letters). Building both on one mechanism is part of
  this design.
- A sender with no address of their own: it waits at their origin office's counter. A server-sent letter
  (no sender): it's EXPIRED.
- The renter is told. The grace period before the lapse is the time to pay up; after it, the mail is gone.

## Later: shared boxes

v1 is one renter, one box. A v2 could let the renter add members (`/pobox add <player>`); a letter's attention
then picks which member it's for, and only they (and the renter) can take it. The data model below leaves room
for that.

## How it fits the code

- **Custody:** a new `Custody.Kind.BOX` with reference `<office>#<n>`; box mail is AT_DEST_BRANCH with box
  custody. The reconciler skips box custody (there's no chest to check), so box mail can't be marked MISSING.
- **Store schema (V6):** a `po_box` table: office, number, renter (UUID), rented_since, paid_through, arrears,
  server id. Leases and arrears in the store mean they survive restarts and are shared across servers.
- **Routes:** the cabinet is an address with a route like any other, surveyed or hand-made; it's usually a few
  steps from the office chest.
- **Economy:** rent goes through `PostalEconomy` and the office accounts, in `P_Day`; reserves and dividends
  are unchanged.
- **Security:** the cabinet view only ever shows the opening player's own box; admins can view any box with
  the inspector permission, as with private mailboxes today.

## Testing

- Unit: rent and arrears arithmetic, lapse timing, the box ↔ record mapping.
- Store contract (SQLite and MariaDB): box custody, the `po_box` table, migrations from V5.
- Smoke: rent a box, deliver a test letter to it, a Postal day charges rent, arrears lapse a lease and return
  its mail.
- Bot player: open the cabinet, take a letter out, try to put one in. The bot will need inventory-click support
  for this (it can only chat and run commands today).
- In game only: the cabinet view, the "box full" and arrears notices, the decoration.

## Phases

1. Schema V6, `/pobox` rent, release, price, info; rent on the Postal day.
2. Delivery to the cabinet; the take-only per-player view.
3. Lapse and return to sender (with #19's return mechanism).
4. Tests, docs (`docs/commands.md`, `docs/economy.md`), test plan.

## Decisions

- When a lease lapses (after the grace period), its mail returns to the senders at once.
- One box size for everyone: 27 items.
- A box doesn't replace a house: players can have both.
- The cabinet is a barrel with a `[Postal_Box]` sign.
- Prices: office owners set them, defaulting to a server-wide value. One renter per box in v1; sharing later,
  through the attention field.
