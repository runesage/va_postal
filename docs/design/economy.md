# Postal economy design

Status: **draft for review**. Decisions taken so far are marked *(decided)*; the rest are proposals.
User-facing behaviour of what ships today is in [`docs/economy.md`](../economy.md).

## 1. Goals

1. **Closed.** Postal never creates or destroys money. Every amount it moves comes out of one account
   and goes into another, so it can't inflate or deflate the server's money supply. *(decided)*
2. **Solvent.** Every account Postal runs can always pay everything it may be asked to pay: full
   reserve, no fractional reserve. *(decided)*
3. **Earned, not owned.** Owning an office costs money; running one earns it. No income for holding
   an office that handles no mail.
4. **Admin-steerable.** The few numbers that set how much money Postal holds and how fast it returns
   it are config values: those are the monetary-policy levers.

## 2. Accounts

| Account | Holder | Notes |
|---|---|---|
| Central | the server, always | `postal-central`, or Towny's closed-economy server account (§9) |
| Office *X* | the server, or the player who bought it | `postal-po-x` |

Players' own balances are their normal economy accounts. Account details (UUIDs, VaultUnlocked vs Vault)
are unchanged from `docs/economy.md`.

## 3. Notation

| Symbol | Meaning |
|---|---|
| `B_c`, `B_o` | balance of Central, of office *o* |
| `P`, `p` | office purchase price, address purchase price |
| `L_o` | office *o*'s liabilities: `p/2` for every player-owned address at *o* (the office's half of the refund it owes when that address changes hands) |
| `L_c` | Central's liabilities: `P` per player-owned office + `p/2` per player-owned address (its refund shares), + the insurance fund once it exists |
| `F` | working floor: minimum balance an office keeps for day-to-day refunds and upkeep (config) |
| `R_o` | office reserve requirement = `L_o + F` |
| `T` | Central's target balance = `L_c + buffer` (config) |

## 4. Money in and out

**Charges** (unchanged from today):

| Charge | Paid by | Goes to |
|---|---|---|
| Letter / parcel, same office | sender | ½ Central, ½ the office |
| Letter / parcel, other office | sender | ⅓ Central, ⅓ sending office, ⅓ destination office |
| COD surcharge | sender | ½ Central, ½ sending office |
| COD amount | recipient | the parcel's sender |
| `/distr` | sender | Central |
| Office purchase | buyer | Central (it holds the refund) |
| Address purchase | buyer | ½ Central, ½ the address's office (each holds its half of the refund) |

Every split hands out a fraction of what the sender paid, so mailing yourself is always a net loss
(½ of the postage for one office, ⅓ across two offices you own). No self-dealing loop creates money.

**Changes:**

- **Remove the blanket distribution** (today: every 1200 s Central splits everything above one office
  price evenly across all offices). It pays offices for existing, not for working.
- **Daily upkeep** (§6), **server-owned sweep** (§7) and the **service dividend** (§8) replace it.

Full reserve makes purchases self-funding: an address purchase deposits exactly the `p/2 + p/2` it adds
to `L_o + L_c`, and an office purchase deposits the `P` it adds to `L_c`. That money is escrow, never
profit, and full reserve simply keeps it from being spent.

## 5. Reserves and withdrawals *(decided: full reserve)*

- Office owner may withdraw `max(0, B_o − R_o)`, and may deposit any amount.
- Below `R_o`: withdrawals are blocked *(decided)*. Nothing else happens yet; seizure / bankruptcy is a
  later step (§10).
- Central has no player withdrawals. Its spending is refunds, the dividend (§8) and, later, insurance.
- Refunds keep today's rule: paid only if the paying account can cover them (with full reserve it always
  can, unless an admin or another plugin moved money out).

## 6. Daily upkeep (Towny-style)

Once per Postal day, every **player-owned** office pays upkeep to Central:

```
upkeep_o = upkeep.base + upkeep.per_address × (addresses at o)
```

- Paid from `B_o`, and allowed to dip into the floor `F` (that's what `F` is for), never into `L_o`.
- If it can't pay: the office is marked **in arrears**, withdrawals stay blocked, and the debt is
  recorded. Towny's bankruptcy model (a debt cap, then the office reverts to the server) is the planned
  follow-up, not part of this step.
- Server-owned offices pay none (they'd be paying Central from Central's own money, see §7).

Upkeep is what makes holding an idle office cost something. On its own it would also drain players over
time, which is why §8 exists.

## 7. Server-owned offices *(decided)*

They keep up to `F` as working balance; each Postal day everything above `F` is swept to Central.

## 8. Service dividend (proposal)

Central returns its surplus to players, in proportion to work done.

Once per Postal day:

```
surplus  = max(0, B_c − T)
pool     = k × surplus + carry                       (k = release rate, 0..1)
share_o  = pool × revenue_o / Σ revenue              (revenue_o = postage + shipping the office handled that day)
paid_o   = min(share_o, cap × revenue_o)             (cap < ½ so self-mailing stays a loss)
carry    = pool − Σ paid_o                           (rolls to tomorrow)
```

- Only **player-owned** offices receive it (server-owned ones would just sweep it back).
- An office with no mail handled gets nothing, and still pays upkeep: money moves from idle offices to
  active ones, which is the anti-leech property.
- The cap keeps farming unprofitable: mailing yourself one letter costs `x`, returns at most `x/2` (the
  office share) `+ cap·x` (dividend) `< x`.

### Why Postal neither drains nor hoards

Let `I` be Central's income per day (its postage shares + upkeep + `/distr`). Ignoring the cap, Central
settles where income equals payout:

```
I = k (B_c − T)   ⇒   B_c* = T + I / k
```

So Central holds what it owes plus about `1/k` days of income, a bounded amount, and everything else
returns to players through dividends, withdrawals and refunds. Total money inside Postal is therefore
roughly `Σ L_o + Σ F + T + I/k` plus whatever owners haven't withdrawn yet: it grows only as places are
bought (escrow) and shrinks as they're sold. In steady state, money paid into Postal each day equals money
paid out.

**Levers:** `buffer` (how much slack Central keeps above `L_c`), `k` (how fast surplus returns), `cap`
(how much of the dividend activity can earn), `upkeep.*` (cost of holding an office), and prices.

## 9. Towny shared Central *(decided: opt-in)*

`economy.central_account: postal` (default) or `towny`.

With `towny` and Towny's `economy.closed_economy` enabled, Central **is** Towny's server account, so
Postal fees and upkeep pool with Towny's taxes and town upkeep. If Towny's closed economy is off (or Towny
is missing), Postal logs a warning and uses `postal-central`.

Caveat: Towny spends from that pool too. Postal keeps its own record of `L_c` (and of how much of the pool
it has paid in and out), uses `T` against the shared balance, warns admins when the pool falls below
`L_c`, and never pays a dividend from money the pool doesn't have above `T`.

## 10. Later

- **Pay on delivery.** Hold the office shares of postage in Central until the letter is actually delivered
  (needs the per-letter records from `docs/design/persistent-state.md`, phase P2). Ties income to service
  even more tightly than §8.
- **Lender of last resort / bankruptcy.** Central covers an office's shortfall, the office carries a debt,
  past a debt cap the office reverts to the server (Towny-style). This is also the hook for a fractional
  reserve if that's ever wanted.
- **Insurance fund** as a Central liability inside `L_c`.

## 11. Commands

- `/postal bank` (admin): Central's `B_c`, `L_c`, `T`; every office's owner, `B_o`, `R_o`, withdrawable,
  arrears.
- `/pobank <office> [balance | deposit <amount> | withdraw <amount>]` (owner; name to be decided).

## 12. Config (proposed)

```yaml
Economy:
  Central_account: postal          # postal | towny
  Day_seconds: 86400               # length of a Postal economy day
  Office_floor: 500                # F
  Central_buffer: 5000             # T = L_c + this
  Upkeep:
    Base: 50
    Per_address: 5
  Dividend:
    Release_rate: 0.5              # k
    Cap: 0.4                       # of the revenue an office handled
```

Defaults are placeholders, to be tuned against the existing prices (postage 4/6, shipping 10/15,
office 5000, address 500).

## Open questions

1. The service dividend (§8): right mechanism? Pro-rata by revenue handled, or by deliveries completed?
2. Upkeep shape: flat + per address, as above?
3. Postal day: a fixed 24 h, or follow Towny's day when Towny is installed?
