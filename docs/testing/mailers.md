# Test plan: mass mailers

What mass mailers promise (`docs/design/central-office.md` §9):
- **An office's owner mails its subscribers**, players who opted in.
- **A Towny mayor or assistant mails the town's residents.** They get it until they opt out.
- **An admin mails every address on the server.**
- **Each copy lands in the player's own mailbox** and expires like a `/dist` copy. Each copy costs the distribution
  postage.

**Most of this is covered automatically.** Unit tests cover who a list reaches (opt-in, opt-out, repeats, people
who've left a town) and the cost. A store contract test covers the lists on SQLite and MariaDB. The bot player test
subscribes, sends to an office's list (the copy lands in the bot's mailbox), unsubscribes (the list then reaches
nobody), and checks the town and server targets.

What the automated tests can't cover:
- **Towny:** the test servers don't run it, so mayors, assistants, residents and opting out are untested.
- **Several players at once:** subscribing from other accounts, and copies landing in their mailboxes.
- **The money:** your economy, and what a full mailbox does.

Allow about 20 minutes. You need two accounts (A sends, B receives), each with an address, and admin rights. For
section 3 you need Towny with a town that A is mayor of and B lives in.

## 1. An office's subscribers

1. As A, own an office (**Town** below). As B, `/postal subscribe Town`. **Expect:** "You're on the Town Post
   Office's mailing list". `/postal lists` shows it.
2. As A, hold a signed book and `/postal mailer Town`. **Expect:** "A mailing to the Town Post Office's subscribers:
   1 mailbox", and the price per copy.
3. `/postal mailer Town confirm`. **Expect:** "Your mailing went to 1 mailbox". B finds a `[Distribution]` copy in
   their mailbox, readable, with A's pages. A is charged for one copy.
4. As B, `/postal unsubscribe Town`. As A, plan again. **Expect:** 0 mailboxes.
5. As B (not Town's owner), try `/postal mailer Town`. **Expect:** "Only the owner of the Town Post Office mails its
   subscribers."

## 2. The whole server

1. As an admin, `/postal mailer server`. **Expect:** the number of players with an address. Confirm, and check a
   couple of mailboxes.
2. As a non-admin, try it. **Expect:** "Only admins mail the whole server."

## 3. A Towny town (Towny needed)

1. As A, the mayor, `/postal mailer town`. **Expect:** "A mailing to <town>'s residents: N mailboxes", counting every
   resident with an address, B included, without anyone subscribing.
2. Confirm. **Expect:** B gets the copy.
3. As B, `/postal unsubscribe town`. **Expect:** "You won't get <town>'s town mail any more." A's next plan counts one
   fewer.
4. As B, `/postal subscribe town`. **Expect:** back on the list.
5. As a resident who isn't mayor or assistant, try `/postal mailer town`. **Expect:** refused.
6. Make a resident an assistant (`/town rank add <name> assistant`) and try again. **Expect:** allowed.

## 4. What to report

For each step: what you did, what you expected, and what happened. For anything wrong, add the server log lines
around it.
