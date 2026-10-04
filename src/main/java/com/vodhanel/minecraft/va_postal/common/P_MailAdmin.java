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

/** Admin tools for tracked mail: {@code /postal track} and {@code /postal testletter}. */
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
            send(sender, "&7Usage: /postal track <mail id | recent>");
            return;
        }
        if ("recent".equalsIgnoreCase(args[1])) {
            send(sender, "&6[Postal] Recent mail");
            for (MailRecord r : store.recent(10)) {
                send(sender, "&7" + r.id + " &f" + r.dest_office + ", " + r.dest_address + "&7: &e" + r.state
                        + "&7 at " + r.custody + (r.moving() ? " &c(moving)" : ""));
            }
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(args[1].trim());
        } catch (IllegalArgumentException e) {
            send(sender, "&7That isn't a mail id.");
            return;
        }
        Optional<MailRecord> found = store.get(id);
        if (found.isEmpty()) {
            send(sender, "&7No mail with id " + id + ".");
            return;
        }
        MailRecord r = found.get();
        send(sender, "&6[Postal] " + r.kind + " " + r.id);
        send(sender, "&7To &f" + r.dest_office + ", " + r.dest_address + "&7 from &f" + r.origin_office + "&7: &e"
                + r.state + "&7 at " + r.custody + (r.moving() ? " &c(moving to " + r.pending_state + " at " + r.pending_custody + ")" : ""));
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss");
        List<MailEvent> history = store.history(id);
        for (MailEvent e : history) {
            send(sender, "&7  v" + e.version + " " + fmt.format(new Date(e.at)) + " " + (e.from_state == null ? "" : e.from_state + " -> ")
                    + e.to_state + " by " + e.actor_kind + (e.actor_ref == null ? "" : " " + e.actor_ref)
                    + (e.detail == null ? "" : " (" + e.detail + ")"));
        }
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

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }
}
