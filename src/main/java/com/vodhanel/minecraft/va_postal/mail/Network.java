package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Dispatcher;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.store.Actor;
import com.vodhanel.minecraft.va_postal.store.ConflictException;
import com.vodhanel.minecraft.va_postal.store.Custody;
import com.vodhanel.minecraft.va_postal.store.DirectoryEntry;
import com.vodhanel.minecraft.va_postal.store.MailKind;
import com.vodhanel.minecraft.va_postal.store.MailRecord;
import com.vodhanel.minecraft.va_postal.store.MailState;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.NetworkPlayer;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Letters between servers (persistent-state phase P4, docs/design/persistent-state.md §17). One mail row
 * crosses: the origin's Central hands a letter to the network ({@code IN_NETWORK}, the book leaves its Central
 * chest), and the destination claims the row with a version-checked update and rebuilds the book in its own
 * Central chest from the record. Nothing but a letter's text crosses: parcels, COD and items never do.
 * <p>
 * Every {@code Network.Poll_seconds} the database is read off the main thread; the chest work then runs on it.
 * Also keeps the network's players (who is on which server) and a copy of the directory for addressing.
 */
public final class Network {
    /** How often the directory copy used for addressing is refreshed. */
    static final long DIRECTORY_MILLIS = 30_000L;

    private static BukkitTask task;
    private static volatile List<DirectoryEntry> directory = List.of();
    private static volatile long directory_at;
    private static volatile boolean busy;

    private Network() {
    }

    public static void start() {
        stop();
        long period = Math.max(2L, poll_seconds()) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(VA_postal.plugin, Network::tick, 60L, period);
        sync_players();
    }

    public static void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        directory = List.of();
        directory_at = 0L;
    }

    static int poll_seconds() {
        return VA_postal.plugin.getConfig().getInt(GetConfig.path_format("network.poll_seconds"), 10);
    }

    // ---- Players ----------------------------------------------------------------------------

    /** Records the players online here now (startup and reload), replacing whatever this server had recorded. */
    private static void sync_players() {
        MailStore store = MailStores.active();
        if (store == null) {
            return;
        }
        List<NetworkPlayer> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(new NetworkPlayer(p.getUniqueId(), p.getName(), store.server_id(), true, 0L));
        }
        async(() -> store.sync_players(online, System.currentTimeMillis()));
    }

    public static void player_joined(Player player) {
        seen(player, true);
    }

    public static void player_left(Player player) {
        seen(player, false);
    }

    private static void seen(Player player, boolean online) {
        MailStore store = MailStores.active();
        if (store == null) {
            return;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        async(() -> store.player_seen(id, name, online, System.currentTimeMillis()));
    }

    /** A player mail can be addressed to: their UUID and name. */
    public record Recipient(UUID id, String name, String where) {
    }

    /**
     * The player called {@code name}, whatever server they're on and whether or not they're online: online here
     * first, then the network's players, then players who have been on this server. Null if nobody by that name
     * is known.
     */
    public static Recipient find_player(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Util.player_complete(name);
        if (online != null) {
            return new Recipient(online.getUniqueId(), online.getName(), "online here");
        }
        MailStore store = MailStores.active();
        if (store != null) {
            try {
                List<NetworkPlayer> found = store.players_named(name);
                if (!found.isEmpty()) {
                    NetworkPlayer p = found.get(0);
                    String where = p.server_id().equals(store.server_id()) ? "here" : "on " + p.server_id();
                    return new Recipient(p.id(), p.name(), (p.online() ? "online " : "last seen ") + where);
                }
            } catch (StoreException e) {
                // fall through to this server's own records
            }
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null) {
            return new Recipient(cached.getUniqueId(), cached.getName(), "last seen here");
        }
        return null;
    }

    // ---- Addressing -------------------------------------------------------------------------

    /** Where an office name points: {@code server} null for one of this server's offices. */
    public record Office(String server, String office) {
        public boolean remote() {
            return server != null;
        }

        /** How it's written on a letter: the office, or {@code server:office} for another server's. */
        public String label() {
            return server == null ? office : server + ":" + office;
        }
    }

    /** The outcome of resolving an office name. */
    public record Resolved(Office office, List<String> choices) {
        static Resolved none() {
            return new Resolved(null, List.of());
        }
    }

    /**
     * Resolves what a player typed for a post office: one of ours by name (or a unique part of it), otherwise
     * another server's office from the directory. {@code server:office} picks a server's office explicitly;
     * a name several other servers have comes back with {@link Resolved#choices}.
     */
    public static Resolved resolve_office(String typed) {
        if (typed == null || typed.isBlank()) {
            return Resolved.none();
        }
        String t = typed.toLowerCase(Locale.ROOT).trim();
        String local_id = local_server();
        int colon = t.indexOf(':');
        if (colon > 0) {
            String server = t.substring(0, colon);
            String office = t.substring(colon + 1);
            if (server.equalsIgnoreCase(local_id)) {
                return local(office);
            }
            return remote(server, office);
        }
        Resolved local = local(t);
        if (local.office() != null) {
            return local;
        }
        return remote(null, t);
    }

    private static Resolved local(String office) {
        String found = C_Postoffice.town_complete(office);
        return "null".equals(found) ? Resolved.none() : new Resolved(new Office(null, found), List.of());
    }

    /** Another server's office: an exact name wins, otherwise a unique part of one (like local offices). */
    private static Resolved remote(String server, String typed) {
        String local_id = local_server();
        List<DirectoryEntry> exact = new ArrayList<>();
        List<DirectoryEntry> partial = new ArrayList<>();
        for (DirectoryEntry e : directory()) {
            if (!e.is_office() || e.central() || e.server_id().equalsIgnoreCase(local_id)
                    || (server != null && !e.server_id().equalsIgnoreCase(server))) {
                continue;
            }
            String name = e.office().toLowerCase(Locale.ROOT);
            if (name.equals(typed)) {
                exact.add(e);
            } else if (name.contains(typed)) {
                partial.add(e);
            }
        }
        List<DirectoryEntry> hits = exact.isEmpty() ? partial : exact;
        if (hits.size() == 1) {
            return new Resolved(new Office(hits.get(0).server_id(), hits.get(0).office().toLowerCase(Locale.ROOT)), List.of());
        }
        List<String> choices = new ArrayList<>();
        for (DirectoryEntry e : hits) {
            choices.add(e.server_id() + ":" + e.office());
        }
        return new Resolved(null, choices);
    }

    /** An address at another server's office, completed from a unique part of its name; null if none. */
    public static String remote_address(Office office, String typed) {
        String t = typed.toLowerCase(Locale.ROOT).trim();
        String partial = null;
        int hits = 0;
        for (DirectoryEntry e : directory()) {
            if (e.is_office() || !e.server_id().equalsIgnoreCase(office.server()) || !e.office().equalsIgnoreCase(office.office())) {
                continue;
            }
            String name = e.address().toLowerCase(Locale.ROOT);
            if (name.equals(t)) {
                return name;
            }
            if (name.contains(t)) {
                partial = name;
                hits++;
            }
        }
        return hits == 1 ? partial : null;
    }

    /** The addresses another server has published for {@code office}. */
    public static List<String> remote_addresses(Office office) {
        List<String> out = new ArrayList<>();
        for (DirectoryEntry e : directory()) {
            if (!e.is_office() && e.server_id().equalsIgnoreCase(office.server()) && e.office().equalsIgnoreCase(office.office())) {
                out.add(e.address());
            }
        }
        return out;
    }

    /** Other servers' offices and their addresses, for listing. */
    public static List<DirectoryEntry> remote_entries() {
        String local_id = local_server();
        List<DirectoryEntry> out = new ArrayList<>();
        for (DirectoryEntry e : directory()) {
            if (!e.server_id().equalsIgnoreCase(local_id) && !e.central()) {
                out.add(e);
            }
        }
        return out;
    }

    /** The directory copy, read now if it's older than {@link #DIRECTORY_MILLIS} (e.g. right after startup). */
    static List<DirectoryEntry> directory() {
        if (System.currentTimeMillis() - directory_at > DIRECTORY_MILLIS) {
            MailStore store = MailStores.active();
            if (store != null) {
                try {
                    directory = store.directory(null);
                    directory_at = System.currentTimeMillis();
                } catch (StoreException e) {
                    // keep the copy we have
                }
            }
        }
        return directory;
    }

    private static String local_server() {
        MailStore store = MailStores.active();
        return store == null ? "" : store.server_id();
    }

    // ---- Moving letters between servers ----------------------------------------------------

    private static void tick() {
        MailStore store = MailStores.active();
        String central = VA_postal.central_schest_location;
        if (store == null || busy || central == null || "null".equals(central)) {
            return;
        }
        String key = Letters.block_key(central);
        busy = true;
        Bukkit.getScheduler().runTaskAsynchronously(VA_postal.plugin, () -> {
            try {
                List<MailRecord> out = store.outbound(key);
                List<MailRecord> in = store.in_network();
                if (System.currentTimeMillis() - directory_at > DIRECTORY_MILLIS) {
                    directory = store.directory(null);
                    directory_at = System.currentTimeMillis();
                }
                if (out.isEmpty() && in.isEmpty()) {
                    busy = false;
                    return;
                }
                Bukkit.getScheduler().runTask(VA_postal.plugin, () -> {
                    try {
                        exchange(store, key, out, in);
                    } finally {
                        busy = false;
                    }
                });
            } catch (StoreException e) {
                busy = false;
            } catch (RuntimeException e) {
                busy = false;
                throw e;
            }
        });
    }

    /** On the main thread: hands {@code out} to the network and claims {@code in} into the Central chest at {@code key}. */
    static void exchange(MailStore store, String key, List<MailRecord> out, List<MailRecord> in) {
        Inventory chest = Reconciler.chest(Custody.chest(key), true);
        if (chest == null) {
            return;
        }
        for (MailRecord r : out) {
            hand_off(store, chest, r);
        }
        for (MailRecord r : in) {
            if (chest.firstEmpty() < 0) {
                break; // Central is full: the rest wait in the network
            }
            claim(store, chest, key, r);
        }
    }

    /** The origin's side: postage is settled here (the origin keeps it), then the book leaves Central. */
    private static void hand_off(MailStore store, Inventory chest, MailRecord r) {
        if (r.kind != MailKind.LETTER) {
            return; // never: parcels can't be addressed to another server, and the store refuses them too
        }
        int slot = slot_of(chest, r.id);
        if (slot < 0) {
            return; // not in the chest: reconciliation decides
        }
        try {
            String hold = HoldTag.read(chest.getItem(slot));
            P_Economy.settle_network_postage(hold != null ? hold : r.hold_id, r.origin_office);
            MailRecord moving = store.begin_move(r, MailState.IN_NETWORK, Custody.NONE, Actor.central(), "to " + r.dest_server);
            chest.setItem(slot, null);
            store.commit_move(moving, Actor.central(), null);
            Util.dinform("[Postal] Letter " + r.id + " left for " + r.dest_server + ":" + r.dest_office);
        } catch (ConflictException e) {
            // changed meanwhile: the next poll sees its new state
        } catch (StoreException e) {
            Util.cinform("[Postal] Letter " + r.id + " can't leave for " + r.dest_server + " now: " + e.getMessage());
        }
    }

    /** The destination's side: claim the row, rebuild the book in Central, and let Central deliver it as usual. */
    private static void claim(MailStore store, Inventory chest, String key, MailRecord r) {
        MailRecord claimed;
        try {
            claimed = store.claim(r, Custody.chest(key), Actor.central());
        } catch (ConflictException e) {
            return; // another claim won, or it changed: not ours this time
        } catch (StoreException e) {
            Util.cinform("[Postal] Could not claim letter " + r.id + " from the network: " + e.getMessage());
            return;
        }
        ItemStack book = Letters.materialise(claimed);
        try {
            if (chest.addItem(book).isEmpty()) {
                store.commit_move(claimed, Actor.central(), "arrived from " + r.origin_server);
            } else {
                store.cancel_move(claimed, Actor.central(), "Central chest full");
                return;
            }
        } catch (ConflictException | StoreException e) {
            Util.cinform("[Postal] Could not record letter " + r.id + " arriving (reconciliation will): " + e.getMessage());
        }
        if (!C_Postoffice.town_complete(r.dest_office).equalsIgnoreCase(r.dest_office)) {
            Util.cinform("[Postal] Letter " + r.id + " from " + r.origin_server + " is for " + r.dest_office
                    + ", which isn't one of this server's post offices; it waits in the Central chest.");
        } else {
            C_Dispatcher.promote_central(r.dest_office, 5000);
        }
    }

    private static int slot_of(Inventory chest, UUID id) {
        for (int slot = 0; slot < chest.getSize(); slot++) {
            ItemStack item = chest.getItem(slot);
            if (item != null && id.equals(MailIds.read(item))) {
                return slot;
            }
        }
        return -1;
    }

    private static void async(Runnable work) {
        Bukkit.getScheduler().runTaskAsynchronously(VA_postal.plugin, () -> {
            try {
                work.run();
            } catch (StoreException e) {
                Util.cinform("[Postal] Could not update the network's players: " + e.getMessage());
            }
        });
    }
}
