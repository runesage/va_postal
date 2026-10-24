package com.vodhanel.minecraft.va_postal.boxes;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.mail.Book;
import com.vodhanel.minecraft.va_postal.mail.Letters;
import com.vodhanel.minecraft.va_postal.mail.MailIds;
import com.vodhanel.minecraft.va_postal.mail.Returns;
import com.vodhanel.minecraft.va_postal.store.Actor;
import com.vodhanel.minecraft.va_postal.store.ConflictException;
import com.vodhanel.minecraft.va_postal.store.Custody;
import com.vodhanel.minecraft.va_postal.store.MailRecord;
import com.vodhanel.minecraft.va_postal.store.MailState;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.PoBox;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P.O. boxes, phase 2 (docs/design/po-boxes.md): delivery. Each office has one box cabinet, a barrel with a
 * {@code [Postal_Box]} sign. A letter addressed to {@code box<n>} at the office waits in its chest like any letter;
 * when the office's postman comes back from his round he files it into its box: the record becomes DELIVERED with box
 * custody, and nothing of it stays in the world. Opening the cabinet shows each renter their own box, take-only.
 */
public final class BoxCabinet implements Listener {
    /** Every box holds this much, the same for everyone. */
    public static final int SIZE = 27;
    private static final Pattern BOX = Pattern.compile("box\\s*(\\d{1,4})");

    private static MailStore store() {
        return MailStores.active();
    }

    // ---- Addresses --------------------------------------------------------------------------

    /** The box number in an address like "box12" or "Box 12", or -1. */
    public static int number(String address) {
        if (address == null) {
            return -1;
        }
        Matcher m = BOX.matcher(address.toLowerCase(Locale.ROOT).trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * The address to write for box mail to {@code typed} at {@code office} ("box12"), or null with the reason in
     * {@code why}: the box must be rented, and an attention must name its renter.
     */
    public static String address(String office, String typed, UUID attention, StringBuilder why) {
        int n = number(typed);
        MailStore store = store();
        if (n < 0 || store == null) {
            return null;
        }
        PoBox box;
        try {
            box = store.box(office, n).orElse(null);
        } catch (StoreException e) {
            why.append("the mail store can't be reached now");
            return "";
        }
        if (box == null) {
            why.append("Box ").append(n).append(" at ").append(DeedDocument.display(office)).append(" isn't rented");
            return "";
        }
        if (attention != null && !attention.equals(box.renter())) {
            why.append("Box ").append(n).append(" at ").append(DeedDocument.display(office)).append(" is ")
                    .append(box.renter_name()).append("'s: the attention must name them");
            return "";
        }
        return "box" + n;
    }

    // ---- The cabinet ------------------------------------------------------------------------

    private static String path(String office) {
        return GetConfig.path_format("postoffice.local." + office + ".box_cabinet");
    }

    static String key(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /** The office whose cabinet {@code b} is, or null. */
    static String office_of(Block b) {
        if (b == null || b.getType() != Material.BARREL) {
            return null;
        }
        String k = key(b);
        org.bukkit.configuration.ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
        if (cs == null) {
            return null;
        }
        for (String office : cs.getKeys(false)) {
            if (k.equals(VA_postal.plugin.getConfig().getString(path(office)))) {
                return office.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** {@code /pobox cabinet <office> [x y z]}: the barrel looked at (or at the coordinates) becomes the office's cabinet. */
    static void set_cabinet(CommandSender sender, String office, String[] args) {
        boolean admin = !(sender instanceof Player) || sender.hasPermission("postal.admin");
        if (!admin && !(sender instanceof Player p && p.getUniqueId().equals(C_Owner.get_owner_local_po_id(office)))) {
            send(sender, "&7Only the owner of " + DeedDocument.display(office) + " can set up its box cabinet.");
            return;
        }
        Block b = null;
        if (args.length >= 5) {
            try {
                org.bukkit.World w = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                b = w.getBlockAt(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            } catch (NumberFormatException e) {
                b = null;
            }
        } else if (sender instanceof Player p) {
            b = p.getTargetBlockExact(6);
        }
        if (b == null || b.getType() != Material.BARREL) {
            send(sender, "&7Look at a barrel (or give its coordinates): that's the box cabinet.");
            return;
        }
        String plot = com.vodhanel.minecraft.va_postal.towny.TownyPostOffices.cabinet_problem(office, b);
        if (plot != null) {
            if (!admin) {
                send(sender, "&7" + plot);
                return;
            }
            send(sender, "&7" + plot + " &6Admin override, you may continue.");
        }
        VA_postal.plugin.getConfig().set(path(office), key(b));
        VA_postal.plugin.saveConfig();
        sign(b, office, sender instanceof Player p ? p.getFacing().getOppositeFace() : BlockFace.SOUTH);
        send(sender, "&6The barrel at " + b.getX() + " " + b.getY() + " " + b.getZ() + " is the " + DeedDocument.display(office)
                + " Post Office's box cabinet.");
    }

    /** A {@code [Postal_Box]} sign on the cabinet's side facing {@code toward}, if there's room. */
    private static void sign(Block barrel, String office, BlockFace toward) {
        Block at = barrel.getRelative(toward);
        if (!at.getType().isAir() || toward == BlockFace.UP || toward == BlockFace.DOWN) {
            return;
        }
        at.setType(Material.OAK_WALL_SIGN, false);
        if (at.getBlockData() instanceof WallSign data) {
            data.setFacing(toward);
            at.setBlockData(data, false);
        }
        if (at.getState() instanceof Sign s) {
            Text.line(s, 0, "[Postal_Box]");
            Text.line(s, 1, DeedDocument.display(office));
            s.setWaxed(true);
            s.update(true, false);
        }
    }

    // ---- Filing -----------------------------------------------------------------------------

    /**
     * The office's postman is back at office {@code id} ({@code office}): box letters waiting in its chest go into their
     * boxes. Untracked books, boxes that aren't rented and full boxes leave the letter where it is.
     */
    public static void file(int id, String office) {
        MailStore store = store();
        if (store == null || office == null || VA_postal.wtr_inventory_postoffice == null || id < 0
                || id >= VA_postal.wtr_inventory_postoffice.length || VA_postal.wtr_inventory_postoffice[id] == null) {
            return;
        }
        String town = office.toLowerCase(Locale.ROOT).trim();
        Inventory chest = VA_postal.wtr_inventory_postoffice[id];
        ListIterator<ItemStack> it = chest.iterator();
        List<String> full_told = new ArrayList<>();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (item == null || item.getType() != Material.WRITTEN_BOOK) {
                continue;
            }
            Book book = new Book(item);
            if (!book.is_valid() || !town.equals(book.getTitle().toLowerCase(Locale.ROOT).trim())) {
                continue;
            }
            int n = number(book.getAuthor());
            UUID mail = MailIds.read(item);
            if (n < 0 || mail == null) {
                continue;
            }
            try {
                PoBox box = store.box(town, n).orElse(null);
                MailRecord r = store.get(mail).orElse(null);
                if (r == null || r.moving() || r.state.terminal()) {
                    continue;
                }
                if (box == null) {
                    // For a box nobody rents (it closed while this was on its way): back to its sender.
                    if (Returns.from_box(store, r, "Box " + n + " at " + DeedDocument.display(town) + " isn't rented")) {
                        it.set(null);
                    }
                    continue;
                }
                if (store.in_box(town, n).size() >= SIZE) {
                    if (!full_told.contains(box.label())) {
                        full_told.add(box.label());
                        tell(box.renter(), "&6Your " + box.label() + " at " + DeedDocument.display(town)
                                + " is full: mail for it waits at the office until you take some out.");
                    }
                    continue;
                }
                // The record first: if the book survived a crash it's a stale copy, and the next pass removes it.
                store.transition(r, MailState.DELIVERED, Custody.box(town, n), Actor.postman(town), "filed in " + box.label());
                it.set(null);
                com.vodhanel.minecraft.va_postal.economy.EconomyState.add_delivery(town);
                P_Economy.settle_postage(item, town);
                Util.dinform("[Postal] Filed letter " + mail + " in " + box.label() + " at " + town);
                tell(box.renter(), "&6There's mail in your " + box.label() + " at the " + DeedDocument.display(town) + " Post Office.");
            } catch (ConflictException e) {
                // changed meanwhile: the next visit files it
            } catch (StoreException e) {
                Util.cinform("[Postal] Couldn't file box mail at " + town + " now: " + e.getMessage());
                return;
            }
        }
    }

    // ---- Closed boxes -----------------------------------------------------------------------

    /**
     * On the main thread: letters left in boxes nobody rents any more (a lease lapsed or was ended) go back to their
     * senders ({@link Returns#from_box}). Returns how many went; any that can't go now wait for the next sweep.
     */
    public static int return_closed() {
        MailStore store = store();
        if (store == null) {
            return 0;
        }
        int sent = 0;
        try {
            for (MailRecord r : store.in_boxes()) {
                String ref = r.custody.ref == null ? "" : r.custody.ref;
                int hash = ref.lastIndexOf('#');
                if (hash <= 0) {
                    continue;
                }
                String office = ref.substring(0, hash);
                int n;
                try {
                    n = Integer.parseInt(ref.substring(hash + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (store.box(office, n).isPresent()) {
                    continue; // still rented: the renter's to take
                }
                if (Returns.from_box(store, r, "Box " + n + " at " + DeedDocument.display(office) + " was closed")) {
                    sent++;
                }
            }
        } catch (StoreException e) {
            Util.cinform("[Postal] Couldn't return mail from closed boxes now: " + e.getMessage());
        }
        return sent;
    }

    // ---- Opening it -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void on_open(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String office = office_of(event.getClickedBlock());
        if (office == null) {
            return;
        }
        event.setCancelled(true); // the barrel's own inventory is never used
        Player player = event.getPlayer();
        MailStore store = store();
        if (store == null) {
            Util.pinform(player, "&7The boxes can't be opened now.");
            return;
        }
        try {
            PoBox box = store.boxes_of(player.getUniqueId()).stream().filter(b -> b.office().equals(office)).findFirst().orElse(null);
            if (box == null) {
                Util.pinform(player, "&7You don't rent a box at the " + DeedDocument.display(office) + " Post Office. &f/pobox rent "
                        + office + "&7 (" + PoBoxes.money(PoBoxes.rent(office)) + " a day).");
                return;
            }
            Bukkit.getScheduler().runTask(VA_postal.plugin, () -> player.openInventory(new BoxView(box).getInventory()));
        } catch (StoreException e) {
            Util.pinform(player, "&7The boxes can't be opened now.");
        }
    }

    /** One renter's box: what's in it, take-only (a click takes a letter; nothing goes in). */
    static final class BoxView implements com.vodhanel.minecraft.va_postal.clerk.Menu {
        private final PoBox box;
        private final Inventory inventory;
        private final List<MailRecord> held = new ArrayList<>();

        BoxView(PoBox box) {
            this.box = box;
            this.inventory = Bukkit.createInventory(this, SIZE, Text.component(Text.color("&8" + box.label() + ", "
                    + DeedDocument.display(box.office()))));
            fill();
        }

        private void fill() {
            inventory.clear();
            held.clear();
            MailStore store = store();
            if (store == null) {
                return;
            }
            for (MailRecord r : store.in_box(box.office(), box.number())) {
                if (held.size() >= SIZE) {
                    break;
                }
                held.add(r);
                inventory.setItem(held.size() - 1, Letters.materialise(r));
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        @Override
        public void click(Player player, int slot, boolean right) {
            if (slot < 0 || slot >= held.size()) {
                return;
            }
            MailStore store = store();
            if (store == null) {
                return;
            }
            MailRecord r = store.get(held.get(slot).id).orElse(null);
            if (r == null || !Custody.box(box.office(), box.number()).equals(r.custody)) {
                fill(); // gone meanwhile
                return;
            }
            if (player.getInventory().firstEmpty() < 0) {
                Util.pinform(player, "&7Make room in your inventory first.");
                return;
            }
            try {
                MailRecord taken = store.transition(r, MailState.DELIVERED, Custody.NONE, Actor.player(player.getUniqueId()),
                        "taken from " + box.label());
                player.getInventory().addItem(Letters.materialise(taken));
            } catch (ConflictException | StoreException e) {
                Util.pinform(player, "&7That letter can't be taken now.");
            }
            fill();
        }
    }

    // ---- Protection -------------------------------------------------------------------------

    /** The office whose cabinet {@code b} is, or whose cabinet {@code b} is the sign on; or null. */
    private static String guarded(Block b) {
        String office = office_of(b);
        if (office == null && b != null && b.getBlockData() instanceof WallSign sign) {
            office = office_of(b.getRelative(sign.getFacing().getOppositeFace()));
        }
        return office;
    }

    /** Only the office's owner or an admin may break the cabinet or its sign; breaking the barrel removes the cabinet. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_break(org.bukkit.event.block.BlockBreakEvent event) {
        String office = guarded(event.getBlock());
        if (office == null) {
            return;
        }
        Player p = event.getPlayer();
        if (!p.hasPermission("postal.admin") && !p.getUniqueId().equals(C_Owner.get_owner_local_po_id(office))) {
            event.setCancelled(true);
            Util.pinform(p, "&7The box cabinet belongs to the " + DeedDocument.display(office) + " Post Office.");
            return;
        }
        if (event.getBlock().getType() == Material.BARREL) {
            VA_postal.plugin.getConfig().set(path(office), null);
            VA_postal.plugin.saveConfig();
            Util.pinform(p, "&6The " + DeedDocument.display(office) + " Post Office has no box cabinet now. Its mail stays in the boxes: "
                    + "&f/pobox cabinet " + office + "&6 on another barrel.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_entity_explode(org.bukkit.event.entity.EntityExplodeEvent event) {
        event.blockList().removeIf(b -> guarded(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_block_explode(org.bukkit.event.block.BlockExplodeEvent event) {
        event.blockList().removeIf(b -> guarded(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_burn(org.bukkit.event.block.BlockBurnEvent event) {
        if (guarded(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_piston_extend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> guarded(b) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void on_piston_retract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> guarded(b) != null)) {
            event.setCancelled(true);
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private static void tell(UUID player, String message) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            Util.pinform(p, message);
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }
}
