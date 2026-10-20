package com.vodhanel.minecraft.va_postal.boxes;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.store.ConflictException;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.PoBox;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * P.O. boxes (docs/design/po-boxes.md), phase 1: leases. A player rents a numbered box at a post office for a daily
 * rent (the office's price, or {@code Settings.Boxes.Default_rent}); the first day is paid up front, and each Postal day
 * charges the next, to the office's account (Central's, for an office the server owns). Rent that can't be paid is
 * arrears; after {@code Settings.Boxes.Grace_days} days of it the lease lapses.
 */
public final class PoBoxes {
    private PoBoxes() {
    }

    private static MailStore store() {
        return MailStores.active();
    }

    // ---- Settings ---------------------------------------------------------------------------

    static double default_rent() {
        return Math.max(0.0D, GetConfig.double_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.boxes.default_rent"), 5.0D));
    }

    static int grace_days() {
        return Math.max(0, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.boxes.grace_days"), 3));
    }

    static int max_per_player() {
        return Math.max(1, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.boxes.max_per_player"), 1));
    }

    /** Boxes an office may let (0: no limit). */
    static int max_per_office() {
        return Math.max(0, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.boxes.max_per_office"), 0));
    }

    /** How near the office chest a player must be to rent there. */
    static int rent_distance() {
        return Math.max(1, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.boxes.rent_distance"), 16));
    }

    /** The office's own daily rent, or null for the default. */
    static Double own_rent(String office) {
        String s = VA_postal.plugin.getConfig().getString(GetConfig.path_format("postoffice.local." + office + ".box_rent"));
        if (s == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) && v >= 0.0D ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** What a box at {@code office} costs a day; 0 without an economy. */
    public static double rent(String office) {
        if (!VA_postal.economy_configured) {
            return 0.0D;
        }
        Double own = own_rent(office);
        return own != null ? own : default_rent();
    }

    static String money(double v) {
        return v <= 0.0D ? "free" : P_Economy.ef(v);
    }

    // ---- Leases -----------------------------------------------------------------------------

    /** Rents {@code player} the next free box at {@code office}; null, or why not. {@code checked}: the limits and the place apply. */
    static String rent(Player player, String office, boolean checked) {
        MailStore store = store();
        if (store == null) {
            return "Boxes need the mail store, which isn't open.";
        }
        List<PoBox> mine = store.boxes_of(player.getUniqueId());
        if (checked && mine.size() >= max_per_player()) {
            return "You already rent " + (mine.size() == 1 ? mine.get(0).label() + " at " + DeedDocument.display(mine.get(0).office())
                    : mine.size() + " boxes") + " (the most a player may).";
        }
        if (checked && max_per_office() > 0 && store.boxes(office).size() >= max_per_office()) {
            return "Every box at " + DeedDocument.display(office) + " is let.";
        }
        if (checked) {
            Location chest = Util.str2location(C_Postoffice.get_local_po_location_by_name(office));
            if (chest == null || chest.getWorld() != player.getWorld() || chest.distance(player.getLocation()) > rent_distance()) {
                return "Rent a box at the office: within " + rent_distance() + " blocks of the " + DeedDocument.display(office) + " Post Office.";
            }
        }
        double first = rent(office);
        if (first > 0.0D && !P_Economy.does_player_have_amount(player, first)) {
            return "A box at " + DeedDocument.display(office) + " costs " + money(first) + " a day, paid in advance.";
        }
        PoBox box;
        try {
            box = store.rent_box(office, player.getUniqueId(), player.getName(), System.currentTimeMillis());
        } catch (ConflictException e) {
            return "Someone took that box just now; try again.";
        }
        if (first > 0.0D) {
            if (P_Economy.withdraw_from_player(player, first)) {
                pay_office(office, first);
            } else {
                store.release_box(office, box.number());
                return "A box at " + DeedDocument.display(office) + " costs " + money(first) + " a day, paid in advance.";
            }
        }
        Util.cinform("[Postal] " + player.getName() + " rented " + box.label() + " at " + office);
        return null;
    }

    /** The day's rent to the office's account, or Central's for an office the server owns. */
    private static void pay_office(String office, double amount) {
        if (C_Owner.get_owner_local_po_id(office) == null) {
            P_Economy.deposit_to_central(amount);
        } else {
            P_Economy.deposit_to_local(office, amount);
        }
        EconomyState.record(EconomyState.Flow.BOX_RENT, amount);
    }

    /** Ends a lease (released, evicted or lapsed). */
    static boolean end(PoBox box, String why) {
        MailStore store = store();
        if (store == null || !store.release_box(box.office(), box.number())) {
            return false;
        }
        Util.cinform("[Postal] " + box.label() + " at " + box.office() + " (" + box.renter_name() + ") " + why);
        return true;
    }

    // ---- The Postal day ---------------------------------------------------------------------

    /** Each Postal day: every lease's rent, arrears, and lapses. Returns {charged, leases lapsed}. */
    public static double[] charge_day() {
        MailStore store = store();
        if (store == null || !VA_postal.economy_configured) {
            return new double[]{0.0D, 0.0D};
        }
        double charged = 0.0D;
        int lapsed = 0;
        List<PoBox> leases;
        try {
            leases = store.boxes(null);
        } catch (StoreException e) {
            return new double[]{0.0D, 0.0D};
        }
        for (PoBox box : leases) {
            OfflinePlayer renter = Bukkit.getOfflinePlayer(box.renter());
            double available = P_Economy.player_balance(renter);
            BoxRent.Day d = BoxRent.day(rent(box.office()), box.arrears(), box.arrears_days(), available, grace_days());
            if (d.paid() > 0.0D) {
                if (P_Economy.withdraw_from_player(renter, d.paid())) {
                    pay_office(box.office(), d.paid());
                    charged += d.paid();
                } else {
                    d = BoxRent.day(rent(box.office()), box.arrears(), box.arrears_days(), 0.0D, grace_days());
                }
            }
            if (d.lapses()) {
                if (end(box, "lapsed with " + money(d.arrears()) + " owed")) {
                    lapsed++;
                    tell(box.renter(), "&cYour " + box.label() + " at " + DeedDocument.display(box.office())
                            + " has lapsed: its rent went unpaid for more than " + grace_days() + " days.");
                }
                continue;
            }
            store.set_box_arrears(box.office(), box.number(), d.arrears(), d.arrears_days());
            if (d.arrears() > 0.0D) {
                tell(box.renter(), "&6You owe " + money(d.arrears()) + " for your " + box.label() + " at "
                        + DeedDocument.display(box.office()) + ". It lapses after " + grace_days() + " days unpaid.");
            }
        }
        return new double[]{charged, lapsed};
    }

    private static void tell(UUID player, String message) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) {
            Util.pinform(p, message);
        }
    }

    // ---- /pobox -----------------------------------------------------------------------------

    /** {@code /pobox [rent <office> | release [office] | price <office> [amount|default] | list <office> | cabinet <office> [x y z] | evict <office> <n>]}. */
    public static void command(CommandSender sender, String[] args) {
        MailStore store = store();
        if (store == null) {
            send(sender, "&7Boxes need the mail store, which isn't open.");
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = !(sender instanceof Player) || sender.hasPermission("postal.admin");
        try {
            switch (sub) {
                case "" -> info(sender);
                case "rent" -> {
                    String office = office(sender, args, 1);
                    if (office == null) {
                        return;
                    }
                    Player p = args.length > 2 && admin ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player pl ? pl : null;
                    if (p == null) {
                        send(sender, "&7Rent for whom? /pobox rent <office> <player>");
                        return;
                    }
                    String problem = rent(p, office, p == sender);
                    if (problem != null) {
                        send(sender, "&7" + problem);
                        return;
                    }
                    PoBox box = store.boxes_of(p.getUniqueId()).stream().filter(b -> b.office().equals(office))
                            .reduce((a, b) -> b).orElseThrow();
                    String line = "&6" + (p == sender ? "You rent" : p.getName() + " rents") + " &f" + box.label() + "&6 at the &f"
                            + DeedDocument.display(office) + "&6 Post Office, for " + money(rent(office)) + " a day.";
                    send(sender, line);
                    if (p != sender) {
                        Util.pinform(p, line);
                    }
                }
                case "release" -> {
                    if (!(sender instanceof Player p)) {
                        send(sender, "&7Only a renter can release a box. Admins: /pobox evict <office> <n>.");
                        return;
                    }
                    List<PoBox> mine = store.boxes_of(p.getUniqueId());
                    if (args.length > 1) {
                        String office = office(sender, args, 1);
                        if (office == null) {
                            return;
                        }
                        mine = mine.stream().filter(b -> b.office().equals(office)).toList();
                    }
                    if (mine.isEmpty()) {
                        send(sender, "&7You don't rent a box" + (args.length > 1 ? " there." : "."));
                        return;
                    }
                    if (mine.size() > 1) {
                        send(sender, "&7You rent more than one box; say which office: /pobox release <office>.");
                        return;
                    }
                    PoBox box = mine.get(0);
                    int held = store.in_box(box.office(), box.number()).size();
                    if (held > 0) {
                        send(sender, "&7Your " + box.label() + " still holds " + held + " letter(s). Take them out first.");
                        return;
                    }
                    if (end(box, "released")) {
                        send(sender, "&6You no longer rent " + box.label() + " at " + DeedDocument.display(box.office()) + ".");
                    }
                }
                case "price" -> {
                    String office = office(sender, args, 1);
                    if (office == null) {
                        return;
                    }
                    if (args.length < 3) {
                        send(sender, "&7A box at " + DeedDocument.display(office) + " costs " + money(rent(office)) + " a day"
                                + (own_rent(office) != null ? " (the office's price)." : " (the server's price)."));
                        return;
                    }
                    if (!admin && !(sender instanceof Player p && p.getUniqueId().equals(C_Owner.get_owner_local_po_id(office)))) {
                        send(sender, "&7Only the owner of " + DeedDocument.display(office) + " can set its box rent.");
                        return;
                    }
                    String value = null;
                    if (!"default".equalsIgnoreCase(args[2])) {
                        try {
                            double v = Double.parseDouble(args[2]);
                            if (!Double.isFinite(v) || v < 0.0D) {
                                throw new NumberFormatException();
                            }
                            value = String.format(Locale.ROOT, "%.2f", v);
                        } catch (NumberFormatException e) {
                            send(sender, "&7'" + args[2] + "' isn't a price. Use an amount, or 'default'.");
                            return;
                        }
                    }
                    VA_postal.plugin.getConfig().set(GetConfig.path_format("postoffice.local." + office + ".box_rent"), value);
                    VA_postal.plugin.saveConfig();
                    send(sender, "&6A box at " + DeedDocument.display(office) + " now costs " + money(rent(office)) + " a day.");
                }
                case "list" -> {
                    String office = office(sender, args, 1);
                    if (office == null) {
                        return;
                    }
                    if (!admin && !(sender instanceof Player p && p.getUniqueId().equals(C_Owner.get_owner_local_po_id(office)))) {
                        send(sender, "&7Only the owner of " + DeedDocument.display(office) + " can list its boxes.");
                        return;
                    }
                    List<PoBox> boxes = store.boxes(office);
                    send(sender, "&6[Postal] Boxes at " + DeedDocument.display(office) + ": " + boxes.size() + " &7(" + money(rent(office)) + " a day)");
                    for (PoBox b : boxes) {
                        send(sender, "&7" + b.label() + ": &f" + b.renter_name() + (b.arrears() > 0.0D
                                ? " &c(owes " + money(b.arrears()) + ", " + b.arrears_days() + " day(s))" : ""));
                    }
                }
                case "cabinet" -> {
                    String office = office(sender, args, 1);
                    if (office == null) {
                        return;
                    }
                    BoxCabinet.set_cabinet(sender, office, args);
                }
                case "evict" -> {
                    if (!admin) {
                        send(sender, "&7Required permission not present.");
                        return;
                    }
                    String office = office(sender, args, 1);
                    if (office == null) {
                        return;
                    }
                    int n;
                    try {
                        n = Integer.parseInt(args.length > 2 ? args[2].replaceAll("(?i)^box", "") : "");
                    } catch (NumberFormatException e) {
                        send(sender, "&7/pobox evict <office> <number>");
                        return;
                    }
                    PoBox box = store.box(office, n).orElse(null);
                    if (box == null || !end(box, "evicted by " + sender.getName())) {
                        send(sender, "&7Box " + n + " at " + DeedDocument.display(office) + " isn't rented.");
                        return;
                    }
                    send(sender, "&6Ended " + box.renter_name() + "'s lease of " + box.label() + " at " + DeedDocument.display(office) + ".");
                    tell(box.renter(), "&cYour " + box.label() + " at " + DeedDocument.display(office) + " was closed by the Post Office.");
                }
                default -> send(sender, "&7/pobox [rent <office> | release [office] | price <office> [amount|default] | list <office> | cabinet <office> [x y z]"
                        + (admin ? " | evict <office> <n>" : "") + "]");
            }
        } catch (StoreException e) {
            send(sender, "&7The mail store can't be reached now.");
        }
    }

    private static void info(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            send(sender, "&7/pobox rent|release|price|list|cabinet|evict ...");
            return;
        }
        List<PoBox> mine = store().boxes_of(p.getUniqueId());
        if (mine.isEmpty()) {
            send(sender, "&7You don't rent a P.O. box. &f/pobox rent <office>&7 at a post office (" + money(default_rent()) + " a day there unless it sets its own).");
            return;
        }
        for (PoBox b : mine) {
            int held = store().in_box(b.office(), b.number()).size();
            send(sender, "&6" + b.label() + " at the " + DeedDocument.display(b.office()) + " Post Office &7(" + money(rent(b.office())) + " a day)"
                    + (held > 0 ? " &f" + held + " letter(s) waiting" : "")
                    + (b.arrears() > 0.0D ? " &cOwed: " + money(b.arrears()) + " (" + b.arrears_days() + " day(s); lapses after "
                    + grace_days() + ")" : ""));
        }
    }

    private static String office(CommandSender sender, String[] args, int i) {
        if (args.length <= i) {
            send(sender, "&7Which post office? See /tlist.");
            return null;
        }
        String office = C_Postoffice.town_complete(args[i]);
        if ("null".equals(office)) {
            send(sender, "&7No post office matches '" + args[i] + "'. See /tlist.");
            return null;
        }
        return office.toLowerCase(Locale.ROOT).trim();
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }
}
