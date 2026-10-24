# Towny: post office plots and addresses on plots

Status: **post office plots built** (§1-5); deeds on plots (§6) next. Optional: none of this applies unless Towny is
installed and `Settings.Towny.Opt_in` is `'true'`. Without Towny, offices, charters, cabinets and deeds work as in
`docs/design/central-office.md` and `docs/design/deeds.md`.

Towny already divides a town into plots (town blocks), with owners, prices and a daily tax. Postal hangs its offices
and addresses on them, so that a town's post is something the town decides and pays for:

- a town's post office stands on a **post office plot**, a plot type Postal adds to Towny;
- **the office belongs to whoever owns that plot**, and changes hands with it;
- the plot pays a **daily tax to the town**, from its owner's own money, as shops do;
- **P.O. box cabinets** stand on the office's plot;
- **addresses** go on plots their owners own (§6).

No backwards compatibility: Towny offices made under the old rules (the mayor always owns the office) aren't
migrated. The old rule is gone; an office already in a town is a town office only if it stands on a post office plot.

## 1. The post office plot type

When Towny is in use, Postal registers a plot type called `postoffice`. A mayor or one of the town's assistants
makes a plot one with Towny's own command, standing in it:

```
/plot set postoffice
```

Only the mayor or an assistant may: Postal refuses anyone else, whatever their Towny permissions, because making the
plot is the town's approval of the office (§2). Towny's `towny.command.plot.set.postoffice` permission is needed as
well; mayors and assistants have it through Towny's usual `towny.command.plot.*`.

| Setting (`Settings.Towny.Post_office_plot`) | Default | |
|---|---|---|
| `Tax` | `0` | daily tax on the plot; `0` means the town's own plot tax (`/town set plottax`) |
| `Cost` | `0` | what setting a plot to `postoffice` costs, paid as Towny charges for plot types |
| `Map_key` | `P` | the letter for post office plots on `/towny map` |

Postal registers the type as it loads, before Towny reads its plots, so post office plots survive restarts. A
server can instead define `postoffice` in Towny's own `townblocktypes` config; Towny's definition then wins.

A post office plot that has an office on it can't be changed to another type: Postal refuses with the office's
name. Close or move the office first (admins: `/postal office <office> ...`).

## 2. Opening an office in a town: approval by plot

A charter (bought at Central as today) opens an office where it's used. Inside a town, the chest must be on a post
office plot, and:

- the charter's holder **owns the plot**, or
- the plot is still the town's and the holder is **the mayor**: a mayor may found the office on a town-owned plot.
  It then belongs to the mayor (and passes to whoever buys the plot).

And the plot has no office already: one office per plot. Anywhere else in a town the charter is refused with what
to do ("ask the mayor for a post office plot"). Outside towns charters work as before.

That makes approval free of extra steps: the mayor (or an assistant) approves an office by making a post office
plot and selling or giving it to the player (`/plot forsale`, `/plot claim`, Towny's own commands). Charter
approval at Central (`Settings.Charters.Require_approval`) is a separate, server-wide check and still applies.

## 3. The office is the plot

The owner of a town office is always its plot's owner:

| The plot | The office belongs to |
|---|---|
| owned by a resident | that resident |
| owned by the town (no resident) | the town's mayor |
| the town has no mayor, or its mayor or owner is an NPC | the server |

Postal follows the plot as Towny changes it: when a plot is claimed, bought, given up or taken back, and after
Towny's new day (when unpaid taxes take plots back), and whenever Central's postman calls at the office. The office
keeps its account and its arrears; only the owner changes, and the office sign is put up again with the new name.
Both owners are told (by notice if they're offline).

So town offices **change hands only with their plot**: Postal's own office sales (offers between players, selling
back to Central) are refused for them, with "sell the plot instead (`/plot forsale`)". The buyer pays the seller
(or the town) through Towny, and the office's account goes with the plot.

## 4. Tax

The post office plot pays a daily tax to the town bank, set by `Tax` above (or the town's plot tax). **Towny**
collects it, at its new day, from the plot owner's **personal** balance: Postal's office accounts (which pay upkeep
to Central, `docs/economy.md`) aren't touched, so the postal system stays solvent whatever the town charges.

If the owner can't pay, Towny takes the plot back for the town, as for any plot. The office then passes to the
mayor (§3) with its account and any arrears: the mayor covers the office's debts until they sell the plot again.

The mayor can exempt the plot (`/plot toggle taxed`), as with any plot.

## 5. P.O. box cabinets

An office on a post office plot keeps its box cabinet on the same plot (the same town block): `/pobox cabinet` on a
barrel elsewhere is refused. Admins are told and may go ahead. Offices outside towns are unchanged.

## 6. Addresses on plots (next)

Inside a town:

- an address deed is used on a chest **on a plot the player owns**; the house name suggested is the plot's name,
  if it has one;
- the mayor or an assistant may put an address on a **town-owned** plot; it belongs to the town (the mayor) and
  passes to whoever buys the plot, by the existing rule that an address on a plot belongs to its owner;
- a chest on someone else's plot is refused.

## What can only be tested in game

CI has no Towny. Unit tests cover the rules (`PostOfficePlotsTest`: who owns an office, where a charter or cabinet
may go); the plot type, tax collection, ownership following plot sales and the refusals need a server with Towny
(`docs/testing/towny.md`).
