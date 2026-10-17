# Deeds: addresses bought in person (design draft)

Status: **design agreed, not scheduled.** Shares its machinery with the Central Office's charters
(`docs/design/central-office.md`, branch `claude/clever-brahmagupta-goe25y`) and is planned to be built first.

Today a player gets an address through commands: someone with permission stands at a chest and runs
`/setaddr <office> <address>` (which also surveys the route), then `/setowner` to buy it. This design replaces
that, for players, with a visit to the local post office: buy a **deed** from its clerk, then use the deed on
the chest that will be the mailbox. The commands stay for admins.

## 1. Deeds, in general

A deed is a document, bought in person, bound to its buyer, and used on a chest to create something. Two
kinds share all the machinery:

| | Address deed (this design) | Charter (Central Office design) |
|---|---|---|
| Bought from | the local office's clerk | Central's clerk |
| Creates | an address (and its mailbox) | a post office |
| Approval | the office owner's choice; off by default | admin; on by default |
| Ceremony | for the player | server-wide |

Shared: the clerk's counter menu and dialog forms, the document item and its template, held payments, expiry,
refunds and reissue, the use-on-chest checks, and the records in the mail store.

## 2. The local office clerk

Every local office is staffed, like Central:

- **A clerk NPC per office**, "Postmaster of {office}" by default, standing at the office's counter. The owner
  places it with `/postal clerk here` while standing in their office (admins for server-owned offices);
  until then it stands beside the office chest.
- **Staffed whenever the mail runs**, exactly as Central's clerk: spawned with the dispatcher, respawned and
  put back at its post by the heartbeat if anything removes or moves it, gone while the dispatcher is stopped.
- **Protected:** invulnerable, can't be pushed, leashed, ridden or burned (as Central's clerk). The office
  chest itself keeps today's rules (owners protect their own buildings with claims).
- **Greets** players in chat and opens the office's **counter menu** on right-click. v1 services: *Buy an
  address* and *Collect a deed* (after approval), plus *Office info* (owner, price, residents). P.O. boxes
  (`docs/design/po-boxes.md`) add *Rent a box* here later.
- **Scale:** one NPC per office. The SMP test world (12 offices) is the yardstick; `Clerks.Local: false`
  turns local clerks off, and the counter menu then opens from the office sign instead.

## 3. Buying an address

1. **At the counter:** *Buy an address* opens a dialog: the address name (a suggested name is filled in;
   §5), the price, and Buy / Cancel. The name is checked against the office's addresses and reserved; the
   price is paid and held.
2. **Approval (if the office requires it):** the application waits for the owner, who sees it in their
   counter menu (*Applications*) or with `/postal office <office> applications`, and approves or denies it.
   The player hears by letter if they have an address (or a P.O. box, later), otherwise in chat at next
   login. A denial is refunded in full and releases the name. Without approval, step 3 follows at once.
3. **The deed:** a written book, *Deed of Address: {address}, {office}*.
4. **Using it:** holding the deed, the player right-clicks the chest that will be their mailbox. Postal
   checks that:
   - the player is the deed's holder;
   - the chest isn't already a Postal chest;
   - it's within `Settings.Mail_office_distance` of the office (as for addressing mail);
   - protection plugins and claims decide by themselves: a chest the player can't open never reaches Postal.
5. **The route survey decides.** Postal surveys a walking route from the office to the chest **before**
   creating anything (the surveyor that `/setaddr` already runs):
   - **A route is found:** the `[Postal_Mail]` sign goes on the chest, the address is created with its route,
     the player owns it, and the held payment is split as an address purchase is today (half to Central,
     half to the office; `docs/economy.md`). The deed is used up. A chime, particles over the chest, and
     *"Welcome to {address}, {office}!"*
   - **No route:** nothing is created and the deed stays in hand. The player is told how close the survey
     got and where it stopped ("no walkable way from the office; it ends 12 blocks short, near 104 64 -37"),
     so they can build a path and try again.

This also fixes a weakness of the commands: today an unreachable address is created anyway, and the problem
only shows when the postman gets stuck there.

## 4. The deed document

As the charter: a written book with an enchanted shimmer, a hidden deed id that Postal checks, and wording
from an editable template (`deeds.yml`, alongside the charter's). Placeholders: `{address}`, `{office}`,
`{world}`, `{server}`, `{holder}`, `{date}`, `{expires}`, `{deed_id}`, `{postmaster}`, `{price}`.

Default wording:

> **Deed of Address**
> **{address}, {office}**
>
> Know all by these presents that {holder} is granted the address of **{address}**, served by the {office}
> Post Office, in the world of {world}.
>
> Deed No. {deed_id}, entered {date}.

> The holder shall fix this deed upon the mailbox of their choosing, within reach of the {office} Post
> Office, on or before {expires}; unused, it lapses and the name is released.
>
> This deed is not transferable.
>
> {postmaster}, Postmaster of {office}

Like charters: bound to the buyer, expires after `Deeds.Expiry_days` (default 14) with a refund less
`Deeds.Expiry_fee` (default 10%), reissued at the counter if lost (the old one stops working), and recorded in
the mail store.

## 5. House names

A player names their own address, but the dialog always offers a suggestion, and a **Suggest another** button
draws a new one. The suggestions are in the spirit of British house names, which are names rather than
numbers: *Rose Cottage*, *The Old Forge*, *Willowbrook*, *Hawthorn House*.

- **Shapes:** `{Tree|Flower} Cottage`, `The Old {Trade}` (Forge, Mill, Bakery, Rectory, Dairy),
  `{Tree}{place ending}` (Ashdene, Oakhurst, Elmleigh, Hollycombe, Birchmead), `{Feature} View`
  (Meadow View, Hill View), `{Tree|Bird} House`, `{Bird}'s Rest`.
- **From the surroundings:** words are weighted by the biome at the chest, so a desert house might be
  *Sandstone House*, a snowy one *Frost Lodge*, one near water *Brookside* or *Mill Pond*, one on a hill
  *Hilltop* or *Windrush*.
- **Fits the post:** names are stored with underscores for spaces (`Rose_Cottage`), as address names already
  are, and kept to the 15 characters a sign line holds. A suggestion is never one the office already has.
- **Word lists** live in `names.yml`, so a server can give its world its own flavour (Welsh, Nordic,
  fantasy).

## 6. Prices and settings

| Setting | Default | |
|---|---|---|
| `Address.Purchase_price` | (existing) | the server default |
| per-office price | the default | owners set it at the counter or with `/postal office <office> price <amount>` |
| `Deeds.Require_approval` | false | the server default for new offices |
| per-office approval | the default | owners switch it in their counter menu or with `/postal office <office> approval on\|off` |
| `Deeds.Expiry_days` / `Expiry_fee` | 14 / 10% | |
| `Clerks.Local` | true | false: no local clerks; the counter opens from the office sign |

The price split, refunds and upkeep are unchanged (`docs/economy.md`). An owner's price only changes what a
new address costs.

## 7. Fits the code

- **Records (schema V6):** a `deed` table shared by both kinds (id, kind, holder, office, name, price,
  state, created, expires), so deeds survive restarts and expiry, refunds and reissue work from the record.
- **Address creation** goes through the same code as `/setaddr` (registration, sign, owner, survey); the
  survey simply runs first and decides.
- **Economy:** the held payment uses the same hold mechanism as postage escrow.
- **Clerks:** one NPC role, used at Central and at every office, built on the existing Postal NPC handling
  (never saved by Citizens, removed on stop).

## 8. Testing

- Unit: name generator (shapes, the 15-character limit, no duplicates in an office, biome weighting), deed
  expiry and refunds.
- Store contract: the `deed` table on SQLite and MariaDB, migration from V5.
- Smoke (console): a deed bought with an admin helper is used on a chest; an unreachable chest leaves the
  deed in hand; expiry refunds.
- Bot player: right-click the clerk, buy through the dialog, use the deed on a chest. The bot needs entity
  interaction, inventory clicks and dialog responses for this (it only chats and runs commands today).
- In game only: the clerk's greeting and menu look, the dialog, the deed's text, the ceremony.

## 9. Build order

1. Deeds framework, the local clerk and address deeds (this document).
2. Central's clerk and protection (Central Office §2).
3. Charters as the second deed kind (Central Office §3).
4. Office sales; then return-to-sender (#19); then P.O. boxes.

## 10. Decisions

1. Local offices have a staffed clerk, as Central does; it can be turned off server-wide.
2. Address purchases are instant by default; an office owner can require approval, and the server sets the
   default for new offices.
3. Address prices: a server default that office owners can change.
4. Players name their addresses; a British-style house-name generator suggests one and can draw another.
