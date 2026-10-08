package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.config.C_Address;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.mail.Book;
import com.vodhanel.minecraft.va_postal.mail.Letters;
import com.vodhanel.minecraft.va_postal.mail.SignManip;
import com.vodhanel.minecraft.va_postal.store.MailEvent;
import com.vodhanel.minecraft.va_postal.store.MailRecord;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin tools for tracked mail: track, testletter, testparcel, reconcile, recover, accept, refuse and setstate
 * (the last three are for testing).
 */
public final class P_MailAdmin {
    private P_MailAdmin() {
    }

    /** {@code /postal track <mail id>}: a letter's record and its full history. */
    public static void track(CommandSender sender, String[] args) {
        MailStore store = MailStores.active();
        if (store == null) {
            send(sender, "&7The mail store isn't open; mail isn't being tracked.");
            return;
        }
        if (args.length < 2) {
            send(sender, "&7Usage: /postal track <mail id | last | recent>");
            return;
        }
        if ("recent".equalsIgnoreCase(args[1])) {
            send(sender, "&6[Postal] Recent mail");
            for (MailRecord r : store.recent(10)) {
                send(sender, "&7" + r.id + " &f" + (r.kind == com.vodhanel.minecraft.va_postal.store.MailKind.PARCEL ? "[parcel] " : "")
                        + r.dest_office + ", " + r.dest_address + "&7: &e" + r.state
                        + "&7 at " + r.custody + (r.moving() ? " &c(moving)" : ""));
            }
            return;
        }
        MailRecord r = target(sender, args, "track <mail id | last | recent>");
        if (r == null) {
            return;
        }
        UUID id = r.id;
        send(sender, "&6[Postal] " + r.kind + " " + r.id);
        send(sender, "&7To &f" + r.dest_office + ", " + r.dest_address + "&7 from &f" + r.origin_office + "&7: &e"
                + r.state + "&7 at " + r.custody + (r.moving() ? " &c(moving to " + r.pending_state + " at " + r.pending_custody + ")" : ""));
        if (r.kind == com.vodhanel.minecraft.va_postal.store.MailKind.PARCEL) {
            send(sender, "&7Contents: &f" + com.vodhanel.minecraft.va_postal.mail.Parcels.contents(r)
                    + (r.cod_amount > 0.0D ? "&7  COD &f" + r.cod_amount : ""));
        }
        if (r.hold_id != null) {
            send(sender, "&7Postage hold: &f" + r.hold_id);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss");
        List<MailEvent> history = store.history(id);
        for (MailEvent e : history) {
            send(sender, "&7  v" + e.version + " " + fmt.format(new Date(e.at)) + " " + (e.from_state == null ? "" : e.from_state + " -> ")
                    + e.to_state + " by " + e.actor_kind + (e.actor_ref == null ? "" : " " + e.actor_ref)
                    + (e.detail == null ? "" : " (" + e.detail + ")"));
        }
    }

    /** {@code /postal reconcile}: a reconciliation pass now, over loaded chunks, as the periodic one does. */
    public static void reconcile(CommandSender sender) {
        if (MailStores.active() == null) {
            send(sender, "&7The mail store isn't open; mail isn't being tracked.");
            return;
        }
        com.vodhanel.minecraft.va_postal.mail.Reconciler.Report report = com.vodhanel.minecraft.va_postal.mail.Reconciler.run(false);
        send(sender, "&6[Postal] Reconciliation: &7" + report);
    }

    /**
     * {@code /postal testletter <from office> <to office> <to address>}: writes a tracked letter and hands it in
     * at the from-office's chest, as a player would, so letter flows can be tested without one.
     */
    public static void testletter(CommandSender sender, String[] args) {
        if (args.length < 4) {
            send(sender, "&7Usage: /postal testletter <from office> <to office> <to address>");
            return;
        }
        String from = C_Postoffice.town_complete(args[1]);
        String to = C_Postoffice.town_complete(args[2]);
        if ("null".equals(from) || "null".equals(to)) {
            send(sender, "&7Unknown post office. See /tlist.");
            return;
        }
        String address = C_Address.addresses_complete(to, args[3]);
        if ("null".equals(address)) {
            send(sender, "&7Unknown address in " + to + ". See /alist " + to + ".");
            return;
        }
        Block chest = office_chest(from);
        if (chest == null) {
            send(sender, "&7Couldn't find " + from + "'s post office chest.");
            return;
        }
        String date = new SimpleDateFormat("MM/dd/yy HH:mm").format(new Date());
        String[] pages = {
                Book.makeFirstMailPage(Util.df(to), Util.df(address), "[Resident]", null, null, "Server", "Test letter", date, null, null),
                "A test letter from " + Util.df(from) + ", posted " + date + "."};
        ItemStack letter = new Book(Util.df(to), Util.df(address), pages).generateItemStack();
        letter = Letters.posted(letter, null, from, to, address, null);
        if (!((Chest) chest.getState()).getInventory().addItem(letter).isEmpty()) {
            send(sender, "&7" + from + "'s post office chest is full.");
            return;
        }
        // Handed in over the counter: the office has it from now on (so reconciliation watches that chest).
        Letters.arrived(letter, com.vodhanel.minecraft.va_postal.common.Util.location2str(chest.getLocation()),
                com.vodhanel.minecraft.va_postal.store.MailState.AT_ORIGIN_BRANCH, com.vodhanel.minecraft.va_postal.store.Actor.admin(sender.getName()));
        UUID id = com.vodhanel.minecraft.va_postal.mail.MailIds.read(letter);
        send(sender, "&6Test letter " + (id == null ? "(untracked)" : id.toString()) + " handed in at " + Util.df(from)
                + " for " + Util.df(to) + ", " + Util.df(address) + ".");
    }

    /**
     * {@code /postal testparcel <from office> <to office> <to address> [cod] [retired]}: packs a test parcel (an
     * enchanted, renamed sword, logs, golden apples) in a chest beside the from-office, locks it, and hands its
     * label in at that office, as a player would after /package. With {@code retired}, it also carries an item
     * recorded under an id this Minecraft doesn't have, as if an upgrade had removed it.
     */
    public static void testparcel(CommandSender sender, String[] args) {
        if (args.length < 4) {
            send(sender, "&7Usage: /postal testparcel <from office> <to office> <to address> [cod] [retired]");
            return;
        }
        String from = C_Postoffice.town_complete(args[1]);
        String to = C_Postoffice.town_complete(args[2]);
        if ("null".equals(from) || "null".equals(to)) {
            send(sender, "&7Unknown post office. See /tlist.");
            return;
        }
        String address = C_Address.addresses_complete(to, args[3]);
        if ("null".equals(address)) {
            send(sender, "&7Unknown address in " + to + ". See /alist " + to + ".");
            return;
        }
        boolean retired = args.length > 4 && "retired".equalsIgnoreCase(args[args.length - 1]);
        double cod = args.length > 4 && !"retired".equalsIgnoreCase(args[4]) ? Util.str2double(args[4]) : 0.0D;
        Block office = office_chest(from);
        if (office == null) {
            send(sender, "&7Couldn't find " + from + "'s post office chest.");
            return;
        }
        Location spot = com.vodhanel.minecraft.va_postal.mail.Courier.stand_near(office.getLocation(), 4);
        if (spot == null) {
            send(sender, "&7No room for a parcel chest near " + from + "'s post office.");
            return;
        }
        ItemStack label = com.vodhanel.minecraft.va_postal.mail.Parcels.test_parcel(spot.getBlock(), to, address, cod, retired,
                com.vodhanel.minecraft.va_postal.store.Actor.admin(sender.getName()));
        if (label == null) {
            send(sender, "&7Couldn't record the test parcel (is the mail store open?).");
            return;
        }
        if (!((Chest) office.getState()).getInventory().addItem(label).isEmpty()) {
            send(sender, "&7" + from + "'s post office chest is full.");
            return;
        }
        Letters.arrived(label, Util.location2str(office.getLocation()),
                com.vodhanel.minecraft.va_postal.store.MailState.AT_ORIGIN_BRANCH,
                com.vodhanel.minecraft.va_postal.store.Actor.admin(sender.getName()));
        UUID id = com.vodhanel.minecraft.va_postal.mail.MailIds.read(label);
        Block b = spot.getBlock();
        send(sender, "&6Test parcel " + id + " packed at " + b.getX() + "," + b.getY() + "," + b.getZ()
                + " and handed in at " + Util.df(from) + " for " + Util.df(to) + ", " + Util.df(address)
                + (cod > 0.0D ? " (COD " + cod + ")" : "") + (retired ? ", with a retired item" : "") + ".");
    }

    /**
     * {@code /postal recover <id> [x y z]}: rebuilds lost mail from its record. A parcel's items go in a new chest
     * at x y z (or in front of the player); a letter goes to the player. The record closes as RECOVERED, so the
     * original (if it ever turns up) is never routed or accepted.
     */
    public static void recover(CommandSender sender, String[] args) {
        MailRecord r = target(sender, args, "recover <mail id> [x y z]");
        if (r == null) {
            return;
        }
        if (r.state.terminal() || r.moving()) {
            send(sender, "&7It's " + r.state + (r.moving() ? " (moving)" : "") + "; only mail still in the post (or MISSING) can be recovered.");
            return;
        }
        com.vodhanel.minecraft.va_postal.store.Actor actor = com.vodhanel.minecraft.va_postal.store.Actor.admin(sender.getName());
        if (r.kind == com.vodhanel.minecraft.va_postal.store.MailKind.PARCEL) {
            Location at = where(sender, args, 2);
            if (at == null) {
                send(sender, "&7Give x y z for the chest (from the console).");
                return;
            }
            if (com.vodhanel.minecraft.va_postal.mail.Parcels.recover(r, at, actor, sender)) {
                send(sender, "&6Parcel " + r.id + " recovered into a chest at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ() + ".");
            } else {
                send(sender, "&7Couldn't recover it there (the spot must be empty air).");
            }
            return;
        }
        if (!(sender instanceof org.bukkit.entity.Player)) {
            send(sender, "&7Recover letters in game: the book is given to you.");
            return;
        }
        try {
            MailStores.active().transition(r, com.vodhanel.minecraft.va_postal.store.MailState.RECOVERED,
                    com.vodhanel.minecraft.va_postal.store.Custody.NONE, actor, "recovered by an admin");
        } catch (RuntimeException e) {
            send(sender, "&7Couldn't recover it: " + e.getMessage());
            return;
        }
        ((org.bukkit.entity.Player) sender).getInventory().addItem(Letters.materialise(r));
        send(sender, "&6Letter " + r.id + " recovered into your inventory.");
    }

    /** {@code /postal accept <id> [x y z]}: accepts a delivered parcel for its recipient (testing; no COD). */
    public static void accept(CommandSender sender, String[] args) {
        MailRecord r = target(sender, args, "accept <mail id> [x y z]");
        if (r == null) {
            return;
        }
        Location at = where(sender, args, 2);
        if (at == null) {
            send(sender, "&7Give x y z for the chest (from the console).");
            return;
        }
        if (com.vodhanel.minecraft.va_postal.mail.Parcels.admin_accept(r, at,
                com.vodhanel.minecraft.va_postal.store.Actor.admin(sender.getName()), sender)) {
            send(sender, "&6Parcel " + r.id + " accepted: its items are in a chest at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ() + ".");
        } else {
            send(sender, "&7Can't accept it: it must be a DELIVERED parcel, and the spot empty air (it's " + r.state + ").");
        }
    }

    /** {@code /postal refuse <id>}: refuses a delivered parcel for its recipient (testing). */
    public static void refuse(CommandSender sender, String[] args) {
        MailRecord r = target(sender, args, "refuse <mail id>");
        if (r == null) {
            return;
        }
        if (com.vodhanel.minecraft.va_postal.mail.Parcels.admin_refuse(r,
                com.vodhanel.minecraft.va_postal.store.Actor.admin(sender.getName()), sender)) {
            send(sender, "&6Parcel " + r.id + " refused: its items went back to where it was packed.");
        } else {
            send(sender, "&7Can't refuse it: it must be a DELIVERED parcel whose packing spot is clear (it's " + r.state + ").");
        }
    }

    /**
     * {@code /postal setstate <id> <state>}: forces a record into a state (testing only), keeping its custody;
     * recorded in its history as forced by an admin.
     */
    public static void setstate(CommandSender sender, String[] args) {
        MailRecord r = target(sender, args, "setstate <mail id> <state>");
        if (r == null) {
            return;
        }
        if (args.length < 3) {
            send(sender, "&7Usage: /postal setstate <mail id> <state>   states: "
                    + java.util.Arrays.toString(com.vodhanel.minecraft.va_postal.store.MailState.values()));
            return;
        }
        com.vodhanel.minecraft.va_postal.store.MailState to;
        try {
            to = com.vodhanel.minecraft.va_postal.store.MailState.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            send(sender, "&7Unknown state. States: " + java.util.Arrays.toString(com.vodhanel.minecraft.va_postal.store.MailState.values()));
            return;
        }
        try {
            MailRecord now = r.moving()
                    ? MailStores.active().cancel_move(r, com.vodhanel.minecraft.va_postal.store.Actor.admin(sender.getName()), "cleared to force a state")
                    : r;
            MailStores.active().transition(now, to, now.custody,
                    com.vodhanel.minecraft.va_postal.store.Actor.admin(sender.getName()), "forced by an admin (testing)");
            send(sender, "&6" + r.id + " is now " + to + " at " + now.custody + ".");
        } catch (RuntimeException e) {
            send(sender, "&7Couldn't change it: " + e.getMessage());
        }
    }

    /** The record named by args[1], or null (with a message). */
    private static MailRecord target(CommandSender sender, String[] args, String usage) {
        MailStore store = MailStores.active();
        if (store == null) {
            send(sender, "&7The mail store isn't open; mail isn't being tracked.");
            return null;
        }
        if (args.length < 2) {
            send(sender, "&7Usage: /postal " + usage + "   (\"last\" for the newest mail)");
            return null;
        }
        if ("last".equalsIgnoreCase(args[1].trim())) {
            List<MailRecord> recent = store.recent(1);
            if (recent.isEmpty()) {
                send(sender, "&7There's no mail yet.");
                return null;
            }
            return recent.get(0);
        }
        try {
            Optional<MailRecord> found = store.get(UUID.fromString(args[1].trim()));
            if (found.isEmpty()) {
                send(sender, "&7No mail with id " + args[1] + ".");
                return null;
            }
            return found.get();
        } catch (IllegalArgumentException e) {
            send(sender, "&7That isn't a mail id.");
            return null;
        }
    }

    /** x y z from args[from..], or two blocks in front of a player; null from the console without coordinates. */
    private static Location where(CommandSender sender, String[] args, int from) {
        if (args.length >= from + 3) {
            org.bukkit.World w = sender instanceof org.bukkit.entity.Player
                    ? ((org.bukkit.entity.Player) sender).getWorld() : org.bukkit.Bukkit.getWorlds().get(0);
            try {
                return new Location(w, Integer.parseInt(args[from]), Integer.parseInt(args[from + 1]), Integer.parseInt(args[from + 2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (sender instanceof org.bukkit.entity.Player) {
            Location eye = ((org.bukkit.entity.Player) sender).getLocation();
            org.bukkit.util.Vector ahead = eye.getDirection().setY(0);
            if (ahead.lengthSquared() < 1e-6) {
                ahead = new org.bukkit.util.Vector(0, 0, 1);
            }
            return eye.clone().add(ahead.normalize().multiply(2)).getBlock().getLocation();
        }
        return null;
    }

    /** The office's [Postal_Mail] / town / [Local] chest, found the same way the dispatcher finds it. */
    static Block office_chest(String office) {
        Location around = Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
        if (around == null) {
            return null;
        }
        around = around.clone().subtract(0.0D, 1.0D, 0.0D);
        UUID owner = C_Owner.get_owner_local_po_id(office);
        String owner_name = null;
        if (owner != null) {
            OfflinePlayer p = org.bukkit.Bukkit.getOfflinePlayer(owner);
            owner_name = p.getName();
        }
        return SignManip.LookForSignChest(around, VA_postal.search_distance, "[Postal_Mail]", office, "[Local]", owner_name);
    }

    /**
     * {@code /postal store}: the mail store's health: backend, schema, server id, call timings (a remote MySQL
     * adds its latency to every call; main-thread time is what the server feels), failures, and the servers
     * sharing the database.
     */
    public static void store(CommandSender sender) {
        com.vodhanel.minecraft.va_postal.store.MailStore store = MailStores.active();
        send(sender, "&6[Postal] Mail store: &f" + MailStores.description());
        if (store == null) {
            send(sender, "&cNot open: mail isn't being tracked. See the server log for why.");
            return;
        }
        send(sender, "&7Server id &f" + store.server_id() + "&7, schema &f" + store.schema_version());
        com.vodhanel.minecraft.va_postal.store.StoreStats st = MailStores.stats();
        if (st != null) {
            long minutes = Math.max(1L, (System.currentTimeMillis() - st.since) / 60000L);
            send(sender, String.format("&7Calls: &f%d&7 (%.1f a minute), average &f%.2f ms&7, slowest &f%.1f ms&7 (%s)",
                    st.calls.get(), st.calls.get() / (double) minutes, st.average_ms(), st.max_ms(), st.slowest_call));
            send(sender, String.format("&7On the main thread: &f%d&7 calls, &f%.1f ms&7 in all",
                    st.main_thread_calls.get(), st.main_thread_nanos.get() / 1e6D));
            send(sender, "&7Failures: &f" + st.failures.get() + (st.failures.get() > 0 ? "&7 (last: " + st.last_failure + ")" : ""));
            if (st.unavailable != null) {
                send(sender, "&cUnavailable for " + (System.currentTimeMillis() - st.unavailable_since) / 1000L
                        + " s: calls fail at once and mail stays where it is until the database answers again.");
            }
        }
        long now = System.currentTimeMillis();
        for (com.vodhanel.minecraft.va_postal.store.ServerInfo s : store.servers()) {
            String seen = s.last_seen() == 0 ? "stopped" : ((now - s.last_seen()) / 1000L) + " s ago";
            boolean me = s.server_id().equals(store.server_id());
            send(sender, "&7Server &f" + s.server_id() + (me ? " &7(this one)" : "") + "&7: last seen " + seen
                    + (me && !s.instance().equals(com.vodhanel.minecraft.va_postal.mail.Directory.instance())
                    ? " &c(another instance wrote it: two servers share this id?)" : ""));
        }
        if (com.vodhanel.minecraft.va_postal.mail.Directory.last_conflict() != null) {
            send(sender, "&cAnother server used this id: " + com.vodhanel.minecraft.va_postal.mail.Directory.last_conflict());
        }
    }

    /**
     * {@code /postal directory [server]}: the network directory: each server's offices with their address counts,
     * or one server's offices and addresses.
     */
    public static void directory(CommandSender sender, String[] args) {
        com.vodhanel.minecraft.va_postal.store.MailStore store = MailStores.active();
        if (store == null) {
            send(sender, "&7The mail store isn't open.");
            return;
        }
        String server = args.length > 1 ? args[1] : null;
        java.util.List<com.vodhanel.minecraft.va_postal.store.DirectoryEntry> all = store.directory(server);
        if (all.isEmpty()) {
            send(sender, "&7Nothing published" + (server == null ? "" : " by " + server) + " yet (it's published a few"
                    + " seconds after start, then every minute when something changes).");
            return;
        }
        send(sender, "&6[Postal] Directory" + (server == null ? "" : " of " + server));
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (com.vodhanel.minecraft.va_postal.store.DirectoryEntry e : all) {
            if (!e.is_office()) {
                counts.merge(e.server_id() + "/" + e.office(), 1, Integer::sum);
            }
        }
        for (com.vodhanel.minecraft.va_postal.store.DirectoryEntry e : all) {
            if (e.is_office()) {
                send(sender, "&f" + e.server_id() + "&7 / &f" + e.office() + (e.central() ? " &7(Central)" : "")
                        + "&7: " + counts.getOrDefault(e.server_id() + "/" + e.office(), 0) + " addresses");
            } else if (server != null) {
                send(sender, "&7   " + e.address() + (e.open() ? "" : " &c(closed)"));
            }
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
