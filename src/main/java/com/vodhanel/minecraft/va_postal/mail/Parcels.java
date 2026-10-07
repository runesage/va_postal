package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.common.BlockFacing;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.store.Actor;
import com.vodhanel.minecraft.va_postal.store.ConflictException;
import com.vodhanel.minecraft.va_postal.store.Custody;
import com.vodhanel.minecraft.va_postal.store.MailKind;
import com.vodhanel.minecraft.va_postal.store.MailRecord;
import com.vodhanel.minecraft.va_postal.store.MailState;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Parcels (docs/design/persistent-state.md, phase P2). At {@code /package} the chest's items go into the
 * parcel's record (full {@code ItemStack}s), so nothing in the world can be duplicated or lost while the label
 * travels; the emptied chest stays where it was, locked by its {@code [Postal_Ship]} sign, until a courier
 * collects it at the first pickup. Accepting, refusing or cancelling a parcel is a single version-checked
 * change to its record, so its items are handed over exactly once, and a copied label (which has no mail id)
 * gets nothing.
 */
public final class Parcels {
    private Parcels() {
    }

    static MailStore store() {
        return MailStores.active();
    }

    // ---- Packaging -------------------------------------------------------------------------

    /**
     * Records a newly packaged parcel: takes the chest's items into the record and empties the chest. Returns
     * the label tagged with its mail id, or null (with a message to the player) if it couldn't be recorded, in
     * which case the chest keeps its items.
     */
    public static ItemStack packaged(ItemStack label, Player sender, Block chest, String origin_office, String dest_office,
                                     String dest_address, UUID attention) {
        if (store() == null) {
            Util.pinform(sender, "&cThe post office can't take parcels right now (its records are unavailable).");
            return null;
        }
        ItemStack recorded = record_parcel(label, sender.getUniqueId(), chest, origin_office, dest_office, dest_address,
                attention, Actor.player(sender.getUniqueId()));
        if (recorded == null) {
            Util.pinform(sender, "&cThe post office couldn't record this parcel; nothing was taken.");
        }
        return recorded;
    }

    /**
     * The core of {@link #packaged}: records the parcel with the chest's items and empties the chest. Returns the
     * label carrying its mail id, or null (the chest keeps its items).
     */
    static ItemStack record_parcel(ItemStack label, UUID sender, Block chest, String origin_office, String dest_office,
                                   String dest_address, UUID attention, Actor actor) {
        return record_parcel(label, sender, chest, origin_office, dest_office, dest_address, attention, actor, -1);
    }

    /** An item id no version of Minecraft has: {@code /postal testparcel ... retired} ships one. */
    static final String RETIRED_ID = "minecraft:postal_retired_item";

    /** As above; the item in {@code retire_slot} (testing; -1 for none) is recorded as {@link #RETIRED_ID}. */
    private static ItemStack record_parcel(ItemStack label, UUID sender, Block chest, String origin_office,
                                           String dest_office, String dest_address, UUID attention, Actor actor,
                                           int retire_slot) {
        MailStore store = store();
        if (store == null || !(chest.getState() instanceof Chest)) {
            return null;
        }
        Inventory inv = ((Chest) chest.getState()).getInventory();
        List<Integer> slots = new ArrayList<>();
        List<byte[]> items = new ArrayList<>();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                slots.add(slot);
                byte[] bytes = item.serializeAsBytes();
                items.add(slot == retire_slot ? ParcelPayload.with_item_id(bytes, RETIRED_ID) : bytes);
            }
        }
        Book book = new Book(label);
        ParcelPayload payload = new ParcelPayload(book.getTitle(), book.getAuthor(), pages(book), chest_key(chest),
                slots, items);
        UUID id = UUID.randomUUID();
        try {
            store.create(MailRecord.new_parcel(id, store.server_id(), origin_office, dest_office, dest_address,
                            sender, attention, payload.encode(), Letters.data_version(), System.currentTimeMillis(),
                            HoldTag.read(label)),
                    actor, "packaged at " + payload.chest + ": " + items.size() + " stack" + (items.size() == 1 ? "" : "s")
                            + " for " + dest_office + ", " + dest_address);
        } catch (StoreException | ConflictException e) {
            Util.cinform("[Postal] Could not record a parcel: " + e.getMessage());
            return null;
        }
        // Mark the chest as packed for this parcel, then empty it: the items are the record's now. If a crash
        // rolls the world back past this, the chest comes back full and unmarked; collect() notices.
        Chest state = (Chest) chest.getState();
        state.getPersistentDataContainer().set(packed_key(), org.bukkit.persistence.PersistentDataType.STRING, id.toString());
        state.update(true, false);
        ((Chest) chest.getState()).getInventory().clear();
        return MailIds.write(label, id);
    }

    private static org.bukkit.NamespacedKey packed_key;

    private static org.bukkit.NamespacedKey packed_key() {
        if (packed_key == null) {
            packed_key = new org.bukkit.NamespacedKey(com.vodhanel.minecraft.va_postal.VA_postal.plugin, "packed");
        }
        return packed_key;
    }

    /** Records a COD amount (and the hold now paying for it) on the parcel this label belongs to. */
    public static void set_cod(ItemStack label, double amount, Player by) {
        Optional<MailRecord> r = record(label);
        if (r.isEmpty()) {
            return;
        }
        try {
            store().set_terms(r.get(), amount, HoldTag.read(label) != null ? HoldTag.read(label) : r.get().hold_id,
                    Actor.player(by.getUniqueId()), "COD set to " + amount);
        } catch (StoreException | ConflictException e) {
            Util.cinform("[Postal] Could not record the COD on parcel " + r.get().id + ": " + e.getMessage());
        }
    }

    /** The COD amount recorded for this label's parcel (0 if none or unknown). */
    public static double cod_of(ItemStack label) {
        return record(label).map(r -> r.cod_amount).orElse(0.0D);
    }

    // ---- The recipient -----------------------------------------------------------------------

    /** {@code /accept}: the parcel's items, in a chest in front of the player, after paying any COD. */
    public static boolean accept(Player player, ItemStack label) {
        MailRecord r = delivered(player, label);
        if (r == null) {
            return false;
        }
        double cod = r.cod_amount;
        if (cod > 0.0D && !P_Economy.does_player_have_amount(player, cod)) {
            Util.pinform(player, "&7&oYou don't have enough money to pay for this COD.");
            return false;
        }
        Block block = ChestManip.parcel_place_chest_accept(player);
        if (block == null) {
            Util.pinform(player, "&7&oUnable to place parcel here.");
            return false;
        }
        if (cod > 0.0D && !P_Economy.charge_player(player, cod)) {
            Util.pinform(player, "&7&oUnable to collect COD payment.");
            block.setType(Material.AIR);
            return false;
        }
        MailRecord done;
        try {
            done = store().transition(r, MailState.ACCEPTED, Custody.NONE, Actor.player(player.getUniqueId()),
                    "accepted" + (cod > 0.0D ? ", COD " + cod + " paid" : ""));
        } catch (StoreException | ConflictException e) {
            // Someone else accepted or refused it first (or the store failed): hand nothing over.
            if (cod > 0.0D) {
                P_Economy.pay_player(player, cod);
            }
            block.setType(Material.AIR);
            Util.pinform(player, "&c&oThis order has already been filled.");
            return false;
        }
        fill(block, done, ParcelPayload.decode(r.payload), player);
        if (cod > 0.0D && r.sender != null) {
            P_Economy.pay_player(Bukkit.getOfflinePlayer(r.sender), cod);
        }
        player.getInventory().setItemInMainHand(BookManip.stamp_parcel_statement(player, label, true));
        return true;
    }

    /** {@code /refuse}: the parcel's items go back to where it was packed, with the statement. */
    public static boolean refuse(Player player, ItemStack label) {
        MailRecord r = delivered(player, label);
        if (r == null) {
            return false;
        }
        Block block = ChestManip.parcel_place_chest_refuse(label);
        if (block == null) {
            Util.pinform(player, "&7&oUnable to place parcel at origin.");
            return false;
        }
        MailRecord done;
        try {
            done = store().transition(r, MailState.REFUSED, Custody.chest(Letters.block_key(Util.location2str(block.getLocation()))),
                    Actor.player(player.getUniqueId()), "refused; returned to " + block.getX() + "," + block.getY() + "," + block.getZ());
        } catch (StoreException | ConflictException e) {
            block.setType(Material.AIR);
            Util.pinform(player, "&c&oThis order has already been filled.");
            return false;
        }
        Inventory inv = fill(block, done, ParcelPayload.decode(r.payload), player);
        ItemStack statement = BookManip.stamp_parcel_statement(player, label, false);
        player.getInventory().setItemInMainHand(null);
        BookManip.parcel_stmnt_to_chest(inv, statement, block, 4);
        Util.pinform(player, "&7&oShipment has been returned to sender.");
        return true;
    }

    /** The parcel this delivered label belongs to, ready to accept or refuse; null (with a message) if not. */
    private static MailRecord delivered(Player player, ItemStack label) {
        Optional<MailRecord> found = record(label);
        if (found.isEmpty() || found.get().kind != MailKind.PARCEL) {
            Util.pinform(player, "&c&oThe post office has no record of this shipping label.");
            return null;
        }
        MailRecord r = found.get();
        if (r.state == MailState.ACCEPTED || r.state == MailState.REFUSED || r.state == MailState.RECOVERED
                || r.state == MailState.RETURNED) {
            Util.pinform(player, "&c&oThis order has already been filled.");
            return null;
        }
        if (r.state != MailState.DELIVERED || r.moving()) {
            Util.pinform(player, "&c&oThis parcel hasn't been delivered yet.");
            return null;
        }
        return r;
    }

    // ---- The sender --------------------------------------------------------------------------

    /**
     * {@code /package cancel}: a parcel not yet posted goes back into the chest it was packed in (or a new chest
     * in front of the sender), its postage is refunded, and the label is taken back.
     */
    public static boolean cancel(Player player, ItemStack label) {
        Optional<MailRecord> found = record(label);
        if (found.isEmpty() || found.get().kind != MailKind.PARCEL) {
            Util.pinform(player, "&c&oThe post office has no record of this shipping label.");
            return false;
        }
        MailRecord r = found.get();
        if (r.state != MailState.POSTED || r.moving() || r.custody.kind != Custody.Kind.NONE) {
            Util.pinform(player, "&c&oThis parcel is already in the post; it can't be cancelled now.");
            return false;
        }
        if (r.sender != null && !r.sender.equals(player.getUniqueId()) && !player.hasPermission("postal.admin")) {
            Util.pinform(player, "&c&oOnly the sender can cancel this parcel.");
            return false;
        }
        ParcelPayload payload = ParcelPayload.decode(r.payload);
        Block block = origin_chest(payload);
        boolean reused = block != null;
        if (reused) {
            SignManip.remove_sign_id_chest(block);
        } else {
            block = ChestManip.parcel_place_chest_accept(player);
            if (block == null) {
                Util.pinform(player, "&7&oUnable to place the parcel's chest here.");
                return false;
            }
        }
        MailRecord done;
        try {
            done = store().transition(r, MailState.RETURNED, Custody.NONE, Actor.player(player.getUniqueId()), "cancelled by the sender");
        } catch (StoreException | ConflictException e) {
            if (!reused) {
                block.setType(Material.AIR);
            }
            Util.pinform(player, "&c&oThis parcel can't be cancelled now.");
            return false;
        }
        fill(block, done, payload, player);
        P_Economy.cancel_hold(r.hold_id != null ? r.hold_id : HoldTag.read(label));
        player.getInventory().setItemInMainHand(null);
        Util.pinform(player, "&7&oParcel cancelled: its items are back in the chest" + (reused ? " you packed it in." : " in front of you."));
        return true;
    }

    // ---- The post office ---------------------------------------------------------------------

    /**
     * The label's first pickup: a courier collects the emptied, locked chest it was packed in (or it's simply
     * removed if nobody is near enough to see).
     */
    public static void collect(ItemStack label) {
        Optional<MailRecord> r = record(label);
        if (r.isEmpty() || r.get().kind != MailKind.PARCEL) {
            return;
        }
        Block block = origin_chest(ParcelPayload.decode(r.get().payload));
        if (block != null) {
            Courier.collect(block);
        }
    }

    /**
     * Before a shipping label's first pickup: true if its parcel was packed in a world a crash has since rolled
     * back (see {@link #rolled_back(MailRecord, ParcelPayload)}), in which case it's cancelled and the label
     * must be left where it is.
     */
    public static boolean rolled_back(ItemStack label) {
        Optional<MailRecord> r = record(label);
        if (r.isEmpty() || r.get().kind != MailKind.PARCEL || r.get().moving() || r.get().state != MailState.POSTED) {
            return false;
        }
        return rolled_back(r.get(), ParcelPayload.decode(r.get().payload));
    }

    /**
     * True if a crash rolled the world back past the packing: the chest at the packing spot has items again and
     * no packed mark. The chest's items are then the real ones, so the parcel is cancelled (its label will
     * never be routed or accepted) and the chest unlocked: the items exist once.
     */
    static boolean rolled_back(MailRecord r, ParcelPayload payload) {
        Location at = location(payload.chest);
        if (at == null || !(at.getBlock().getState() instanceof Chest)) {
            return false;
        }
        Chest chest = (Chest) at.getBlock().getState();
        String mark = chest.getPersistentDataContainer().get(packed_key(), org.bukkit.persistence.PersistentDataType.STRING);
        boolean has_items = false;
        for (ItemStack item : chest.getInventory().getContents()) {
            has_items |= item != null && !item.getType().isAir();
        }
        if (mark != null || !has_items) {
            return false;
        }
        try {
            store().transition(r, MailState.RETURNED, r.custody, Actor.reconcile(),
                    "cancelled: the world was rolled back past its packing, so its items are still in the chest");
        } catch (StoreException | ConflictException e) {
            return false;
        }
        SignManip.remove_sign_id_chest(at.getBlock());
        P_Economy.cancel_hold(r.hold_id);
        Util.cinform("[Postal] Parcel " + r.id + " cancelled: the world was rolled back past its packing (its items are"
                + " still in the chest at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ() + ").");
        return true;
    }

    /** Admin recovery: rebuilds the parcel's items in a chest at {@code at} (and closes the record as RECOVERED). */
    public static boolean recover(MailRecord r, Location at, Actor actor, CommandSender who) {
        Block block = at.getBlock();
        if (!block.getType().isAir()) {
            return false;
        }
        block.setType(Material.CHEST);
        MailRecord done;
        try {
            done = store().transition(r, MailState.RECOVERED, Custody.chest(Letters.block_key(Util.location2str(block.getLocation()))),
                    actor, "recovered to " + block.getX() + "," + block.getY() + "," + block.getZ());
        } catch (StoreException | ConflictException e) {
            block.setType(Material.AIR);
            return false;
        }
        fill(block, done, ParcelPayload.decode(r.payload), who);
        return true;
    }

    /** Admin accept (testing): hands the items over in a chest at {@code at}, without COD. */
    public static boolean admin_accept(MailRecord r, Location at, Actor actor, CommandSender who) {
        Block block = at.getBlock();
        if (!block.getType().isAir() || r.state != MailState.DELIVERED || r.moving()) {
            return false;
        }
        block.setType(Material.CHEST);
        MailRecord done;
        try {
            done = store().transition(r, MailState.ACCEPTED, Custody.NONE, actor, "accepted by an admin (no COD collected)");
        } catch (StoreException | ConflictException e) {
            block.setType(Material.AIR);
            return false;
        }
        fill(block, done, ParcelPayload.decode(r.payload), who);
        return true;
    }

    /** Admin refuse (testing): the items go back to where the parcel was packed. */
    public static boolean admin_refuse(MailRecord r, Actor actor, CommandSender who) {
        if (r.state != MailState.DELIVERED || r.moving()) {
            return false;
        }
        ParcelPayload payload = ParcelPayload.decode(r.payload);
        Location at = location(payload.chest);
        if (at == null || !at.getBlock().getType().isAir()) {
            return false;
        }
        Block block = at.getBlock();
        block.setType(Material.CHEST);
        MailRecord done;
        try {
            done = store().transition(r, MailState.REFUSED, Custody.chest(Letters.block_key(Util.location2str(block.getLocation()))),
                    actor, "refused by an admin");
        } catch (StoreException | ConflictException e) {
            block.setType(Material.AIR);
            return false;
        }
        fill(block, done, payload, who);
        return true;
    }

    /**
     * {@code /postal testparcel}: packs a chest at {@code chest} with test items (an enchanted, renamed sword,
     * logs, golden apples), labels it to {@code to}/{@code address}, locks it with its [Postal_Ship] sign and
     * records the parcel. Returns the label carrying its mail id, or null.
     */
    public static ItemStack test_parcel(Block chest, String to, String address, double cod, boolean retired, Actor actor) {
        chest.setType(Material.CHEST);
        BlockFacing.set_facing(chest, BlockFace.SOUTH);
        Inventory inv = ((Chest) chest.getState()).getInventory();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        org.bukkit.inventory.meta.ItemMeta meta = sword.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Test Blade"));
        meta.lore(java.util.List.of(net.kyori.adventure.text.Component.text("Shipped by /postal testparcel")));
        sword.setItemMeta(meta);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 5);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 3);
        inv.setItem(0, sword);
        inv.setItem(4, new ItemStack(Material.OAK_LOG, 32));
        inv.setItem(13, new ItemStack(Material.GOLDEN_APPLE, 3));
        if (retired) {
            // Recorded under an id this Minecraft doesn't have, as if an upgrade had removed it.
            inv.setItem(22, new ItemStack(Material.PAPER));
        }

        String where = chest_key(chest);
        String[] parts = where.split(",");
        String date = new java.text.SimpleDateFormat("MM/dd/yy HH:mm").format(new java.util.Date());
        String[] pages = {
                Book.makeFirstMailPage(Util.df(to), Util.df(address), "[Resident]", null, null, "Server", "[Shipping Label]",
                        date, null, null),
                "§7" + parts[0] + "\n" + parts[1] + "," + parts[2] + "," + parts[3] + "," + parts[4] + "\n\n"
                        + "§201 §9diamond_sword\n§232 §9oak_log\n§203 §9golden_apple\n\n§2/accept\n§c/refuse\n"};
        ItemStack label = new Book(Util.df(to), Util.df(address), pages).generateItemStack();
        label = record_parcel(label, null, chest, null, to, address, null, actor, retired ? 22 : -1);
        if (label == null) {
            return null;
        }
        BookManip.standard_addr_sign(Util.location2str(chest.getLocation()), 2, to, address, "Server");
        if (cod > 0.0D) {
            record(label).ifPresent(r -> store().set_terms(r, cod, r.hold_id, actor, "COD set to " + cod));
        }
        return label;
    }

    /** The shipping label rebuilt from its record (a lost label). Mail id and hold are added by the caller. */
    static ItemStack label(MailRecord r) {
        ParcelPayload p = ParcelPayload.decode(r.payload);
        return new Book(p.title, p.author, p.pages.toArray(new String[0])).generateItemStack();
    }

    /** A one-line summary of the items, for /postal track. */
    public static String contents(MailRecord r) {
        try {
            List<String> names = new ArrayList<>();
            ParcelPayload p = ParcelPayload.decode(r.payload);
            for (byte[] bytes : p.items) {
                ItemStack item = restore(bytes);
                if (item == null) {
                    names.add(describe(bytes) + " (no longer in the game)");
                    continue;
                }
                String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                        ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(item.getItemMeta().displayName()) + " (" + item.getType().name().toLowerCase() + ")"
                        : item.getType().name().toLowerCase();
                names.add(item.getAmount() + "x " + name + (item.getEnchantments().isEmpty() ? "" : " " + item.getEnchantments().size() + " enchantment(s)"));
            }
            return names.isEmpty() ? "(empty)" : String.join(", ", names);
        } catch (RuntimeException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    // ---- Helpers -----------------------------------------------------------------------------

    static Optional<MailRecord> record(ItemStack label) {
        MailStore store = store();
        UUID id = MailIds.read(label);
        if (store == null || id == null) {
            return Optional.empty();
        }
        try {
            return store.get(id);
        } catch (StoreException e) {
            Util.cinform("[Postal] Could not read parcel " + id + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Puts the parcel's items back in their slots; anything that doesn't fit is dropped beside the chest. An item
     * this version of Minecraft can no longer make (removed in an upgrade, say) is left out and everything else
     * delivered: {@code who} is told what's missing, and the parcel's history and the log keep a note of it. The
     * record's payload is untouched, so the item's data isn't lost.
     */
    private static Inventory fill(Block block, MailRecord r, ParcelPayload p, CommandSender who) {
        Inventory inv = ((Chest) block.getState()).getInventory();
        Location drop = block.getLocation().add(0.5D, 1.0D, 0.5D);
        List<String> lost = new ArrayList<>();
        for (int i = 0; i < p.items.size(); i++) {
            ItemStack item = restore(p.items.get(i));
            if (item == null) {
                lost.add(describe(p.items.get(i)));
                continue;
            }
            int slot = p.slots.get(i);
            if (slot >= 0 && slot < inv.getSize() && inv.getItem(slot) == null) {
                inv.setItem(slot, item);
            } else {
                inv.addItem(item).values().forEach(left -> block.getWorld().dropItemNaturally(drop, left));
            }
        }
        if (!lost.isEmpty()) {
            report_lost(r, lost, who);
        }
        return inv;
    }

    /** The item these bytes hold, or null if this version of Minecraft can't make it. */
    private static ItemStack restore(byte[] bytes) {
        try {
            ItemStack item = ItemStack.deserializeBytes(bytes);
            return item == null || item.getType().isAir() ? null : item;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What an unrestorable item was, as far as its stored id says. */
    private static String describe(byte[] bytes) {
        String id = ParcelPayload.item_id(bytes);
        return id != null ? id : "an unreadable item";
    }

    private static void report_lost(MailRecord r, List<String> lost, CommandSender who) {
        String count = lost.size() == 1 ? "1 item" : lost.size() + " items";
        String list = String.join(", ", lost);
        if (who != null) {
            who.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&e&o" + count + " in this parcel ("
                    + list + ") no longer exist" + (lost.size() == 1 ? "s" : "") + " in this version of Minecraft, so "
                    + (lost.size() == 1 ? "it" : "they") + " couldn't be delivered. Everything else is here; the post"
                    + " office has kept a record of " + (lost.size() == 1 ? "it." : "them.")));
        }
        com.vodhanel.minecraft.va_postal.VA_postal.plugin.getLogger().warning("Parcel " + r.id + ": " + count
                + " could not be restored and " + (lost.size() == 1 ? "was" : "were") + " left out: " + list
                + " (the record keeps the item data).");
        try {
            store().set_terms(r, r.cod_amount, r.hold_id, Actor.system("restore"), count + " left out (no longer in the game): " + list);
        } catch (StoreException | ConflictException e) {
            Util.cinform("[Postal] Could not note the lost items on parcel " + r.id + ": " + e.getMessage());
        }
    }

    /** The chest the parcel was packed in, if it's still there and still locked by its [Postal_Ship] sign. */
    private static Block origin_chest(ParcelPayload p) {
        Location at = location(p.chest);
        if (at == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)
                && !at.getChunk().load()) {
            return null;
        }
        Block block = at.getBlock();
        if (!(block.getState() instanceof Chest)) {
            return null;
        }
        // Someone else's chest built on the same spot isn't the parcel's.
        Block front = BlockFacing.front(block);
        return front.getState() instanceof org.bukkit.block.Sign
                && ((org.bukkit.block.Sign) front.getState()).getLine(0).contains("[Postal_Ship]") ? block : null;
    }

    static Location location(String key) {
        if (key == null) {
            return null;
        }
        String[] p = key.split(",");
        if (p.length < 4) {
            return null;
        }
        World w = Bukkit.getWorld(p[0].trim());
        if (w == null) {
            return null;
        }
        try {
            return new Location(w, Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String chest_key(Block chest) {
        BlockFace facing = BlockFacing.facing(chest);
        return chest.getWorld().getName() + "," + chest.getX() + "," + chest.getY() + "," + chest.getZ() + ","
                + (facing == null ? "NORTH" : facing.name());
    }

    private static List<String> pages(Book book) {
        List<String> out = new ArrayList<>();
        String[] pages = book.getPages();
        if (pages != null) {
            for (String page : pages) {
                out.add(page == null ? "" : page);
            }
        }
        return out;
    }
}
