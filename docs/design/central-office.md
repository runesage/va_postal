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

Out of scope here: a dead letter office (dropped); PO boxes (a later local-office feature, §9); and the
insurance fund, which comes with parcels in persistent-state phase P2.

## 2. The clerk

A Citizens NPC who stays at Central's counter. It doesn't walk routes, and it uses the bundled skin and
naming like the other Postal NPCs.

- **Spawn:** spawned with the dispatcher at Central's location (or a `Clerk_location` set with
  `/postal clerk here`), and removed on stop, like the other Postal NPCs. It is never saved by Citizens.
- **Look:** it faces the nearest player within a few blocks, and holds a book and quill.
- **Counter menu:** right-clicking the clerk opens an inventory menu of Central's services. A service is
  only available through the clerk, so the player has to be at Central:
  - **Charter a post office** (§3);
  - **Sign a transfer** (§3);
  - **Postage rates:** the current base rates and every office's discount;
  - **Network map** (§6).
- Admin commands keep working from anywhere. The counter rule applies only to players.

## 3. Charters

### Chartering a new office

Today a staff member places an office with `/setlocal` and gives it an owner with `/setowner`. With charters
a player can found an office themselves, but only through Central:

1. **Apply on site.** At the spot for the new office, the player runs `/postal charter <name>`. This checks
   that the name is free, that the spot is far enough from other offices, and that the player may build
   there (Towny plot or WorldGuard region, when they're installed). It records a pending application with
   the location. Nothing is charged yet.
2. **Sign at Central.** At the counter, the player picks the application and pays the post office purchase
   price, which seeds the office as in `docs/economy.md`. The office is created at the recorded location
   and the player becomes its owner.
3. **Admin approval (optional).** With `Charter.Require_approval: true`, a signed charter waits for
   `/postal charter approve <name>` before the office opens. Until then the payment is held by Central and
   refunded if it's rejected.

A pending application expires after `Charter.Application_days` (default 7). `/setlocal` stays as the
admin way to place a server-owned office.

### Transfers

An owner can't hand an office over informally; the change of hands is signed at Central:

1. The owner offers it from anywhere: `/postal office <office> transfer <player> [price]`.
2. The buyer accepts at the counter. They pay the agreed price to the seller and a transfer fee
   (`Charter.Transfer_fee`) to Central.
3. The office keeps its account, balance and escrow. A sale is between players, so the system refund for
   an office changing hands (`docs/economy.md`) does **not** apply.

An offer expires after `Charter.Offer_days` (default 3), and the owner can withdraw it.

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
| **C1** | The clerk and the counter menu, charters (apply, sign, optional approval), transfers | Economy (#6) |
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
4. Central's first functions are the clerk and charters (including transfers). Notice boards, maps and
   Branch of the Month follow as flavour.
5. No dead letter office. PO boxes are a later local-office feature.

## 11. Open questions

- Should chartering require Towny, where it's installed (an office only inside a town, and maybe only by its
  mayor)?
- Should the transfer fee be a flat amount or a share of the sale price?
- What should the clerk be called (for example "Postmaster General")?
