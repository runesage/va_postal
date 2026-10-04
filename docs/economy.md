# Economy

Postal needs Vault or VaultUnlocked plus an economy plugin (EssentialsX, XConomy, TheNewEconomy, …).
It does **not** use Vault's bank API, so economies without bank support work.

## Office accounts

Central and every local post office have their own account in the server economy, the same way
Towny gives towns accounts:

| Office | Account name | UUID |
|---|---|---|
| Central | `postal-central` | derived from `va_postal:central` |
| Local office *X* | `postal-po-x` (lower-cased, max 32 chars) | derived from `va_postal:office:x` |

- UUIDs are name-based and carry **version 2**. EssentialsX treats v2 UUIDs as NPC accounts, and no real
  or offline-mode player can have one. They are derived on demand, never stored, so renaming an office
  gives it a new, empty account.
- With **VaultUnlocked**, Postal creates proper non-player accounts (`createAccount(uuid, name, false)`).
- With plain **Vault**, the accounts are ordinary player accounts held by an NPC `OfflinePlayer` stand-in.

Other plugins can pay into them like any account. EssentialsX leaves NPC accounts out of `/baltop` by
default (`npcs-in-balance-ranking` in its config); use `/postal bank` to see them.

## Money flow

| Charge | Paid by | Split |
|---|---|---|
| Letter / parcel, same office | sender | ½ Central, ½ local office |
| Letter / parcel, other office | sender | ⅓ Central, ⅓ sending office, ⅓ destination office |
| COD surcharge | sender | ½ Central, ½ local office |
| COD amount | recipient | 100 % to the parcel's sender (works while they're offline) |
| `/distr` | sender | 100 % Central |
| Post office purchase | new owner | 100 % Central (held to fund the refund) |
| Address purchase | new owner | ½ Central, ½ the address's office |

When an office or address changes hands, the previous player owner is refunded the purchase price
(and, for offices, gets the office's balance) **only if Central (and the office, for addresses) can
cover it**. Postal never creates money to pay a refund.

Central keeps what it collects; it no longer hands its surplus out to every office on a timer (that paid
offices for existing rather than for working). The design for upkeep, owner withdrawals and an
activity-based dividend is in [`docs/design/economy.md`](design/economy.md).

## Reserves and `/postal bank`

Postal runs a full reserve: every account keeps enough to pay every refund it could owe.

- An office **owes** half the address price for each player-owned address it serves, and keeps that plus
  a working floor (`Economy.Office_floor`, default 500) as its **reserve**. A player owner may take out
  anything above the reserve.
- **Central owes** the office price for each player-owned office plus the other half of every player-owned
  address. It aims to hold that plus `Economy.Central_buffer` (default 5000).

`/postal bank` (admin, or console) shows Central's balance, what it owes and its target, and for every
office its owner, balance, reserve and what the owner may withdraw. `/postal bank newday` runs a Postal day
immediately.

Office owners manage their office's money with `/postal office`:

| Command | Does |
|---|---|
| `/postal office` | your offices: balance, reserve, withdrawable |
| `/postal office <office> [balance]` | one office (admins and the console can view any) |
| `/postal office <office> deposit <amount>` | move money from you into the office |
| `/postal office <office> withdraw <amount\|all>` | take money out, down to the reserve |

Below its reserve an office's withdrawals are blocked. Each transfer checks both sides and undoes the first
if the second fails, so money is never lost or created.

## The Postal day

Daily economy actions run once per Postal day: 24 hours by default (`Economy.Day_seconds`). The time of the
last day is saved (`Economy.Last_day`), so restarts don't skip or repeat one; after a long downtime a single
catch-up day runs.

## Upgrading from v4.x

v4 kept balances in Vault banks named `Central` and after each town. Those balances are **not** migrated.
Move them into the new accounts by hand with your economy plugin's admin commands before removing the
old banks.
