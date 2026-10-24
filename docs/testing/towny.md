# Test plan: Towny post office plots

What post office plots promise (`docs/design/towny.md` §1-5):
- **A town's post office stands on a post office plot**, which only the mayor or an assistant can make.
- **The office belongs to whoever owns the plot**: the mayor while the town owns it. It changes hands with the plot.
- **The plot pays a daily tax to the town**, from its owner's own money. If the owner can't pay, the plot and the
  office go back to the mayor.
- **The box cabinet stands on the office's plot.**
- **Addresses go on plots their owners own**, or on the town's plots for the mayor and assistants, and belong to
  the plot's owner.

**CI can't cover any of this: it has no Towny.** Unit tests cover the rules (`PostOfficePlotsTest`). The plot type,
the refusals and ownership following the plot were also run on a local server with Towny 0.103.2.9 and two
headless bots, without an economy, as were the address deed refusals and acceptances. What still needs a
real server:
- tax collection at Towny's new day;
- the office going to the mayor when a plot owner can't pay;
- an address following a plot sale when the postman next delivers there;
- the messages as players see them.

Allow about 30 minutes. You need Towny with an economy (Vault and an economy plugin), Postal with
`Settings.Towny.Opt_in: 'true'` and `Economy.Use: 'true'`, admin rights, and two player accounts: a **mayor** and a
**resident** of the same town. Charter approval is quicker with `/postal charter autoapprove on`.

## 1. The plot type

1. Restart the server and watch the log. **Expect:** `[Towny] API: A new townblock type was registered: postoffice`
   before Towny's `Loaded 10 townblock types: ... postoffice ...`.
2. As the resident, standing on a plot you own, run `/plot set postoffice`. **Expect:** refused (by Towny's
   permissions, or by Postal: "Only the mayor or an assistant can make a post office plot").
3. As the mayor, standing on a town plot, run `/plot set postoffice`. **Expect:** "Plot type set to postoffice", and
   a `P` for the plot on `/towny map`.
4. Restart the server. **Expect:** `/plot info` on that plot still says `postoffice`.

## 2. Opening an office

1. As the resident, buy a charter at Central and use it on a chest on the post office plot (the town still owns
   it). **Expect:** "This post office plot still belongs to <town>: buy it first (/plot claim), or ask the mayor."
2. Use it on a chest elsewhere in the town. **Expect:** "This is in <town>: a post office there must be on a post
   office plot."
3. As the mayor, use a charter on the post office plot. **Expect:** the office opens, and belongs to the mayor.
4. Use another charter on the same plot. **Expect:** "There's already a post office on this plot."

## 3. The office follows the plot

1. As the mayor, on the office's plot: `/plot forsale 100`. As the resident: `/plot claim`. **Expect:** the resident
   is told the office is theirs; the mayor that it passed with its plot. The office sign's last line names the
   resident. The office's balance (`/postal bank`) is unchanged.
2. As the resident, `/postal office <office> sell <mayor> 10`, then sell it back to Central at the counter.
   **Expect:** both refused: "...it changes hands with the plot. Sell the plot instead (/plot forsale)".
3. As the mayor, `/plot set shop` on the office's plot. **Expect:** "The <office> Post Office stands on this plot:
   it stays a post office plot while the office is here."
4. As the resident, `/plot unclaim`. **Expect:** the office goes back to the mayor ("The town owns the plot, so its
   mayor runs the office").

## 4. Tax

1. Set `Settings.Towny.Post_office_plot.Tax: 25` in Postal's config and restart. Let the resident own the office's
   plot again, with at least 25 in their own balance.
2. `/ta newday`. **Expect:** 25 is taken from the resident's own balance, not from the office account, and goes to
   the town bank.
3. Empty the resident's balance and `/ta newday` again. **Expect:** Towny takes the plot back for the town, and
   within moments Postal gives the office to the mayor, telling both. The office's account and any arrears are
   unchanged.

## 5. Box cabinet

1. As the office's owner, `/pobox cabinet <office>` looking at a barrel on another plot. **Expect:** "The box
   cabinet must stand on the post office's plot."
2. Look at a barrel on the office's plot. **Expect:** it becomes the cabinet.
3. As an admin, a barrel off the plot. **Expect:** the same warning, then "Admin override", and it's set.

## 6. Addresses on plots

1. As the resident, buy an address deed at the office's counter and use it on a chest on a town-owned plot.
   **Expect:** "This plot belongs to <town>: an address goes on a plot you own (buy one with /plot claim), or ask the
   mayor."
2. Claim that plot (`/plot claim`, after the mayor puts it up with `/plot forsale`) and use the deed again.
   **Expect:** the address is made, and is yours.
3. As the mayor, use a deed on a chest on the resident's plot. **Expect:** "This chest is on someone else's plot".
4. As the mayor, use a deed on a town-owned plot. **Expect:** the address is made, and belongs to the mayor.
5. Sell that plot to the resident, then let the postman deliver a letter there. **Expect:** the address now
   belongs to the resident (the log says it "follows its plot").

## 7. What to report

For each step: what you did, what you expected, and what happened. For anything wrong, add the server log lines
around it.
