#### `/postal`
Permissions: `none`
Displays a menu with commands available to the player.
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
##### `testparcel <from PostOffice> <to PostOffice> <address> [cod] [retired]`
Packs a test parcel (an enchanted, renamed diamond sword, oak logs, golden apples) in a chest beside the from-office, locks it and hands its label in at that office, as a player would after `/package`. Optional COD amount. With `retired`, the parcel also carries an item recorded under an id this Minecraft doesn't have, as if an upgrade had removed it: whoever opens the parcel gets everything else, plus a message naming what was left out.
##### `recover <mail id> [x y z]`
Rebuilds lost mail from its record: a parcel's items in a new chest at x y z (or two blocks in front of you), a letter into your inventory. The record closes as RECOVERED, so the original is never routed or accepted.
##### `accept <mail id> [x y z]` / `refuse <mail id>`
Accepts (items in a chest at x y z, no COD) or refuses (items back where it was packed) a delivered parcel for its recipient. For testing without a second player.
##### `setstate <mail id> <state>`
Forces a record into a state, keeping where it is. Testing only; recorded in its history as forced by an admin.

`last` can be used in place of a mail id for the newest mail.
##### `testletter <from PostOffice> <to PostOffice> <address>`
Writes a tracked test letter and hands it in at the from-office's chest, as a player would. For testing routes without a player.
##### `bank [newday | report [days] | policy [<setting> <value>]]`
Central's balance, what it owes and its target, and every office's owner, balance, reserve,
withdrawable amount and arrears. `newday` runs a Postal economy day now; `report` shows the daily flow
log; `policy` shows or changes the economy settings at runtime. See `docs/economy.md`.
<br>
#### `/postal office [PostOffice] [balance | deposit <amount> | withdraw <amount|all>]`
Permissions: owner of the post office (admins may view any)
An office owner's account: see the balance, deposit, or withdraw down to the reserve.
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
Address a signed book in your hand _(to `player`)_ at `PostOffice:Address`
<br>
#### `/att [player]`
Permissions: `postal.att`
Re-Addresses the unprocessed book in your hand to `player`
No argument defaults the addressed to "`[resident]`"
<br>
#### `/package <PostOffice> <address> [player]`
Permissions: `postal.package`
Alias: `/pk`
Package a chest full of goods, the player must stand near the chest, with it having no signs near or on.
Otherwise, this command is used in the same was as `/addr`.
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