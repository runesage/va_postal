#### `/postal`
Permissions: `none`
Displays a menu with commands available to the player.
<br>
#### `/postal network`
Permissions: `none`
On a network of servers: when the mail ship (or whatever `Network.Vehicle` calls it) next leaves for the other servers, how many letters are waiting at Central for it, what's on its way here, and the other servers.
<br>
#### `/postal stats [PostOffice] [days] | refresh`
Permissions: `none` (`refresh`: `postal.admin`)
The post offices' leaderboards over the last `Settings.Stats.Period_days` (default 30) or the days given: the busiest (most deliveries), the most punctual (share delivered on time) and the fewest losses (letters gone missing while the office held them); offices need `Settings.Stats.Min_deliveries` (default 5) deliveries to rank on the last two. With an office, its own numbers: picked up, delivered, lost, on time and the average time from pickup to delivery. The numbers come from the mail store's history, summarised every hour; `refresh` summarises it now. Also on Central's counter (*Leaderboards*). See `docs/design/central-office.md` §7.
<br>
#### `/postal board add [panel] [x y z [world]] | remove [x y z [world]] | list | refresh`
Permissions: `postal.admin`
Central's notice board: wall signs within `Settings.Board.Max_distance` (default 32) blocks of Central's chest, each showing one panel: `postage` (letter rates), `parcels` (parcel rates and the COD surcharge), `busiest`, `on_time`, `fewest_lost` (the leaderboards of `/postal stats`) or `notices` (the latest three, dated: rate changes, offices opening). `add` puts the sign you look at (or the one at the coordinates) on the board, showing the panel named or else the next one the board doesn't show yet; `remove` takes it off. The signs are redrawn every hour with the stats and at once when the rates change or an office opens; they're waxed, and Central's protection covers them. Anyone can right-click a sign for its panel in full in chat: every rate, the leaderboards' top ten, the last ten notices written out (the sign has shorthand: `19/10 New rates`; chat says which rate, from what to what). See `docs/design/central-office.md` §6.
<br>
#### `/postal map network|<PostOffice> [player] | frame [x y z [world]] | list`
Permissions: `postal.admin`
Postal maps: map items Postal draws itself. The network map shows Central, every office in its world and Central's routes out to them; an office map shows one office, its routes and addresses, with their names. `network` or an office gives one to you (or the player named); `frame` hangs a network map, fixed, in the item frame you look at (or the one at the coordinates), for the notice board; `list` lists the maps Postal draws. Players buy them at the counters: the network map at Central's (*The network*), an office's own at its counter (the office card), for `Settings.Maps.Price` (default 10, free without an economy), paid to Central. Maps keep working after a restart, and are redrawn every few minutes so they keep up with the network. See `docs/design/central-office.md` §6.
<br>
#### `/postal <start/stop/admin/chests/talk/quiet/speed>`
Permissions: `postal.admin`
Administrator commands for postal:
##### `speed <0.5 - 2.0>`
NPC walking speed factor
##### `admin`
Disables all security for 60 seconds, **_for all users_**
##### `bypass`
Disables all security for 5 minutes, for all admins.
##### `start`
Starts postal, initiates all queues, and spawns postmen if necessary.
##### `stop`
Stops postal, pauses all queues, and leaves the postmen for the citizens API to clean up.
##### `restart`
Restarts postal.
##### `chests`
Lists all chests and locations _(only works when running)_
##### `track <mail id | recent>`
Shows a tracked letter's state, where it is, and its full history; `recent` lists the ten most recently changed letters.
##### `reconcile`
Runs the mail reconciliation check now (normally every 5 minutes), for loaded chunks: finishes interrupted moves, marks letters missing from their chests as MISSING, removes stale copies and flags forged postal books.
##### `survey <PostOffice> [address | missing | all]`
Surveys routes: Postal finds a walking route from the post office to the address over the blocks as they are now, and saves it as an ordinary route (so `/showroute` shows it and `/setroute` can still adjust it). `missing` (the default) surveys the addresses that have no route, `all` replaces every route in the office. A new address is surveyed automatically when it's registered with `/setaddr`. The survey keeps to roads and stairs, walks through doors and gates, climbs ladders, and won't swim, cross crops or drop more than three blocks; if there's no such way, it says how close it got.
##### `testparcel <from PostOffice> <to PostOffice> <address> [cod] [retired]`
Packs a test parcel (an enchanted, renamed diamond sword, oak logs, golden apples) in a chest beside the from-office, locks it and hands its label in at that office, as a player would after `/package`. Optional COD amount. With `retired`, the parcel also carries an item recorded under an id this Minecraft doesn't have, as if an upgrade had removed it: whoever opens the parcel gets everything else, plus a message naming what was left out.
##### `recover <mail id> [x y z]`
Rebuilds lost mail from its record: a parcel's items in a new chest at x y z (or two blocks in front of you), a letter into your inventory. The record closes as RECOVERED, so the original is never routed or accepted.
##### `accept <mail id> [x y z]` / `refuse <mail id>`
Accepts (items in a chest at x y z, no COD) or refuses (items back where it was packed) a delivered parcel for its recipient. For testing without a second player.
##### `setstate <mail id> <state>`
Forces a record into a state, keeping where it is. Testing only; recorded in its history as forced by an admin.

`last` can be used in place of a mail id for the newest mail.
##### `store`
The mail store's health: SQLite or MySQL, the schema version, this server's id, how many calls it has made and how long they take (average and slowest, and the time spent on the main thread), failures, and the servers sharing the database. With MySQL, watch the main-thread time: every call waits for the database.
##### `directory [server]`
The network directory: the offices each server has published, with their address counts, or one server's offices and addresses. Each server republishes its own every minute when they change.
##### `whois <player>`
Where the network last saw a player (server, online or when last seen), as `/addr` finds them.
##### `testletter <from PostOffice> <to PostOffice|server:office> <address> [player]`
Writes a tracked test letter and hands it in at the from-office, as a player would; the destination can be another server's office.
##### `testletter <from PostOffice> <to PostOffice> <address>`
Writes a tracked test letter and hands it in at the from-office's chest, as a player would. For testing routes without a player.
##### `deed <give | sell | apply | list | names | reissue | expire | test> ...`
Address deeds (`docs/design/deeds.md`): an address bought as a signed document and created where its holder right-clicks a chest with it. Postal first surveys a route from the office to the spot in front of the chest; if the postman can't get there, nothing is made, the deed stays in hand, and the holder is told where the route stopped. Players buy deeds from an office's clerk (see `clerk` below); staff can also issue them:
- `give <player> <office> [name]`: a free deed (no name: the office suggests a house name). `sell` is the same, charged the address price, which Central holds until the deed is used (then half goes to the office) or lapses.
- `apply <player> <office> [name]`: an application at the office's price, as if made at its counter (`server` for a server-held one, for tests).
- `list [player]`: a player's deeds and their state. `names <office> [count]`: house names the office would suggest (Rose Cottage, The Old Forge, Ashdene...; the words are in `names.yml`, the deed's wording in `deeds.yml`).
- `reissue <player> <deed>`: a new copy of a lost deed; older copies stop working.
- `expire`: lapses the deeds whose time is up now (they're checked hourly); a lapsed deed is refunded less `Settings.Deeds.Expiry_fee` (default 10%). Deeds last `Settings.Deeds.Expiry_days` (default 14).
- `test <office> <name> <x> <y> <z> [world]`: uses a server-held deed on the chest at x y z, for testing from the console.
##### `charter <pending | approve | deny | autoapprove | give | sell | apply | test> ...`
Charters (`docs/design/central-office.md` §3): a post office bought as a signed document, a *Charter of {office} Post Office*, and opened where its holder right-clicks a chest with it. The name is checked when it's bought (no office here, no charter held for it, no office of that name on another server of the network) and reserved until the charter is used or lapses; Central holds the price (the post office purchase price) meanwhile. Using it opens the office at that chest: its sign goes on, the holder owns it, the payment seeds it as an office purchase does, and everyone hears *"A new post office has opened in {office}!"* (`Settings.Charters.Announce`; the bell and particles: `Effects`). The chest must have room in front for the postman and be at least `Settings.Charters.Min_distance` (default 64) blocks from any other office and from Central. A new office has no addresses: it opens for mail with its first (sold at its clerk's counter as usual). The wording is in `charter.yml`. Charters lapse after `Settings.Deeds.Expiry_days` like deeds, refunded less the expiry fee.
- `pending`: applications waiting for a decision. Applications need an admin's approval while `Settings.Charters.Require_approval` is on (the default); `autoapprove on|off` switches it (and saves it).
- `approve <id | name | all>`, `deny <id | name> [reason]`: decides them. The applicant hears by letter from the Postmaster General if they have an address, in chat if online, else when they next join; an approved charter is handed over at once if they're online (otherwise collected at Central's counter); a declined one is refunded in full and its name is free again.
- `give <player> <office>`: a free charter; `sell`: the same at the post office price; `apply`: an application at that price, as made at Central's counter (`server` holds it, for tests).
- `test <office> <x> <y> <z> [world]`: opens an office on the chest there with a server charter (a server-owned office), for testing from the console.
##### `clerk <here [office] | reset <office> | place <office> <x> <y> <z> [world] | list>`
Every local office has a clerk, "Postmaster of {office}", on duty while the mail runs: spawned with the dispatcher, put back at their post if anything moves or removes them, gone while it's stopped. They can't be hurt, pushed or leashed. Players nearby are greeted; right-clicking the clerk opens the office's **counter**:
- *Buy an address*: a form to name the house (a suggested name is filled in, *Suggest another* draws a new one) and pay the office's price; the deed goes into the player's inventory (permission `postal.deed.buy`, default true).
- *Office info*: the postmaster, how many addresses, what a new one costs.
- *Collect or replace a deed*: shown while the player has unused deeds of this office (an approved application's, or a lost one); older copies stop working.
- *Address price* (the owner and admins): set what a new address here costs, or go back to the server's price.
- *Applications* and *Approval* (the owner and admins): see below.

**Approval.** An office can require its owner's approval for new addresses (`Settings.Deeds.Require_approval`, default `'false'`, is the default for offices that haven't chosen). Buying then makes an **application**: paid for and its name reserved, but no deed yet. The owner hears of it (in chat, or when they next join; admins, for an office the server owns) and approves or declines it in the counter's *Applications* menu (left-click approves, right-click declines) or with `/postal office <office> applications | approve | deny`. An approved application becomes a deed, good for `Settings.Deeds.Expiry_days` from then, handed over at once if the player is online or collected at the counter; a declined one is refunded in full and its name is free again; one nobody answers lapses after `Expiry_days`, refunded in full. The applicant hears by letter from the office if they have an address, in chat if online, and otherwise when they next join.

The forms are dialogs; with `Settings.Clerks.Dialogs: 'false'` (or where a dialog can't be shown) they're asked in chat. `Settings.Clerks.Local: 'false'` leaves offices unstaffed, and right-clicking the office sign opens the counter instead. `Settings.Clerks.Name` and `Settings.Clerks.Greeting` set the clerk's name and greeting (`{office}`, `{player}`).
**Central** has a clerk too, the Postmaster General (`Settings.Clerks.Central`, `Central_name`, `Central_greeting`), on duty whenever the mail runs, holding a book and quill. His counter shows the postage rates, the network at a glance and the player's own offices, and sells **charters**: *Apply for a charter* (or *Buy a charter* with autoapprove on) opens a form for the office's name at the post office price (permission `postal.charter.buy`, default true); while an application waits the card says so; a granted (or lost) charter is collected there. Office sales are settled there too: *Your post offices* sells one back to Central (confirmed by typing its name), and *Offers to you* lists offices offered to the player (left-click accepts and pays, right-click declines). Clerks greet players who come up to them and say goodbye when they walk off (`Settings.Clerks.Farewell`). Postal protects **Central's chest and sign** itself: only admins can break them, explosions leave them standing, pistons can't move them, hoppers can't take from or put into the chest, fire can't burn the sign; players' attempts are logged (`Settings.Central.Report_grief`).
- `here [office]`: the owner (or an admin) stations their office's clerk where they stand, within `Settings.Clerks.Reach` (default 24) blocks of the office. `reset <office>` puts them back beside the office chest, where they stand by default. `central` names the Postmaster General (admins only).
- `place <office> <x> <y> <z> [world]` (admin, console): stations a clerk there.
- `list` (admin, console): every office's clerk, where they stand, whether they're on duty, and the office's address price.
##### `bank [newday | report [days] | policy [<setting> <value>]]`
Central's balance, what it owes and its target, and every office's owner, balance, reserve,
withdrawable amount and arrears. `newday` runs a Postal economy day now; `report` shows the daily flow
log; `policy` shows or changes the economy settings at runtime. See `docs/economy.md`.
<br>
#### `/postal office [PostOffice] [balance | deposit <amount> | withdraw <amount|all> | price [amount|default] | discount [percent|none] | approval [on|off|default] | applications | approve <id|name|all> | deny <id|name> [reason] | sell [<player> <price> | cancel | central]]`
Permissions: owner of the post office (admins may view any)
An office owner's account: see the balance, deposit, or withdraw down to the reserve. `price` shows what a new address at the office costs; with an amount the owner (or an admin) sets the office's own price, and `default` goes back to the server's `Address.Purchase_price`. `discount` shows or sets the office's discount on the postage of mail it sends, up to the server's `Economy.Rates.Max_discount` (default 25%); it comes out of the office's own share (`docs/economy.md`). `sell` sells the office, only ever through Central (`docs/design/central-office.md` §3): `sell <player> <price>` offers it to another player, who accepts (or declines) at Central's counter, paying the price to the owner and `Settings.Charters.Transfer_rate` (default 5%) of it to Central; the office keeps its account and residents. An offer lapses after `Settings.Charters.Offer_days` (default 3); `sell cancel` withdraws it; `sell` shows it. `sell central` sells the office back to Central for its balance less the address escrow plus Central's share of the purchase price, and it carries on as a server office (also at Central's counter, under *Your post offices*). Admins and the console may offer or withdraw on the owner's behalf; `/setowner` stays as the admin override. `approval` shows or sets whether new addresses need the owner's approval (`default`: the server's `Settings.Deeds.Require_approval`); `applications` lists the ones waiting, and `approve` / `deny` decide them (by the id shown, or the house name; a reason given with `deny` goes to the applicant).
<br>
#### `/go [PostOffice] [Address]` _`(No address defaults to central)`_
Permissions: `postal.gotocentral, postal.gotolocal, postal.gotoaddr`
Subcommands:
```
/gotocentral
/gotolocal <PostOffice>
/gotoaddr <PostOffice> <Address>
```
Teleports the player to the given address, postoffice, or central office.
<br>
#### `/addr <PostOffice> <address> [player]`
Permissions: `postal.addr`
Address a signed book in your hand _(to `player`)_ at `PostOffice:Address`.

On a network of servers sharing one mail store, `PostOffice` can be another server's office: its name, or `server:office` when several servers have an office by that name (your own server's offices always win a plain name). Only the letter's text travels; the postage is the network rate (`Economy.Postage.Letter.Network`).

`player` can be any player the network knows, online or not, on any server: the letter still goes to the address's mailbox, marked for their attention.
<br>
#### `/att [player]`
Permissions: `postal.att`
Re-Addresses the unprocessed book in your hand to `player` (any player the network knows, as for `/addr`)
No argument defaults the addressed to "`[resident]`"
<br>
#### `/package <PostOffice> <address> [player]`
Permissions: `postal.package`
Alias: `/pk`
Package a chest full of goods, the player must stand near the chest, with it having no signs near or on.
Otherwise, this command is used in the same was as `/addr`, except that parcels can't go to another server: only letters cross servers, so no items ever leave the server they were packed on.
The goods go into the post office's records straight away (with all their enchantments, names and data); the empty chest stays, locked by its `[Postal_Ship]` sign, until a courier collects it when the label is first picked up.

`/package cancel`, holding the label before it's posted, unpacks the goods back into that chest and refunds the postage.
<br>
#### `/cod <price>`
Permissions: `postal.cod`
Needs: `Economy`
COD = Cash On Delivery
When holding a shippable item, issuing this command will charge a configured amount from the player's account to stamp the item with a price for the receiver to pay when `/accept`-ing the package.
Read more on the [Economy page on bukkit](https://dev.bukkit.org/projects/postal-forwarded/pages/economy#title-6)
<br>
#### `/accept & /refuse`
Permissions: `postal.accept, postal.refuse`
`/accept` will accept the package and the attached COD payments, if the player can't pay these, the command is denied.
It will place the package (chest) in front of the player.
`/refuse` will refuse the package for the player, and place it back at it's origin, it will display an error when it cannot place it back.
A parcel is accepted or refused only once: its items come from the post office's record, not the label, so a copy of a label can't get them a second time.
<br>
#### `/dist <"all"/"owners"> [PostOffice] [Expiration Days]`
Permissions: `postal.distr`
Distribute mail book to given Postal addresses [at `PostOffice`], defaults to sending it to *all* owners.
<br>
#### `/tlist`
Permissions: `postal.tlist`
Alias: `/tl`
Short for town-list, will present the player with a formatted, alphabetical list of towns when entered without parameters. The closest 3 towns, in order of distance, are also shown. If entered with enough characters to identify a particular town, the addresses of that town are listed.
<br>
#### `/alist <PostOffice>`
Permissions: `postal.alist`
Alias: `/al`
Short for address-list, will list the addresses of the closest town when entered without parameters. Like /tlist, it will list the addresses of a particular town if entered with enough characters to identify it. The two commands complement each other including details that the other doesn’t.
<br>
#### `/plist [Substring match]`
Permissions: `postal.plist`
Alias: `postal.plist`
Short for player-list, lists the closes 8 players, in order of distance when entered without parameters. Along with the listed player is the Postal address he/she is closest to with the compass heading required to get there. If entered with enough characters to complete a player name, Postal will list any Postal addresses or post offices owned by the player.
<br>
#### `/gps <PostOffice> [Address]`
Permissions: `postal.gps`
Alias: `/gpsp`
Locates your compass to `PostOffice/(Address @ PostOffice)`.
<br>
#### `/expedite <PostOffice> <address>`
Permissions: `postal.expedite`
Alias: `/ex`
Pushes the route queue forward so that the local postman at `PostOffice` will visit `address` next.
<br>
#### `/setcentral`
Permissions: `postal.setcentral`
Sets the central postoffice at the current location.
<br>
#### `/setlocal <new PostOffice>`
Permissions: `postal.setlocal`
Sets a new local post office at the current location.
<br>
#### `/setaddr [PostOffice] <address>`
Permissions: `postal.setaddr`
Sets a new address `address` at the current location linked to `PostOffice` _(is filled in automatically when it's within 500 blocks and no `PostOffice` argument was given)_
<br>
#### `/setroute [[PostOffice] <address>]`
Permissions: `postal.setroute`
Opens the route editor for `address` or the nearest within 15 blocks if not given.
<br>
#### `/setowner [<PostOffice> (or/and) [address]] <player>`
Permissions: `postal.owneraddr, postal.ownerlocal`
Subcommands:
```
/setaddr <PostOffice> <address> <player>
/setlocal <PostOffice> <player>
```
`/setowner` resolves in `/setaddr` or `/setlocal` first, depending on the arguments.
Both give the ownership of the postal object in question (`address` or `PostOffice`) to `player`
With the economy on, a player without these permissions can still buy an **unowned** post office or address,
but only for themselves: the player named pays the purchase price, so only they can agree to it.
<br>
#### `/showroute [[PostOffice] <address>]`
Permissions: `postal.showroute`
Alias: `/sr`
Shows the route of `address` or the nearest one in 15 blocks (if no arguments given) to the player for a short amount of time.
<br>
#### `/openX` and `/closeX`
Permissions:
```
postal.openlocal,
postal.closelocal,
postal.openaddr,
postal.closeaddr
```
Commands:
```
/openlocal <PostOffice>
/closelocal <PostOffice>
/openaddr <PostOffice> <address>
/closeaddr <PostOffice> <address>
```
Opens or Closes the postal object, a closed address means no post can be delivered from or to that address, a closed PostOffice means no post can be delivered to *any* of the addresses attached to it, a closed address/postoffice can be identified by it's red-titled sign.
<br>
#### `/deletelocal <PostOffice>` and `/deleteaddr <PostOffice> <address>`
Permissions: `postal.deletelocal, postal.deleteaddr`
Deletes the mentioned address from the database, but the chests and signs will stay, and be protected till a restart of the postal service.