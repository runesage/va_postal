package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.commands.Cmd_static;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.P_Towny;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Address;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.store.ListMember;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Mass mailers: a signed book copied into many mailboxes at once, the way {@code /dist} does it (a {@code [Distribution]}
 * copy straight into each mailbox chest, expiring after {@code Settings.Distr_exp_days}). Three senders, three lists:
 * <ul>
 *   <li>an office's owner (or an admin) mails its subscribers, players who opted in with {@code /postal subscribe};</li>
 *   <li>a Towny mayor or assistant mails their town's residents, who are on its list until they opt out
 *       ({@code /postal unsubscribe town});</li>
 *   <li>an admin mails every player with an address on the server.</li>
 * </ul>
 * Each copy needs a mailbox: an address the player owns here. Each copy costs the distribution postage, paid to
 * Central as {@code /dist} is.
 */
public final class Mailers {
    private Mailers() {
    }

    private static MailStore store() {
        return MailStores.active();
    }

    // ---- Lists -------------------------------------------------------------------------------

    /** {@code /postal subscribe <office|town>} and {@code /postal unsubscribe <office|town>}. */
    public static void subscription(CommandSender sender, String[] args, boolean subscribe) {
        if (!(sender instanceof Player p)) {
            send(sender, "&7Only players subscribe to mailing lists.");
            return;
        }
        if (store() == null) {
            send(sender, "&7Mailing lists need the mail store, which isn't open.");
            return;
        }
        if (args.length < 2) {
            send(sender, "&7Usage: /postal " + (subscribe ? "subscribe" : "unsubscribe") + " <office | town>");
            return;
        }
        try {
            if ("town".equalsIgnoreCase(args[1])) {
                String town = town_of(p.getUniqueId());
                if (town == null) {
                    send(sender, "&7You don't live in a Towny town.");
                    return;
                }
                // Residents are on their town's list until they opt out: subscribing again just clears the opt-out.
                store().set_list_mode(MailingLists.town_list(town), p.getUniqueId(), p.getName(), subscribe ? null : ListMember.OPTED_OUT,
                        System.currentTimeMillis());
                send(sender, subscribe ? "&6You'll get " + town + "'s town mail again."
                        : "&6You won't get " + town + "'s town mail any more. &7/postal subscribe town to have it again.");
                return;
            }
            String office = C_Postoffice.town_complete(args[1]);
            if ("null".equals(office)) {
                send(sender, "&7No post office matches '" + args[1] + "'. See /tlist.");
                return;
            }
            office = office.toLowerCase(Locale.ROOT).trim();
            store().set_list_mode(MailingLists.office_list(office), p.getUniqueId(), p.getName(), subscribe ? ListMember.SUBSCRIBED : null,
                    System.currentTimeMillis());
            String name = DeedDocument.display(office);
            send(sender, subscribe ? "&6You're on the " + name + " Post Office's mailing list."
                    + (Returns.home(p.getUniqueId(), office) == null ? " &7Its mail goes to an address of yours: you have none yet." : "")
                    : "&6You're off the " + name + " Post Office's mailing list.");
        } catch (StoreException e) {
            send(sender, "&cThe mail store is unavailable: " + e.getMessage());
        }
    }

    /** {@code /postal lists}: the lists the player is on. */
    public static void lists(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            send(sender, "&7Only players are on mailing lists.");
            return;
        }
        if (store() == null) {
            send(sender, "&7Mailing lists need the mail store, which isn't open.");
            return;
        }
        List<String> on = new ArrayList<>();
        String town = town_of(p.getUniqueId());
        boolean town_out = false;
        try {
            for (ListMember m : store().lists_of(p.getUniqueId())) {
                if (m.list().startsWith("office:") && ListMember.SUBSCRIBED.equals(m.mode())) {
                    on.add("the " + DeedDocument.display(m.list().substring(7)) + " Post Office");
                }
                if (town != null && m.list().equals(MailingLists.town_list(town)) && ListMember.OPTED_OUT.equals(m.mode())) {
                    town_out = true;
                }
            }
        } catch (StoreException e) {
            send(sender, "&cThe mail store is unavailable: " + e.getMessage());
            return;
        }
        if (town != null) {
            on.add(0, town_out ? "&8(" + town + "'s town mail: opted out)" : town + "'s town mail");
        }
        send(sender, "&6[Postal] Your mailing lists");
        send(sender, on.isEmpty() ? "&8None. /postal subscribe <office>" : "&f" + String.join("&7, &f", on));
        if (Returns.home(p.getUniqueId(), null) == null) {
            send(sender, "&7Mailings go to an address of yours, and you have none yet.");
        }
    }

    // ---- Sending -----------------------------------------------------------------------------

    /** Who a mailing to {@code target} reaches, and whether {@code sender} may send it; null (with a message) if not. */
    private static Plan plan(Player sender, String target) {
        boolean admin = sender.hasPermission("postal.admin");
        if ("server".equalsIgnoreCase(target)) {
            if (!admin) {
                send(sender, "&7Only admins mail the whole server.");
                return null;
            }
            return new Plan("every address on the server", null, owners());
        }
        if ("town".equalsIgnoreCase(target)) {
            String town = town_of(sender.getUniqueId());
            if (town == null) {
                send(sender, "&7You don't live in a Towny town.");
                return null;
            }
            if (!may_mail_town(sender.getUniqueId())) {
                send(sender, "&7Only " + town + "'s mayor and assistants mail the town.");
                return null;
            }
            String list = MailingLists.town_list(town);
            return new Plan(town + "'s residents", null, MailingLists.members(list, town_residents(town), rows(list)));
        }
        String office = C_Postoffice.town_complete(target);
        if ("null".equals(office)) {
            send(sender, "&7No post office matches '" + target + "'. Mail an office's subscribers, your 'town', or (admins) the 'server'.");
            return null;
        }
        office = office.toLowerCase(Locale.ROOT).trim();
        if (!admin && !sender.getUniqueId().equals(C_Owner.get_owner_local_po_id(office))) {
            send(sender, "&7Only the owner of the " + DeedDocument.display(office) + " Post Office mails its subscribers.");
            return null;
        }
        String list = MailingLists.office_list(office);
        return new Plan("the " + DeedDocument.display(office) + " Post Office's subscribers", office,
                MailingLists.members(list, List.of(), rows(list)));
    }

    private record Plan(String who, String office, List<UUID> members) {
    }

    /**
     * {@code /postal mailer <office | town | server> [confirm]}, with a signed book in hand: says who it reaches and what it
     * costs; with {@code confirm}, copies it into every reachable mailbox and charges for the copies delivered.
     */
    public static void command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&7Hold the book to send: mailings are sent in game.");
            return;
        }
        if (store() == null) {
            send(sender, "&7Mailings need the mail store, which isn't open.");
            return;
        }
        if (args.length < 2) {
            send(sender, "&7Usage: /postal mailer <office | town | server> [confirm]   (a signed book in hand)");
            send(sender, "&7Your lists: /postal lists   Join or leave one: /postal subscribe|unsubscribe <office | town>");
            return;
        }
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType() != Material.WRITTEN_BOOK) {
            send(sender, "&7Hold the signed book you want to send.");
            return;
        }
        Plan plan;
        try {
            plan = plan(player, args[1]);
        } catch (StoreException e) {
            send(sender, "&cThe mail store is unavailable: " + e.getMessage());
            return;
        }
        if (plan == null) {
            return;
        }
        List<String[]> boxes = new ArrayList<>();
        for (UUID member : plan.members()) {
            String[] home = Returns.home(member, plan.office());
            if (home != null) {
                boxes.add(home);
            }
        }
        double price = VA_postal.economy_configured ? C_Economy.distr_price() : 0.0D;
        double most = MailingLists.cost(boxes.size(), price);
        boolean confirm = args.length > 2 && "confirm".equalsIgnoreCase(args[2]);
        if (!confirm) {
            send(sender, "&6A mailing to " + plan.who() + ": &f" + boxes.size() + "&6 mailbox" + (boxes.size() == 1 ? "" : "es")
                    + " &7(" + plan.members().size() + " on the list; only those with an address of their own get it).");
            if (most > 0.0D) {
                send(sender, "&7It costs " + P_Economy.ef(price) + " a copy: up to &f" + P_Economy.ef(most) + "&7.");
            }
            if (!boxes.isEmpty()) {
                send(sender, "&7To send it: &f/postal mailer " + args[1] + " confirm");
            }
            return;
        }
        if (boxes.isEmpty()) {
            send(sender, "&7Nobody on that list has a mailbox to send to.");
            return;
        }
        if (most > 0.0D && !P_Economy.does_player_have_amount(player, most)) {
            send(sender, "&7You need " + P_Economy.ef(most) + " for this mailing.");
            return;
        }
        ItemStack copy = copy_of(player, stack, plan.who());
        int delivered = 0, full = 0, missing = 0;
        for (String[] home : boxes) {
            Chest chest = mailbox(home[0], home[1]);
            if (chest == null) {
                missing++;
            } else if (Cmd_static.add_to_mailbox(chest, copy.clone())) {
                delivered++;
            } else {
                full++;
            }
        }
        player.getInventory().setItemInMainHand(null);
        double paid = MailingLists.cost(delivered, price);
        if (paid > 0.0D && P_Economy.withdraw_from_player(player, paid)) {
            P_Economy.deposit_to_central(paid);
            EconomyState.record(EconomyState.Flow.DISTRIBUTION, paid);
        }
        Util.cinform("[Postal] " + player.getName() + " mailed " + plan.who() + ": " + delivered + " delivered, " + full
                + " mailbox(es) full, " + missing + " not found");
        send(sender, "&6Your mailing went to &f" + delivered + "&6 mailbox" + (delivered == 1 ? "" : "es")
                + (paid > 0.0D ? " &7(" + P_Economy.ef(paid) + ")" : "") + "."
                + (full + missing > 0 ? " &7" + (full + missing) + " couldn't take it (full or missing)." : ""));
    }

    /** The {@code [Distribution]} copy: the book's pages behind a first page saying who it's for, expiring as /dist's do. */
    private static ItemStack copy_of(Player author, ItemStack stack, String who) {
        Book book = new Book(stack);
        String[] pages = book.getPages_with_blank_first_page();
        String date = new SimpleDateFormat("MM/dd/yy HH:mm").format(new Date());
        pages[0] = Book.makeFirstMailPage(who, "", "Resident", "[Central]", "[PostMaster]", book.getAuthor(),
                Util.proper(book.getTitle()), date, author, null);
        long expires = System.currentTimeMillis() / 1000L + 86400L * Math.max(1, GetConfig.distr_exp_days());
        return new Book("[Distribution]", Long.toString(expires), pages).generateItemStack();
    }

    /** The mailbox chest of {@code address} at {@code office}, or null. */
    private static Chest mailbox(String office, String address) {
        String spot = Util.put_point_on_ground(C_Address.get_address_location(office, address), false);
        if (spot == null || "null".equals(spot)) {
            return null;
        }
        Location at = Util.str2location(spot);
        if (at == null) {
            return null;
        }
        at.subtract(0.0D, 1.0D, 0.0D);
        Block b = SignManip.LookForSignChest(at, 10, "[Postal_Mail]", office, address, null);
        return b != null && b.getState() instanceof Chest c ? c : null;
    }

    /** Everyone who owns an address on this server. */
    private static List<UUID> owners() {
        Set<UUID> out = new LinkedHashSet<>();
        String[] offices = C_Arrays.town_list();
        if (offices != null) {
            for (String office : offices) {
                String[] addresses = C_Arrays.addresses_list(office);
                if (addresses == null) {
                    continue;
                }
                for (String a : addresses) {
                    UUID owner = C_Owner.get_owner_address_id(office, a.trim());
                    if (owner != null) {
                        out.add(owner);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    // P_Towny names Towny's classes, so it may only be loaded where Towny is installed: ask first.
    private static boolean towny() {
        return VA_postal.towny_configured && VA_postal.towny_opt_in;
    }

    private static String town_of(UUID player) {
        return towny() ? P_Towny.town_of(player) : null;
    }

    private static boolean may_mail_town(UUID player) {
        return towny() && P_Towny.may_mail_town(player);
    }

    private static List<UUID> town_residents(String town) {
        return towny() ? P_Towny.town_residents(town) : List.of();
    }

    private static List<ListMember> rows(String list) {
        return store().list_members(list);
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }
}
