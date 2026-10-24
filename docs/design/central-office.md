# Design: Central Office

**Status:** being built. Done: the clerk (the Postmaster General) and Central's protection (§2), with a first
counter (postage rates, the network, the player's offices); charters: buying, approval, the document, opening an office
and its ceremony, at Central's counter and from the admin commands (§3); office sales, back to Central or to another
player through Central (§3); rates: base postage as policy, office discounts and rate-change notices (§4); office stats
and the leaderboards, in chat and on Central's counter (§7); the notice board, Postal maps and Branch of the Month (§6);
inspections (§5). All of C1 to C4 is built, waiting on an in-game check. Address deeds
(`docs/design/deeds.md`), which share the clerks, counters and deed machinery, are done.

## 1. Goals

Central exists today only as a chest, a location and an account. Postmen pass through, but nobody works
there and players have no reason to go. This design makes Central the place that **runs the network**:

- someone works there, and players deal with them in person;
- new post offices are chartered there, and ownership changes are signed off there;
- Central sets the network's postage, and tells every branch when it changes;
- Central keeps an eye on the branches and shows how the network is doing.

The split of responsibilities: **Central owns the network and anything that needs authority; local offices
own their residents.** Addresses stay with their local office.

**Central is the office owners' window onto "the server".** Everything an owner does with authority behind
it (chartering an office, selling one, rates) is done in person at Central's counter. Central does **not**
gate the mail itself: letters flow between offices as they do today whether or not anyone visits Central.

Out of scope here: Towny integration (its own branch); a dead letter office (dropped); PO boxes (a later
local-office feature, §9); and the insurance fund, replaced by lost-mail claims (§9).

## 2. The clerk: the Postmaster General

A Citizens NPC who stays at Central's counter. It doesn't walk routes, and it uses the bundled skin and
naming like the other Postal NPCs.

- **Staffed whenever the mail runs:** the clerk is spawned when the dispatcher starts, at Central's location
  (or a `Clerk_location` set with `/postal clerk here`), and leaves when it stops, like the other Postal NPCs.
  While the mail runs, Central is always staffed: a heartbeat check respawns the clerk if it has gone
  (killed by a plugin, unloaded with its chunk, despawned) and puts it back at its post if it has been moved.
  While the dispatcher is stopped, Central is closed and the counter can't be used. It is never saved by
  Citizens.
- **Name:** the Postmaster General.
- **Look:** it faces the nearest player within a few blocks, and holds a book and quill.
- **Greeting:** the clerk greets a player who comes up to the counter in chat ("Good day, {player}. Central
  Post Office, how may I help?") and says goodbye when they walk off. The lines come from the language file.
- **Forms:** anything that needs typing or confirming (an office name, a sale price, a denial reason) opens a
  native dialog screen (Paper's dialog API: text fields, number inputs and confirm/cancel buttons). The dialog
  API is marked experimental, so each form falls back to chat prompts if dialogs aren't available or stop
  working after an update.
- **Counter menu:** right-clicking the clerk opens an inventory menu of Central's services, with themed icons
  (a written book for charters, a gold ingot for sales, paper for rates, a filled map for the network map). A service is
  only available through the clerk, so the player has to be at Central:
  - **Charters:** buy one, collect an approved one, reissue a lost note, refund an expired one (§3);
  - **Sell an office:** back to Central, or accept a sale from another player (§3);
  - **Postage rates:** the current base rates and every office's discount;
  - **Network map** (§6).
- Admin commands keep working from anywhere. The counter rule applies only to players.

### Protecting Central

Central is shared by everyone, so Postal protects it itself, without relying on a protection plugin:

- **The clerk** is invulnerable, can't be pushed, pulled, leashed, ridden or set on fire, and keeps its
  equipment. It stays at its post (the heartbeat above puts it back).
- **Central's chest and its sign** can only be broken by admins. Explosions (creepers, TNT, beds, crystals)
  leave them standing, pistons can't move them, hoppers and droppers can't take from or put into the chest,
  and fire can't burn the sign.
- **What players can do:** open the counter menu, and use the chest only as Postal allows (as today).
- Attempts are logged (`Settings.Report_grief`), so staff can see who tried.

Local offices keep today's rules (their owners protect them with claims); the same protection could be offered
to them later.

## 3. Charters

### Chartering a new office

Today a staff member places an office by running `/setlocal` in front of its chest, and gives it an owner
with `/setowner`. That stays as the admin way. Charters add a way for players to found an office
themselves, through Central:

1. **Buy a charter at Central.** At the counter, the player picks a name for the office and pays the post
   office purchase price. The name is checked and reserved there, so name clashes are settled at Central.
   They get a **charter note** (§3, "The charter document"). Central holds the payment until the charter is
   used.
2. **Open the office with it.** Holding the note, the player right-clicks the chest that will be the office's
   chest. Postal checks that:
   - the player is the charter's holder;
   - the chest isn't already a Postal chest;
   - it's far enough from other offices (`Charter.Min_distance`).

   The office is then created at that chest. Postal places the office sign on it, the player becomes the
   owner, and the payment seeds the office as in `docs/economy.md`. The note is used up, and the opening is
   marked (below).
3. **Admin approval (on by default).** The player applies at the counter: they choose the name (reserved
   while the application is open) and pay, and the payment is held. Staff see open applications with
   `/postal charter pending` and decide with `/postal charter approve <player> <name>` or
   `/postal charter deny <player> <name> [reason]`. The player is told either way, by a letter from the
   Postmaster General delivered like any mail, and on approval collects the note at the counter. A denied
   application is refunded in full and its name released.
   - `Charter.Require_approval: true` is the default. `/postal charter autoapprove on|off` changes it (and
     saves it to the config), for servers that would rather sell charters straight away.

**Protection plugins** decide where an office may go without Postal knowing about them: if the player isn't
allowed to open the chest (a claim, a region), the right-click never reaches Postal.

### The charter document

The note is a signed written book that reads like a real charter, with a short ceremony when it's used.

- **Item:** a written book titled `Charter of <Office> Post Office`, authored by the Postmaster General, with
  an enchanted shimmer. A hidden charter id (in the item's data) is what Postal checks; the text is for
  reading.
- **Template:** the wording comes from `charter.yml`, so a server can rewrite it. Placeholders:
  `{office}`, `{town}` (the office's display name), `{world}`, `{server}`, `{holder}`, `{date}`, `{expires}`,
  `{charter_id}`, `{postmaster}` (the clerk's name), `{price}`.
- **Default wording** (two pages):

  > **Charter of {office} Post Office**
  >
  > By authority of the Central Post Office of {server}, and under the hand of {postmaster}, a post office
  > is hereby chartered in the name of **{office}**, in the world of {world}.
  >
  > Charter No. {charter_id}, granted this {date} to **{holder}**, who shall keep its counter, serve its
  > residents, and carry the mail faithfully to and from Central.

  > The holder shall open this office within the realm by placing this charter upon the office's chest, on
  > or before {expires}. Unused, this charter lapses and its name is released.
  >
  > This charter is not transferable. The office may be sold only through Central.
  >
  > Sealed at Central, {date}.
  > {postmaster}, Postmaster General

- **Opening ceremony:** when a charter opens an office: a burst of particles over the chest, a bell, and a
  server-wide notice: *"A new post office has opened in {office}! Chartered to {holder}."* Each part can be
  turned off (`Charter.Announce`, `Charter.Effects`).
- The opened office's first log book (the "Postal log" every office gets) begins with a copy of its charter.

**Charter notes:**
- **Bound to the buyer.** Only the holder named on it can use it, so Central always knows who it chartered.
  A charter for someone else is bought in their name. That keeps ownership changes at Central (see Selling an office).
- **Expiry.** An unused charter expires after `Charter.Expiry_days` (default 14). The name is released, and
  the holder can collect a refund at the counter, minus `Charter.Expiry_fee` (default 10%).
- **Lost notes.** The counter reissues a note for the same charter, and the old one stops working (the
  charter id is checked when it's used).
- **Records:** charters, applications and offers are rows in the mail store (schema V6), so they survive
  restarts, and expiry, refunds and reissues are checked against the record, not the item.
- **Names across servers:** office names are reserved network-wide through the shared directory, so two
  servers on one network can't charter the same name. Each server has its own Central and clerk.

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

*Built.*

- **Base postage is Central's.** Central sets the base rates as economy policy, using the same policy store
  as the rest of the economy settings: `/postal bank policy rates.letter_local | rates.letter_out_town |
  rates.letter_network | rates.parcel_local | rates.parcel_out_town | rates.cod_surcharge <amount>` (they are the
  `Economy.Postage` settings).
- **Branches may only discount.** An owner sets one discount from 0 up to `Economy.Rates.Max_discount` (default
  25%, *policy* `rates.max_discount`) with `/postal office <office> discount <percent|none>`. No surcharges for now.
  Lowering the maximum lowers every discount above it.
- **The discount comes only out of the office's own share.** The sending office's discount is taken off its share
  of the postage when the mail is settled (never more than that share), and goes back to the sender with any other
  refund. Central's and the delivering office's shares are worked out from the base rate, so a price war between
  branches can't drain Central or its reserves. Senders still pay the full price when they address the mail. A COD
  surcharge isn't discounted.
- **Rate-change notices.** When a base rate changes, every office is owed a notice. Central's postman leaves it in
  the office chest on his next visit, as a sheet of paper (*Notice of postage rates*): what changed, the rates now,
  and that office's discount. Several changes before a visit make one notice; a full chest keeps it for the next
  visit. The owner is told in chat if online. (A sheet rather than a book: books in office chests are mail.)
- **The counters** show the rates: Central's *Postage rates* card with the maximum discount, each office's counter
  with its own discount.

## 5. Inspections (reports only)

Central watches the branches and sends an inspector to any that are flagged.

- **Flags:** arrears, a missing or full office chest, a route that keeps getting stuck, a low on-time rate,
  or letters going `MISSING` (from the mail store).
- **Visit:** the inspector walks to the office and leaves a report book in its chest, and sends a copy to the
  owner. The report lists what was found and how to fix it.
- **Reports only:** inspections carry no penalties. The latest report for each office is also shown on the
  notice board (§6).
- At most one inspection per office per `Inspection.Interval_days` (default 7).

*Built, where it differs from the above:*
- **No inspector NPC of his own.** He travels with Central's postman, as the rate notices do (§4), so he needs no route
  of his own. Every hour Central looks over its offices, and a flagged office is sent one. On the postman's next visit
  the report is left in the office chest as a sheet of paper (*Inspection report*), not a book, because books in office
  chests are mail. An office whose chest can't be found isn't visited: its report goes straight to the owner.
- **The flags and their settings** (`Settings.Inspection.*`):
  - a missing or full office chest;
  - upkeep owed;
  - a postman stuck and reset `Stuck_resets` times (default 10) over the interval. This is counted in memory, so a
    restart starts the count again;
  - under `Min_on_time` (default 0.5) of mail on time, with `Settings.Stats.Min_deliveries` deliveries;
  - any letter lost.

  Each finding says what's wrong and how to fix it.
- **Where reports show:** `/postal inspect` shows the latest reports, and `inspect <office> now` sends one at once (for
  admins). The board's `inspections` panel shows the latest three, with their counts of findings.

## 6. Flavour

### Notice board

*Built (signs; the map frames come with the maps).*

Signs on a wall at Central, each showing one panel. An admin puts them on the board by looking at each and running
`/postal board add [panel]` (or giving its coordinates from the console); the sign must be within
`Settings.Board.Max_distance` blocks (default 32) of Central's chest. A sign line holds 15 characters, so each panel
is a title and three short lines:

- `postage` and `parcels`: the current base rates;
- `busiest`, `on_time` and `fewest_lost`: the top three of each leaderboard (§7);
- `notices`: the latest three, dated, in shorthand (a change of rates, an office opening);
- the Branch of the Month and inspection results will be panels of their own when they're built.

The board is redrawn every hour after the stats roll-up, and at once when the rates change or an office opens.
Right-clicking a sign puts its panel in full in the player's chat, since a sign has room for so little: every rate
(with the address price and the maximum discount), the leaderboards' top ten, the notices written out. Each notice
has two forms: shorthand for the sign (`19/10 New rates`, `19/10 Open Brookfield`) and the full text for chat
(`A letter, to another office: was $5.00, now $9.00`, `The Brookfield Post Office opened, chartered to Alex`); the
sign shows the latest three, chat the last ten.
Its signs are waxed so nobody edits them, and Central's protection covers them like its chest and sign. A sign that's
gone is dropped from the board, with a line in the console.

### Maps

*Built.*

Postal draws its own maps into ordinary map items (`maps/`), in place of the vanilla renderer:

- **Network map:** Central (blue banner), every office in Central's world (red banners, named) and Central's routes out
  to them (dashed). Bought from Central's clerk (*The network* card), given by an admin (`/postal map network`), or
  hung on the notice board (`/postal map frame`, fixed in an item frame).
- **Office map:** one office's area, with its routes walked from the office through every waypoint and its addresses
  (white banners, named). Sold at the office's counter (the office card); a local owner could later sell them at
  their own price.
- Both cost `Settings.Maps.Price` (default 10, free without an economy), paid to Central (`MAP_SALE` in the flow
  report).
- Each map's id is recorded (`Maps.m<id>` in config), so its renderer is put back when it loads again after a
  restart; a map is redrawn every few minutes, so it keeps up with new offices and addresses.

### Branch of the Month

*Built.*

Each period (`Settings.Awards.Period_days`, default 30, counted from the first time the server runs with it), the
office with the best score over the period is named Branch of the Month: announced to the server, posted as a notice,
and shown on the notice board's `award` panel. `/postal award` shows the holder and who leads this period; an admin
can name one at once with `/postal award now`.

- The score adds three parts, each weighted (`Settings.Awards.Weights.Deliveries`, `On_time`, `Losses`, 1 each): the
  office's deliveries as a share of the busiest office's, its on-time share, and its kept share (one less lost of
  delivered and lost). Offices need `Settings.Stats.Min_deliveries` deliveries in the period. Ties go to the busier
  office.
- The economy is closed, so the bonus (`Settings.Awards.Bonus`, default 0, *policy* `awards.bonus`) is paid only from
  Central's money **above its target**, the same money the dividend comes from (`AWARD` in the flow report). With too
  little there it's skipped, with a line in the console.

## 7. Stats

*Built.*

Inspections, leaderboards and Branch of the Month read the same per-office numbers: deliveries, the time
from pickup to delivery, and letters lost. They come from the mail store's event history (persistent-state
P1), so no new tracking is needed in the world. The stats module (`stats/`) summarises them every hour into an
`office_stats` table (schema 8): one row per office per UTC day, today's rewritten as the day goes on.

- **Sent:** mail an office picked up (its `AT_ORIGIN_BRANCH` event), counted for the letter's origin office.
- **Delivered:** mail an office delivered (`DELIVERED`), with the time since it was first picked up (its creation if
  it never was). A letter that crossed the network counts for the office here that delivered it.
- **Lost:** mail gone `MISSING`, counted for the office that held it: the sending office before it reached Central,
  the delivering office after. Lost at Central counts for no office.
- **On time** needs an expected delivery time. To start with it's worked out from the routes: an office's *round* is
  the time its postman takes to serve every address once (the recorded route times plus `Postman_cool_sec` each).
  Local mail may take two rounds (picked up on one, delivered on the next); mail between offices one round of the
  delivering office plus two of Central's (`Central_cool_sec` times the number of offices: to Central, then out).
  That's multiplied by `Settings.Stats.On_time_factor` (default 1.5) plus `On_time_grace` seconds (default 300).
- **Leaderboards** cover the last `Settings.Stats.Period_days` (default 30): busiest (deliveries), most punctual
  (on-time share) and fewest losses, the last two among offices with at least `Min_deliveries` (default 5).
  `/postal stats [office] [days]` shows them in chat, Central's counter shows the top three of each
  (*Leaderboards*), and `/postal stats refresh` summarises the history at once. The first summary goes back a period.

## 8. Phases

| Phase | Scope | Needs |
|---|---|---|
| **C1** | The clerk and the counter menu, charter notes (buy, open, expiry, optional approval), selling offices | Economy (#6) |
| **C2** | Base rates as policy, branch discounts, courier notices | C1 |
| **C3** | Stats module, notice board, maps, Branch of the Month | Persistent state P1 (#7) |
| **C4** | Inspector and reports | C3 |

## 9. Later

- **PO boxes:** designed separately (`docs/design/po-boxes.md`) as a local-office feature, not Central's.
- **Congestion:** dispatch slows as Central's hourly volume rises, with `/expedite` as the paid way past
  it. Off by default.
- **Insurance fund: replaced by lost-mail claims (built).** A paid fund would value parcels by something Postal can't
  check (a declared value) or a price list someone keeps, and an office owner could "lose" a friend's parcel from
  their own chest and share the payout. It's also unneeded: every record keeps what the mail carried. So Central
  pays nothing: at its counter a sender's *Lost mail* card lists their `MISSING` mail, and a claim either sends the
  record's copy again from Central or, for a parcel, gives its items back with the postage refunded. Nothing is
  created, so there's nothing to game. `/postal claim` is the admin side.

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
11. The clerk staffs Central whenever the mail is running, and is respawned and put back at its post if
    anything removes or moves it. Central is closed while the dispatcher is stopped.
12. Central gates only the owners' dealings with the server (charters, sales, rates), never the flow of mail.
13. Central's clerk, chest and sign are protected by Postal itself against griefing.
14. The charter note is a readable charter, worded from an editable template with placeholders, and opening
    an office with it has a ceremony (particles, bell, server-wide notice).
15. Charters need admin approval by default; `/postal charter autoapprove on|off` or the config turns that
    off. Applicants hear the decision by letter.
16. The counter is an inventory menu; forms that need typing use dialog screens (with a chat fallback, as the
    dialog API is experimental); the clerk greets and says goodbye in chat.
