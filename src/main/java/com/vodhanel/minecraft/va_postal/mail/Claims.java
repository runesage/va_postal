package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.common.P_MailAdmin;
import com.vodhanel.minecraft.va_postal.common.Text;
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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Lost-mail claims (docs/design/central-office.md §9). Mail goes MISSING when the chest its record names doesn't hold
 * it (griefed, taken or destroyed), but the record keeps everything in it: a letter's pages, a parcel's items. So
 * nothing sent is ever really lost, and a claim gives it back rather than paying for it. At Central's counter the
 * sender sees their lost mail, and either has it sent again from Central (the record's copy, so the lost book is void
 * if it turns up) or, for a parcel, takes its items back with the postage refunded. No money is made and nothing is
 * duplicated: a parcel's items only ever come out of its record once.
 */
public final class Claims {
    private Claims() {
    }

    private static MailStore store() {
        return MailStores.active();
    }

    /** {@code sender}'s lost mail on this server, oldest first (empty if the store can't be read). */
    public static List<MailRecord> lost(UUID sender) {
        MailStore store = store();
        if (store == null || sender == null) {
            return List.of();
        }
        try {
            return store.missing_sent_by(sender);
        } catch (StoreException e) {
            Util.cinform("[Postal] Could not read the lost mail of " + sender + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * On the main thread: sends lost mail again from Central. Its record's copy goes into Central's chest (AT_CENTRAL),
     * and Central's postman takes it on from there; its postage hold stands and is settled at delivery as usual. Null if
     * it went, or what stopped it.
     */
    public static String resend(MailRecord r, Actor actor) {
        if (r.state != MailState.MISSING || r.moving()) {
            return "it isn't lost any more (it's " + r.state + ")";
        }
        org.bukkit.block.Block central = com.vodhanel.minecraft.va_postal.clerk.Clerks.office_chest(com.vodhanel.minecraft.va_postal.clerk.Clerks.CENTRAL);
        if (central == null) {
            return "Central's chest can't be found";
        }
        String key = Letters.block_key(Util.location2str(central.getLocation()));
        Inventory chest = Reconciler.chest(Custody.chest(key), true);
        if (chest == null || chest.firstEmpty() < 0) {
            return chest == null ? "Central's chest can't be found" : "Central's chest is full";
        }
        MailRecord held;
        try {
            held = store().transition(r, MailState.AT_CENTRAL, Custody.chest(key), actor, "claimed: sent again from Central");
        } catch (ConflictException e) {
            return "it moved on meanwhile (it may have turned up)";
        } catch (StoreException e) {
            return "the mail store is unavailable";
        }
        chest.addItem(Letters.materialise(held));
        Parcels.clear_packed(r);
        Util.cinform("[Postal] " + noun(r) + " " + r.id + " for " + r.dest_office + ", " + r.dest_address
                + " was claimed: sent again from Central.");
        return null;
    }

    /** On the main thread: a lost parcel's items back to its sender, postage refunded. True if they were handed over. */
    public static boolean take_back(Player player, MailRecord r) {
        if (r.kind != MailKind.PARCEL || r.state != MailState.MISSING || r.moving()) {
            return false;
        }
        boolean done = Parcels.claim_back(player, r, Actor.player(player.getUniqueId()));
        if (done) {
            Util.cinform("[Postal] Parcel " + r.id + " for " + r.dest_office + ", " + r.dest_address
                    + " was claimed back by its sender, " + player.getName() + ".");
        }
        return done;
    }

    /** "Letter" or "Parcel". */
    public static String noun(MailRecord r) {
        return r.kind == MailKind.PARCEL ? "Parcel" : "Letter";
    }

    /** When it went missing, for the counter and the command. */
    public static String when(MailRecord r) {
        return new SimpleDateFormat("d MMM yyyy").format(new Date(r.updated_at));
    }

    /**
     * {@code /postal claim [<id>|last]} (admins): with no id, every lost letter and parcel on this server; with one, sends
     * it again from Central, for its sender. Taking a parcel's items back is for its sender, at Central's counter.
     */
    public static void command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("postal.admin")) {
            send(sender, "&7Lost mail is claimed at Central's counter, from the Postmaster General.");
            return;
        }
        MailStore store = store();
        if (store == null) {
            send(sender, "&7The mail store isn't open; mail isn't being tracked.");
            return;
        }
        if (args.length < 2) {
            List<MailRecord> lost;
            try {
                lost = store.missing();
            } catch (StoreException e) {
                send(sender, "&cThe mail store is unavailable: " + e.getMessage());
                return;
            }
            if (lost.isEmpty()) {
                send(sender, "&6No mail is lost on this server.");
                return;
            }
            send(sender, "&6Lost mail (" + lost.size() + "): &7/postal claim <id> sends one again from Central");
            for (MailRecord r : lost) {
                String from = r.sender == null ? "the server" : Bukkit.getOfflinePlayer(r.sender).getName();
                send(sender, "&f" + r.id + " &7" + noun(r).toLowerCase() + " from &f" + from + "&7 for &f"
                        + Util.df(r.dest_office) + ", " + Util.df(r.dest_address) + "&7, lost " + when(r));
            }
            return;
        }
        MailRecord r = P_MailAdmin.target(sender, args, "claim [<mail id>]");
        if (r == null) {
            return;
        }
        String problem = resend(r, Actor.admin(sender.getName()));
        if (problem != null) {
            send(sender, "&7Couldn't send " + r.id + " again: " + problem + ".");
            return;
        }
        send(sender, "&6" + noun(r) + " " + r.id + " is sent again from Central.");
        if (r.sender != null) {
            Player p = Bukkit.getPlayer(r.sender);
            if (p != null && !p.equals(sender)) {
                Util.pinform(p, "&6Your lost " + noun(r).toLowerCase() + " for &f" + Util.df(r.dest_address)
                        + "&6 was found in the records: Central sends it again.");
            }
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }
}
