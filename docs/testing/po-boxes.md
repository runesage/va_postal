# Test plan: P.O. boxes

What P.O. boxes promise (`docs/design/po-boxes.md`), and what this plan checks:
- **A player rents a numbered box** at a post office, by the day. The rent goes to the office (Central for a server
  office). Unpaid rent is owed, and the lease lapses after the grace period.
- **Letters can be addressed to a box**: `/addr <office> box<n> <renter>`. The postman files them into the box
  when he's back at the office.
- **Only the renter can see and take their mail**, from the office's box cabinet. Nothing can be put in.
- **When a box closes, its mail goes back** to the senders.

**Most of this is covered automatically.** The store tests cover leases, numbering, arrears and box mail on SQLite
and MariaDB. `ci/bot-test.sh` joins a headless player that rents, is charged, lapses, gets a letter filed, opens the
cabinet, takes the letter and has a closed box's mail sent back. It runs on SQLite and MySQL. There's no smoke phase,
because renting needs a player.

What the automated tests can't cover:
- how the cabinet window looks, and shift-click, drag, number keys and hoppers against it;
- the sign as a player places it, and breaking things near the cabinet;
- a real postman walking a returned letter home, and reading its "Returned to sender" page;
- your economy and your offices.

Allow about 30 minutes. You need two accounts (A rents the box, B sends to it) or one account plus the console.

## 0. Setup

Optional settings in `config.yml` under `Settings:`. Each is shown with its default:

```yaml
Settings:
  Boxes:
    Default_rent: 5        # a day, unless the office sets its own (/pobox price)
    Grace_days: 3          # days of unpaid rent before the lease lapses
    Max_per_player: 1
    Max_per_office: 0      # 0: no limit
    Rent_distance: 16      # blocks from the office chest to rent there
```

Pick a local post office with a working route; it's called **Town** below. Give both players some money.

## 1. The cabinet

1. As Town's owner (or an admin), place a barrel near the office, look at it and run `/pobox cabinet Town`.
   **Expect:** "The barrel at x y z is the Town Post Office's box cabinet", and a waxed `[Postal_Box]` / `Town`
   sign on the barrel's side facing you.
2. As a player who isn't the owner (not op), try to break the barrel and then the sign.
   **Expect:** both refused: "The box cabinet belongs to the Town Post Office".
3. Right-click the barrel as a player with no box.
   **Expect:** "You don't rent a box at the Town Post Office. /pobox rent town ($5 a day)". No barrel window opens.

## 2. Renting

1. As A, away from Town (more than 16 blocks from its chest): `/pobox rent Town`.
   **Expect:** "Rent a box at the office: within 16 blocks…".
2. At the office: `/pobox rent Town`. **Expect:** "You rent Box 1 at the Town Post Office, for $5 a day", and
   your balance down by 5.
3. `/pobox`. **Expect:** your box and its rent.
4. `/pobox rent Town` again. **Expect:** "You already rent Box 1 at Town (the most a player may)".
5. As the owner: `/pobox list Town` shows A's box. `/pobox price Town 2.5` and then `/pobox price Town` say $2.50.

## 3. Mail to the box

1. As B, write and sign a book, then at a post office run `/addr Town box1 <A>` and confirm with `/`.
   **Expect:** "Ready to address to: Town, Box1, A", and postage as for any letter.
2. Try `/addr Town box2` (nobody rents it). **Expect:** "Box 2 at Town isn't rented".
3. Try `/addr Town box1 <B>`. **Expect:** "Box 1 at Town is A's: the attention must name them".
4. Try addressing a parcel's shipping label to `box1`. **Expect:** "P.O. boxes take letters only, not parcels".
5. Post the letter from step 1 and wait for Town's postman to finish a round. It goes through Central first if B
   posted it at another office.
   **Expect:** A gets "There's mail in your Box 1 at the Town Post Office". `/pobox` shows "1 letter(s) waiting".

## 4. Taking mail out

1. As A, right-click the cabinet. **Expect:** a window titled "Box 1, Town" with the letter in it.
2. Try to put something in: drop an item from your inventory into an empty slot, shift-click an item up from your
   inventory, drag across slots, and press a number key over a slot.
   **Expect:** nothing goes in, and nothing is lost from your inventory.
3. Click the letter. **Expect:** it moves to your inventory, readable, and the window shows the box empty.
4. As B, right-click the cabinet. **Expect:** B never sees A's box. With no box of their own, B is told how to rent one.
5. With mail waiting, `/pobox release` as A. **Expect:** "still holds 1 letter(s). Take them out first."
6. Once it's empty, `/pobox release`. **Expect:** "You no longer rent Box 1 at Town".

## 5. Rent, arrears and lapse

Run Postal days by hand with `/postal bank newday` (console or admin).

1. A rents a box again. Run one `newday`. **Expect:** A pays the day's rent. In `/postal bank report`, `BOX_RENT`
   appears in the flows and Town's account (Central for a server office) is up by the rent.
2. Set A's money to 0 (`/eco set A 0`) and run `newday` once.
   **Expect:** A is told "You owe $… for your Box 1 at Town. It lapses after 3 days unpaid". `/pobox` shows "Owed".
3. Run `newday` until it lapses: four days in arrears with the default grace.
   **Expect:** "Your Box 1 at Town has lapsed…". `/pobox` shows no box, and the number is free again.

## 6. A closed box's mail goes back

1. A rents a box again. B owns an address somewhere on this server and sends a letter to `Town box1 <A>`. Wait for
   it to be filed (A is told).
2. As an admin, run `/pobox evict Town 1` without taking the letter out.
   **Expect:**
   - A hears "1 letter(s) left in your Box 1 at Town went back to their senders";
   - B hears "A letter of yours came back (Box 1 at Town was closed); the postman brings it to <B's address>";
   - the server log says "Letter … from a closed box goes back to its sender at …".
3. Wait for the postman to deliver to B's address. Open B's mailbox and read the letter.
   **Expect:** a last page, "Returned to sender", saying why. `/postal track last` (or the letter's id) shows the
   history ending `RETURNED`.
4. Repeat with a sender who owns no address on this server.
   **Expect:** the letter waits in Central's chest titled "Returned mail", and the sender is told.

## 7. What to report

For each step: what you did, what you expected, and what happened. For anything wrong, add the server log lines
around it.
