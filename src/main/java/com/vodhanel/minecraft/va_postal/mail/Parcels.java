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
                items.add(item.serializeAsBytes());
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
        inv.clear(); // the items are the record's now
        return MailIds.write(label, id);
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
        try {
            store().transition(r, MailState.ACCEPTED, Custody.NONE, Actor.player(player.getUniqueId()),
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
        fill(block, ParcelPayload.decode(r.payload));
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
        try {
            store().transition(r, MailState.REFUSED, Custody.chest(Letters.block_key(Util.location2str(block.getLocation()))),
                    Actor.player(player.getUniqueId()), "refused; returned to " + block.getX() + "," + block.getY() + "," + block.getZ());
        } catch (StoreException | ConflictException e) {
            block.setType(Material.AIR);
            Util.pinform(player, "&c&oThis order has already been filled.");
            return false;
        }
        Inventory inv = fill(block, ParcelPayload.decode(r.payload));
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
        try {
            store().transition(r, MailState.RETURNED, Custody.NONE, Actor.player(player.getUniqueId()), "cancelled by the sender");
        } catch (StoreException | ConflictException e) {
            if (!reused) {
                block.setType(Material.AIR);
            }
            Util.pinform(player, "&c&oThis parcel can't be cancelled now.");
            return false;
        }
        fill(block, payload);
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

    /** Admin recovery: rebuilds the parcel's items in a chest at {@code at} (and closes the record as RECOVERED). */
    public static boolean recover(MailRecord r, Location at, Actor actor) {
        Block block = at.getBlock();
        if (!block.getType().isAir()) {
            return false;
        }
        block.setType(Material.CHEST);
        try {
            store().transition(r, MailState.RECOVERED, Custody.chest(Letters.block_key(Util.location2str(block.getLocation()))),
                    actor, "recovered to " + block.getX() + "," + block.getY() + "," + block.getZ());
        } catch (StoreException | ConflictException e) {
            block.setType(Material.AIR);
            return false;
        }
        fill(block, ParcelPayload.decode(r.payload));
        return true;
    }

    /** Admin accept (testing): hands the items over in a chest at {@code at}, without COD. */
    public static boolean admin_accept(MailRecord r, Location at, Actor actor) {
        Block block = at.getBlock();
        if (!block.getType().isAir() || r.state != MailState.DELIVERED || r.moving()) {
            return false;
        }
        block.setType(Material.CHEST);
        try {
            store().transition(r, MailState.ACCEPTED, Custody.NONE, actor, "accepted by an admin (no COD collected)");
        } catch (StoreException | ConflictException e) {
            block.setType(Material.AIR);
            return false;
        }
        fill(block, ParcelPayload.decode(r.payload));
        return true;
    }

    /** Admin refuse (testing): the items go back to where the parcel was packed. */
    public static boolean admin_refuse(MailRecord r, Actor actor) {
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
        try {
            store().transition(r, MailState.REFUSED, Custody.chest(Letters.block_key(Util.location2str(block.getLocation()))),
                    actor, "refused by an admin");
        } catch (StoreException | ConflictException e) {
            block.setType(Material.AIR);
            return false;
        }
        fill(block, payload);
        return true;
    }

    /**
     * {@code /postal testparcel}: packs a chest at {@code chest} with test items (an enchanted, renamed sword,
     * logs, golden apples), labels it to {@code to}/{@code address}, locks it with its [Postal_Ship] sign and
     * records the parcel. Returns the label carrying its mail id, or null.
     */
    public static ItemStack test_parcel(Block chest, String to, String address, double cod, Actor actor) {
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

        String where = chest_key(chest);
        String[] parts = where.split(",");
        String date = new java.text.SimpleDateFormat("MM/dd/yy HH:mm").format(new java.util.Date());
        String[] pages = {
                Book.makeFirstMailPage(Util.df(to), Util.df(address), "[Resident]", null, null, "Server", "[Shipping Label]",
                        date, null, null),
                "§7" + parts[0] + "\n" + parts[1] + "," + parts[2] + "," + parts[3] + "," + parts[4] + "\n\n"
                        + "§201 §9diamond_sword\n§232 §9oak_log\n§203 §9golden_apple\n\n§2/accept\n§c/refuse\n"};
        ItemStack label = new Book(Util.df(to), Util.df(address), pages).generateItemStack();
        label = record_parcel(label, null, chest, null, to, address, null, actor);
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
            for (ItemStack item : items(ParcelPayload.decode(r.payload))) {
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

    static List<ItemStack> items(ParcelPayload p) {
        List<ItemStack> out = new ArrayList<>();
        for (byte[] bytes : p.items) {
            out.add(ItemStack.deserializeBytes(bytes));
        }
        return out;
    }

    /** Puts the parcel's items back in their slots; anything that doesn't fit is dropped beside the chest. */
    private static Inventory fill(Block block, ParcelPayload p) {
        Inventory inv = ((Chest) block.getState()).getInventory();
        Location drop = block.getLocation().add(0.5D, 1.0D, 0.5D);
        for (int i = 0; i < p.items.size(); i++) {
            ItemStack item;
            try {
                item = ItemStack.deserializeBytes(p.items.get(i));
            } catch (RuntimeException e) {
                Util.cinform("[Postal] Could not restore a parcel item: " + e.getMessage());
                continue;
            }
            int slot = p.slots.get(i);
            if (slot >= 0 && slot < inv.getSize() && inv.getItem(slot) == null) {
                inv.setItem(slot, item);
            } else {
                inv.addItem(item).values().forEach(left -> block.getWorld().dropItemNaturally(drop, left));
            }
        }
        return inv;
    }

    /** The chest the parcel was packed in, if it's still there. */
    private static Block origin_chest(ParcelPayload p) {
        Location at = location(p.chest);
        if (at == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)
                && !at.getChunk().load()) {
            return null;
        }
        Block block = at.getBlock();
        return block.getState() instanceof Chest ? block : null;
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
