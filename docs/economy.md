# Economy

Postal needs Vault or VaultUnlocked plus an economy plugin (EssentialsX, XConomy, TheNewEconomy, …).
It does **not** use Vault's bank API, so economies without bank support work.

Postal's economy is **closed**: it only moves money between accounts, never creates or destroys it.
The reasoning and the math are in [`docs/design/economy.md`](design/economy.md).

## Accounts

Central and every local post office have their own account in the server economy, the same way
Towny gives towns accounts:

| Office | Account name | UUID |
|---|---|---|
| Central | `postal-central` (or Towny's server account, see below) | derived from `va_postal:central` |
| Local office *X* | `postal-po-x` (lower-cased, max 32 chars) | derived from `va_postal:office:x` |

- UUIDs are name-based and carry **version 2**. EssentialsX treats v2 UUIDs as NPC accounts, and no real
  or offline-mode player can have one. They are derived on demand, never stored, so renaming an office
  gives it a new, empty account.
- With **VaultUnlocked**, Postal creates proper non-player accounts (`createAccount(uuid, name, false)`).
- With plain **Vault**, the accounts are ordinary player accounts held by an NPC `OfflinePlayer` stand-in.

Other plugins can pay into them like any account. EssentialsX leaves NPC accounts out of `/baltop` by
default (`npcs-in-balance-ranking` in its config); use `/postal bank` to see them.

### Sharing Central with Towny (opt-in)

With `Economy.Central_account: towny` and Towny's closed economy enabled
(`economy.closed_economy.enabled` in Towny's config), Central **is** Towny's server account: Postal's
fees and upkeep pool with Towny's taxes and town upkeep. If Towny or its closed economy isn't there,
Postal logs a warning and uses `postal-central`. Towny spends from that pool too, so the Postal day warns
when the pool holds less than Postal owes in refunds.

## Money flow

| Charge | Paid by | Goes to |
|---|---|---|
| Letter / parcel, same office | sender | ½ Central, ½ local office |
| Letter / parcel, other office | sender | ⅓ Central, ⅓ sending office, ⅓ destination office |
| COD surcharge | sender | ½ Central, ½ sending office |
| COD amount | recipient | the parcel's sender (works while they're offline) |
| `/distr` | sender | Central |
| Post office purchase | new owner | the office's floor seeds the office; the rest to Central (held for the refund) |
| Address purchase | new owner | ½ Central, ½ the address's office (each holds its half of the refund) |

An office's shares of postage, shipping and COD surcharges are its **revenue** for the day.

### Postage escrow

Postage, shipping and COD surcharges are paid **up front** but split **on delivery**, by the offices that
actually handled the mail:

1. **Addressing** (`/addr`, `/package`, `/cod`): the sender pays the **out-of-town** price (plus the COD
   surcharge), which Central holds. The letter or shipping label carries the hold. Re-addressing keeps its
   hold and costs nothing more.
2. **Pickup:** the office whose postman first picks the mail up (from an address chest or its own office
   chest) is its **sending office**. Where the sender stood when addressing it doesn't matter.
3. **Delivery:** the delivering office is the destination. If it's the sending office the mail was local:
   the local price is split ½ Central, ½ the office, and the difference goes back to the sender. Otherwise
   the out-of-town price is split in thirds. A COD surcharge goes ½ Central, ½ the sending office. Refunds
   reach senders who are offline.
4. **Never posted:** postage held for mail no postman picks up within `Postage.Hold_expiry_days` Postal days
   (default 7) is refunded. That mail stays where it is until it's re-addressed (and paid for again).

Held postage counts toward what **Central owes**, so it's never swept, paid out as a dividend or used to
seed offices. `/postal bank` shows the total held.

Mail can only be addressed within `Settings.Mail_office_distance` blocks (default 200, 0 for anywhere) of a
post office **in the same world**. There's one Central per server, in charge of every dimension; an office
in the nether is an ordinary local office.

**When a place changes hands**, the previous player owner gets back what they put in, if the accounts can
cover it (Postal never creates money for a refund):

- an **office**: its balance minus the address refunds it still holds in escrow (that includes the
  seed), plus Central's share of the price;
- an **address**: its price, half from Central and half from the office.

## Reserves

Postal runs a full reserve: every account keeps enough to pay every refund it could owe.

- An office **owes** half the address price for each player-owned address it serves. Its **reserve** is
  that plus the floor (`Economy.Office_floor`, default 500), which a purchase seeds, so a player-owned
  office never runs dry. The owner may take out anything above the reserve.
- **Central owes**, for each player-owned office, the office price less the seed, plus the other half of
  every player-owned address, plus all postage held in escrow. Its **target** is that plus `Economy.Central_buffer` (default 5000).
- Server-owned offices are kept at their reserve: topped up from Central's spare money when created or
  short, and swept back to Central when above it.

## The Postal day

Once per Postal day (24 hours by default, `Economy.Day_seconds`):

1. **Server-owned offices** settle to their reserve (surplus to Central, or topped up from it).
2. **Upkeep.** Every player-owned office pays Central
   `Base + Per_address × addresses + Per_waypoint × route waypoints + Revenue_rate × the day's revenue`.
   It's paid only from what the office holds **above its reserve**, never from the escrow or the seed;
   whatever it can't pay is recorded as **arrears** and blocks the owner's withdrawals until paid
   (deposits and dividends pay arrears first).
3. **Service dividend.** Central releases `Release_rate` of what it holds above its target (plus yesterday's
   carry, never more than that surplus) to player-owned offices in proportion to their work that day:
   revenue or deliveries (`Dividend.Basis`). Each office gets at most `Cap` × its revenue (at most 0.45, so
   mailing yourself never pays); the rest carries to the next day. Offices that handled no mail get
   nothing, and still pay upkeep.
4. The day's flows are logged.

The last day's time is saved (`Economy.Last_day`), so restarts don't skip or repeat one; after a long
downtime a single catch-up day runs.

## Commands

| Command | Who | Does |
|---|---|---|
| `/postal bank` | admin, console | Central's balance, what it owes and its target; every office's owner, balance, reserve, withdrawable and arrears |
| `/postal bank newday` | admin, console | run a Postal day now |
| `/postal bank report [days]` | admin, console | the flow log: today so far and the last days (in from players, out to players, internal moves, Central, carry, arrears) |
| `/postal bank policy [<setting> <value>]` | admin, console | show or change the economy settings at runtime (validated, saved to config) |
| `/postal office` | owner | your offices: balance, reserve, withdrawable, arrears |
| `/postal office <office> [balance]` | owner (admins: any) | one office |
| `/postal office <office> deposit <amount>` | owner | move money from you into the office |
| `/postal office <office> withdraw <amount\|all>` | owner | take money out, down to the reserve (not while in arrears) |

Transfers check both sides and undo the first if the second fails, so money is never lost or created.

## Settings

All under `Economy:` in `config.yml`; the ones marked *policy* can be changed live with
`/postal bank policy`.

| Setting | Default | Meaning |
|---|---|---|
| `Use` | false | turn the economy on |
| `Central_account` | postal | `postal` or `towny` (shared treasury) |
| `Day_seconds` | 86400 | length of a Postal day (*policy*: `day_seconds`) |
| `Office_floor` | 500 | the seed/working floor every office keeps (*policy*: `office_floor`) |
| `Central_buffer` | 5000 | Central keeps what it owes plus this before releasing a dividend (*policy*: `central_buffer`) |
| `Upkeep.Base` / `Per_address` / `Per_waypoint` | 50 / 5 / 0 | upkeep terms (*policy*: `upkeep.base`, …) |
| `Upkeep.Revenue_rate` | 0 | share of the day's revenue taken as upkeep, 0–1 (*policy*: `upkeep.revenue_rate`) |
| `Dividend.Basis` | revenue | `revenue` or `deliveries` (*policy*: `dividend.basis`) |
| `Dividend.Release_rate` | 0.5 | share of Central's surplus released per day, 0–1 (*policy*: `dividend.release_rate`) |
| `Dividend.Cap` | 0.4 | per-office dividend cap as a share of its revenue, 0–0.45 (*policy*: `dividend.cap`) |

| `Postage.Hold_expiry_days` | 7 | postage held for mail never picked up is refunded after this many Postal days |

Prices (`Postoffice.Purchase_price`, `Address.Purchase_price`, `Postage.*`) are unchanged. Daily counters,
carry, arrears, postage holds and the last 30 days of flows are kept in `plugins/Postal/economy.yml`.
`Settings.Mail_office_distance` (default 200) is under `Settings:`.
