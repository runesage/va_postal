# Test plan: lost-mail claims

What claims promise (`docs/design/central-office.md` §9):
- **Lost mail is never lost for good.** Its record keeps what it carried.
- **Senders claim their own lost mail** at Central's counter. A letter or parcel can be sent again from Central, or
  a parcel's items can be taken back with the postage refunded.
- **Nothing is created.** A parcel's items come out of its record once, and the lost original is void if it turns
  up.

**Most of this is covered automatically.** A store contract test covers the lost-mail queries on SQLite and MariaDB.
Smoke phase 25 forces a letter and a parcel `MISSING`, then claims both from the console: each is sent again from
Central and lands in Central's chest. The bot player test claims at the counter: it takes a lost parcel's items back
(postage refunded, `RETURNED`), and sends a lost letter again (`AT_CENTRAL`).

What the automated tests can't cover:
- mail going missing the real way: a griefed chest, then the reconciler noticing;
- the chat message the sender gets when that happens;
- a resent letter delivered and read, and the old copy refused if it turns up.

Allow about 15 minutes. You need one player account and admin rights.

## 1. Mail goes missing

1. Post a letter to an address at another office, with your name as sender (`/addr`, then post it).
2. Before Central's postman collects it, open the office chest and take the letter out. Keep it.
3. Run `/postal reconcile`. **Expect:** you're told "A letter of yours for <address> went missing. Nothing is lost:
   claim it at Central's counter." The server log says the letter is `MISSING`.

## 2. Claiming a letter

1. At Central, right-click the Postmaster General. **Expect:** a *Lost mail* card (a recovery compass) saying 1
   letter or parcel of yours went missing.
2. Click it, then left-click the letter. **Expect:** "...it goes out again from here. If the old one turns up, it's
   void." The card shows nothing lost when you open the counter again.
3. Let the postmen run. **Expect:** the letter is delivered as normal, and `/postal track` shows the claim in its
   history.
4. Put the letter you kept in step 1.2 into a post office chest. **Expect:** it isn't delivered a second time.

## 3. Claiming a parcel back

1. Pack a parcel with a few named or enchanted items and post it. Take its label out of the office chest before
   it's collected, and run `/postal reconcile`.
2. At Central's counter, open *Lost mail* and right-click the parcel. **Expect:** its items in your inventory,
   exactly as packed, and "Postal refunded ... of postage for your lost parcel."
3. Try the same with a full inventory. **Expect:** you're asked to make room, and nothing changes.
4. Try `/accept` with the label you kept. **Expect:** "This order has already been filled."

## 4. Admin

1. `/postal claim`. **Expect:** every lost letter and parcel on the server, or "No mail is lost on this server."
2. `/postal claim <id>` for one of them. **Expect:** it's sent again from Central, and its sender is told if online.

## 5. What to report

For each step: what you did, what you expected, and what happened. For anything wrong, add the server log lines
around it.
