package com.vodhanel.minecraft.va_postal.mail;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.store.Actor;
import com.vodhanel.minecraft.va_postal.store.ConflictException;
import com.vodhanel.minecraft.va_postal.store.MailKind;
import com.vodhanel.minecraft.va_postal.store.MailRecord;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Return to sender (issue #19): a letter that can't be delivered goes back to whoever sent it, as the same record,
 * readdressed. The reason travels in its payload ({@code "returned"}), so the sender's server knows it's a return: it
 * delivers it to an address of the sender's, with a page saying why, and it ends RETURNED rather than DELIVERED. A
 * returned letter is never returned again; one that can't reach its sender either waits at Central.
 * <ul>
 *   <li>A network letter for an office its server doesn't have is sent back as soon as it arrives there.</li>
 *   <li>A letter nobody collected from the network within {@code Network.Return_after_days} (default 7; 0: never)
 *       is brought back by the server that sent it.</li>
 * </ul>
 */
public final class Returns {
    private Returns() {
    }

    private static final Gson GSON = new Gson();

    // ---- The payload ------------------------------------------------------------------------

    /** Why {@code r} was returned, or null if it wasn't. */
    public static String reason(MailRecord r) {
        if (r == null || r.kind != MailKind.LETTER || r.payload == null) {
            return null;
        }
        return reason(r.payload);
    }

    static String reason(byte[] payload) {
        try {
            JsonObject o = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
            return o != null && o.has("returned") ? o.get("returned").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True if {@code r} is on its way back to its sender. */
    public static boolean returned(MailRecord r) {
        return reason(r) != null;
    }

    /** The server that sent {@code r} back (for the arrival's announcement), or null if it wasn't returned. */
    static String returned_from(MailRecord r) {
        if (reason(r) == null) {
            return null;
        }
        try {
            JsonObject o = GSON.fromJson(new String(r.payload, StandardCharsets.UTF_8), JsonObject.class);
            return o.has("returned_from") ? o.get("returned_from").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code payload} with the return {@code reason} added. */
    static byte[] stamped(byte[] payload, String reason) {
        return stamped(payload, reason, null);
    }

    /** {@code payload} with the return {@code reason} added, and the server sending it back ({@code from}, if known). */
    static byte[] stamped(byte[] payload, String reason, String from) {
        JsonObject o;
        try {
            o = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
        } catch (RuntimeException e) {
            o = null;
        }
        if (o == null) {
            o = new JsonObject();
        }
        o.addProperty("returned", reason);
        if (from != null) {
            o.addProperty("returned_from", from);
        }
        return GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    /** The last page of a returned letter: why it came back. */
    static String page(String reason) {
        return "§4§lReturned to sender§r\n\n" + reason;
    }

    // ---- Where it goes ----------------------------------------------------------------------

    /** An address of {@code sender}'s on this server ({office, address}), at {@code prefer} if they have one there; or null. */
    static String[] home(UUID sender, String prefer) {
        if (sender == null) {
            return null;
        }
        List<String> offices = new ArrayList<>();
        if (prefer != null) {
            offices.add(prefer.toLowerCase(Locale.ROOT).trim());
        }
        ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                String o = key.toLowerCase(Locale.ROOT).trim();
                if (!offices.contains(o)) {
                    offices.add(o);
                }
            }
        }
        for (String office : offices) {
            if (!C_Postoffice.is_local_po_name_defined(office)) {
                continue;
            }
            String[] addresses = C_Arrays.addresses_list(office);
            if (addresses == null) {
                continue;
            }
            for (String a : addresses) {
                if (sender.equals(C_Owner.get_owner_address_id(office, a.trim()))) {
                    return new String[]{office, a.trim().toLowerCase(Locale.ROOT)};
                }
            }
        }
        return null;
    }

    /** How long, in days, a letter may wait in the network uncollected before its server takes it back (0: never). */
    static double return_after_days() {
        double d = GetConfig.double_value(VA_postal.plugin.getConfig(), GetConfig.path_format("network.return_after_days"), 7.0D);
        return Double.isFinite(d) ? Math.max(0.0D, d) : 7.0D;
    }

    // ---- Sending back -----------------------------------------------------------------------

    /** Readdresses {@code r} (in the network) to its sender's server, due at {@code due}; the new record, or null. */
    static MailRecord send_back(MailStore store, MailRecord r, String reason, long due) {
        try {
            MailRecord back = store.readdress(r, r.origin_server, r.origin_office, null, stamped(r.payload, reason, store.server_id()), due,
                    Actor.central(), "returned to sender: " + reason);
            Util.cinform("[Postal] Letter " + r.id + " goes back to " + r.origin_server + ": " + reason);
            return back;
        } catch (ConflictException e) {
            return null; // changed meanwhile: the next poll sees it as it is now
        } catch (StoreException e) {
            Util.cinform("[Postal] Letter " + r.id + " can't be returned now: " + e.getMessage());
            return null;
        }
    }

    /**
     * A letter in the network has arrived here. Returns the record to claim (readdressed to its sender's mailbox if it's
     * coming home), or null if it was sent straight back (it's for an office this server doesn't have).
     */
    static MailRecord on_arrival(MailStore store, MailRecord r, long transit) {
        String reason = reason(r);
        if (reason == null) {
            if (r.dest_office == null || C_Postoffice.is_local_po_name_defined(r.dest_office)) {
                return r;
            }
            if (store.server_id().equals(r.origin_server)) {
                return r; // ours already: nowhere to send it back to (it waits at Central)
            }
            MailRecord back = send_back(store, r, "there's no post office called " + Util.df(r.dest_office) + " on "
                    + store.server_id(), System.currentTimeMillis() + transit);
            return back == null ? r : null;
        }
        // Coming home: to an address of the sender's (at the office it was posted from, if they have one there).
        String[] home = home(r.sender, r.dest_office);
        if (home == null) {
            Util.cinform("[Postal] Returned letter " + r.id + " has no address of its sender's to go to; it waits at Central.");
            tell(r.sender, "&6A letter of yours came back (" + reason + "), and you have no mailbox here: it waits at Central.");
            return r;
        }
        try {
            MailRecord homeward = store.readdress(r, store.server_id(), home[0], home[1], r.payload, r.due_at, Actor.central(),
                    "returned to " + home[0] + " " + home[1]);
            tell(r.sender, "&6A letter of yours came back (" + reason + "); the postman brings it to &f" + Util.df(home[1]) + "&6.");
            return homeward;
        } catch (ConflictException | StoreException e) {
            return r;
        }
    }

    /** Off the main thread: letters this server sent that nobody collected in time come back (see {@link #return_after_days}). */
    static void bring_back_stranded(MailStore store, long now) {
        double days = return_after_days();
        if (days <= 0.0D) {
            return;
        }
        String within = days >= 1.0D && days == Math.rint(days)
                ? "within " + (long) days + " day" + (days == 1.0D ? "" : "s") : "in time";
        for (MailRecord r : store.stranded(now - (long) (days * 86_400_000L))) {
            if (returned(r)) {
                continue; // a return its server never claimed: leave it, don't bounce it
            }
            send_back(store, r, r.dest_server + " didn't collect it " + within, now);
        }
    }

    private static void tell(UUID player, String message) {
        if (player == null) {
            return;
        }
        Bukkit.getScheduler().runTask(VA_postal.plugin, () -> {
            Player p = Bukkit.getPlayer(player);
            if (p != null) {
                Util.pinform(p, message);
            }
        });
    }
}
