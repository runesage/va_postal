# Design: Central Office

**Status:** draft for discussion. **Plan reference:** `docs/plans/postal-revival-v3.md` §3 (Central Office
Redesign).

## 1. Goals

Central exists today only as a chest, a location and an account. Postmen pass through, but nobody works
there and players have no reason to go. This design makes Central the place that **runs the network**:

- someone works there, and players deal with them in person;
- new post offices are chartered there, and ownership changes are signed off there;
- Central sets the network's postage, and tells every branch when it changes;
- Central keeps an eye on the branches and shows how the network is doing.

The split of responsibilities: **Central owns the network and anything that needs authority; local offices
own their residents.** Addresses stay with their local office.

Out of scope here: Towny integration (its own branch); a dead letter office (dropped); PO boxes (a later
local-office feature, §9); and the insurance fund, which comes with parcels in persistent-state phase P2.

## 2. The clerk: the Postmaster General

A Citizens NPC who stays at Central's counter. It doesn't walk routes, and it uses the bundled skin and
naming like the other Postal NPCs.

- **Spawn:** spawned with the dispatcher at Central's location (or a `Clerk_location` set with
  `/postal clerk here`), and removed on stop, like the other Postal NPCs. It is never saved by Citizens.
- **Name:** the Postmaster General.
- **Look:** it faces the nearest player within a few blocks, and holds a book and quill.
- **Counter menu:** right-clicking the clerk opens an inventory menu of Central's services. A service is
  only available through the clerk, so the player has to be at Central:
  - **Charters:** buy one, collect an approved one, reissue a lost note, refund an expired one (§3);
  - **Sign a transfer** (§3);
  - **Postage rates:** the current base rates and every office's discount;
  - **Network map** (§6).
- Admin commands keep working from anywhere. The counter rule applies only to players.

## 3. Charters

### Chartering a new office

Today a staff member places an office by running `/setlocal` in front of its chest, and gives it an owner
with `/setowner`. That stays as the admin way. Charters add a way for players to found an office
themselves, through Central:

1. **Buy a charter at Central.** At the counter, the player picks a name for the office and pays the post
   office purchase price. The name is checked and reserved there, so name clashes are settled at Central.
   They get a **charter note**: a written item naming the office and its holder, carrying a charter id.
   Central holds the payment until the charter is used.
2. **Open the office with it.** Holding the note, the player right-clicks the chest that will be the office's
   chest. Postal checks that:
   - the player is the charter's holder;
   - the chest isn't already a Postal chest;
   - it's far enough from other offices (`Charter.Min_distance`).

   The office is then created at that chest. Postal places the office sign on it, the player becomes the
   owner, and the payment seeds the office as in `docs/economy.md`. The note is used up.
3. **Admin approval (optional).** With `Charter.Require_approval: true`, a charter is only sold after
   `/postal charter approve <player> <name>`; the player applies at the counter and collects the note
   there once it's approved.

**Protection plugins** decide where an office may go without Postal knowing about them: if the player isn't
allowed to open the chest (a claim, a region), the right-click never reaches Postal.

**Charter notes:**
- **Bound to the buyer.** Only the holder named on it can use it, so Central always knows who it chartered.
  A charter for someone else is bought in their name. That keeps ownership changes at Central (see Selling an office).
- **Expiry.** An unused charter expires after `Charter.Expiry_days` (default 14). The name is released, and
  the holder can collect a refund at the counter, minus `Charter.Expiry_fee` (default 10%).
- **Lost notes.** The counter reissues a note for the same charter, and the old one stops working (the
  charter id is checked when it's used).

### Selling an office

An office only ever changes hands through Central, in one of two ways. There is no informal handover.

**Selling back to Central.** At the counter, the owner sells the office back to Central and gets the
refund an office changing hands already pays (`docs/economy.md`): its balance minus the address refunds it
holds in escrow, plus Central's share of the purchase price. The office stays open as a server-owned office,
so its addresses and residents aren't affected.

**Selling to another player through Central.**

1. The owner offers it from anywhere: `/postal office <office> sell <player> <price>`.
2. The buyer accepts at the counter. They pay the agreed price to the seller, and a transaction fee of
   `Charter.Transfer_rate` (default 5%) of that price to Central.
3. The office keeps its account, balance and escrow. The buyer pays the seller directly, so the system
   refund does **not** apply on top of the sale.

An offer expires after `Charter.Offer_days` (default 3), and the owner can withdraw it.

`/setowner` stays as an admin override.

## 4. Rates

- **Base postage is Central's.** Central sets the base rates as economy policy, using the same policy store
  as the rest of the economy settings.
- **Branches may only discount.** An owner sets one discount from 0 up to `Rates.Max_discount` (default
  25%) with `/postal office <office> discount <percent>`. No surcharges for now.
- **The discount comes only out of the office's own share.** Central's cut is worked out from the base
  rate, so a price war between branches can't drain Central or its reserves.
- **Rate-change notices.** When base rates change, Central sends a notice book to every office chest,
  carried by a courier NPC on the existing Central-to-office routes. The notices use the same delivery
  path as `/dist`.

## 5. Inspections (reports only)

Central watches the branches and sends an inspector to any that are flagged.

- **Flags:** arrears, a missing or full office chest, a route that keeps getting stuck, a low on-time rate,
  or letters going `MISSING` (from the mail store).
- **Visit:** the inspector walks to the office and leaves a report book in its chest, and sends a copy to the
  owner. The report lists what was found and how to fix it.
- **Reports only:** inspections carry no penalties. The latest report for each office is also shown on the
  notice board (§6).
- At most one inspection per office per `Inspection.Interval_days` (default 7).

## 6. Flavour

### Notice board

Signs, and item frames with maps, on a wall at Central. They're registered by looking at the wall and
running `/postal board add`, and refreshed every Postal day:

- current base rates;
- the leaderboards (busiest office, best on-time rate, fewest losses);
- the Branch of the Month (§6);
- recent notices and inspection results.

### Maps

Paper's map API draws Postal maps into ordinary map items:

- **Network map:** Central, every office and the routes between them. Bought from the clerk, or hung on the
  notice board.
- **Office map:** one office's area, with its addresses and route. Sold at the counter, and a local owner
  could later sell them at their own office.

The renderers are attached again when a map loads, so the maps keep working after a restart.

### Branch of the Month

Each period (`Awards.Period_days`, default 30), the office with the best score gets a plaque on the notice
board and an optional bonus from Central (`Awards.Bonus`, default 0).

- The score combines deliveries, on-time rate and losses, with configurable weights.
- The economy is closed, so the bonus is paid only from Central's money **above its target**, the same
  money the dividend comes from. It is skipped when there isn't enough.

## 7. Stats

Inspections, leaderboards and Branch of the Month read the same per-office numbers: deliveries, the time
from posting to delivery, and letters lost. They come from the mail store's event history (persistent-state
P1), so no new tracking is needed in the world. A small stats module summarises them once per Postal day
into a `office_stats` table.

**On time** needs an expected delivery time. To start with, it's the route's recorded round-trip time
(already measured per address) plus Central's dispatch interval when the letter crosses between offices.

## 8. Phases

| Phase | Scope | Needs |
|---|---|---|
| **C1** | The clerk and the counter menu, charter notes (buy, open, expiry, optional approval), selling offices | Economy (#6) |
| **C2** | Base rates as policy, branch discounts, courier notices | C1 |
| **C3** | Stats module, notice board, maps, Branch of the Month | Persistent state P1 (#7) |
| **C4** | Inspector and reports | C3 |

## 9. Later

- **PO boxes:** a rented PO box at a local office, for players without an address. It belongs with the
  local offices, not Central.
- **Congestion:** dispatch slows as Central's hourly volume rises, with `/expedite` as the paid way past
  it. Off by default.
- **Insurance fund:** with parcels in persistent-state P2.

## 10. Decisions

1. Branches may discount but not surcharge, for now.
2. Inspections are reports only, with no penalties.
3. Rate disputes are dropped.
4. Central's first functions are the clerk and charters (including office sales). Notice boards, maps and
   Branch of the Month follow as flavour.
5. No dead letter office. PO boxes are a later local-office feature.
6. Offices are chartered with a charter note bought at Central and used on a chest. `/setlocal` stays
   for admins.
7. An office is only ever sold back to Central or to another player through Central, with a transaction
   fee that is a percentage of the sale price.
8. The clerk is the Postmaster General.
9. Towny integration is out of scope here; it comes in its own branch.
10. Charter notes are bound to their buyer and can't be traded.
