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

Office balances therefore appear in baltop and can be paid into by other plugins.

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

Every 1200 s, Central keeps one post office purchase price in reserve and splits the rest evenly across
the local offices.
