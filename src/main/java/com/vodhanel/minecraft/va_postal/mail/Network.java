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
    /** A name not in the copy re-reads the directory, but no more often than this. */
    static final long REFRESH_MILLIS = 3_000L;

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
        departed_slot = -1L;
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
            return remote_fresh(server, office);
        }
        Resolved local = local(t);
        if (local.office() != null) {
            return local;
        }
        return remote_fresh(null, t);
    }

    /** Another server's office, re-reading the directory once if our copy doesn't have it (a server just started). */
    private static Resolved remote_fresh(String server, String typed) {
        Resolved found = remote(server, typed);
        if (found.office() == null && found.choices().isEmpty() && refresh_directory()) {
            found = remote(server, typed);
        }
        return found;
    }

    /** Re-reads the directory now, at most every few seconds. True if it was read. */
    private static boolean refresh_directory() {
        if (System.currentTimeMillis() - directory_at < REFRESH_MILLIS) {
            return false;
        }
        directory_at = 0L;
        directory();
        return directory_at != 0L;
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
        String found = remote_address_in_copy(office, typed);
        if (found == null && refresh_directory()) {
            found = remote_address_in_copy(office, typed);
        }
        return found;
    }

    private static String remote_address_in_copy(Office office, String typed) {
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

    // ---- The schedule: the mail ship (or train...) -------------------------------------------

    /** How often mail leaves each server's Central for the network ({@code Network.Departure_minutes}). */
    static long departure_millis() {
        return minutes("network.departure_minutes", 10.0D);
    }

    /** How long the trip takes once it leaves ({@code Network.Transit_minutes}). */
    static long transit_millis() {
        return minutes("network.transit_minutes", 5.0D);
    }

    private static long minutes(String path, double fallback) {
        double m = VA_postal.plugin.getConfig().getDouble(GetConfig.path_format(path), fallback);
        return Math.max(0L, Math.round(m * 60_000D));
    }

    /**
     * Departures are on the clock (every period since the epoch), so every server agrees when the mail leaves
     * without talking to the others.
     */
    static long next_departure(long now) {
        long period = Math.max(1_000L, departure_millis());
        return (now / period + 1) * period;
    }

    /** What the vehicle is called in messages ({@code Network.Vehicle}): "the mail ship" by default. */
    static String vehicle() {
        String v = VA_postal.plugin.getConfig().getString(GetConfig.path_format("network.vehicle"), "the mail ship");
        return v == null || v.isBlank() ? "the mail ship" : v.trim();
    }

    private static volatile long departed_slot = -1L;
    private static volatile List<MailRecord> last_out = List.of();
    private static volatile List<MailRecord> last_in = List.of();

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
                last_out = out;
                last_in = in;
                if (System.currentTimeMillis() - directory_at > DIRECTORY_MILLIS) {
                    directory = store.directory(null);
                    directory_at = System.currentTimeMillis();
                }
                Bukkit.getScheduler().runTask(VA_postal.plugin, () -> {
                    try {
                        exchange(store, key, out, in, System.currentTimeMillis());
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

    /**
     * On the main thread: at a departure, the dispatcher takes {@code out} to the ship; when letters in {@code in} have
     * arrived, the dispatcher brings them to the Central chest at {@code key}. One voyage at a time: while the dispatcher is
     * out, the next waits for them (a departure keeps its time, since its slot isn't marked done).
     */
    static void exchange(MailStore store, String key, List<MailRecord> out, List<MailRecord> in, long now) {
        if (CentralDispatcher.busy()) {
            return;
        }
        long period = Math.max(1_000L, departure_millis());
        long slot = now / period;
        boolean departure = departed_slot >= 0 && slot > departed_slot;
        if (departed_slot < 0 || departure) {
            departed_slot = slot; // the first check after a start waits for the next departure
        }
        List<MailRecord> arriving = new ArrayList<>();
        for (MailRecord r : in) {
            if (r.due_at <= now) {
                arriving.add(r);
            }
        }
        org.bukkit.Location at = Util.str2location(VA_postal.central_schest_location);
        if (at == null || at.getWorld() == null) {
            return;
        }
        org.bukkit.block.Block block = at.getBlock();
        if (departure && !out.isEmpty()) {
            long arrives = slot * period + transit_millis();
            CentralDispatcher.voyage(CentralDispatcher.Voyage.DEPARTURE, block, () -> depart(store, key, out, arrives));
        } else if (!arriving.isEmpty()) {
            CentralDispatcher.voyage(CentralDispatcher.Voyage.ARRIVAL, block, () -> arrive(store, key, arriving));
        }
    }

    /** The dispatcher takes the outbound letters: returns their line, or null if none left. */
    private static String depart(MailStore store, String key, List<MailRecord> out, long arrives) {
        Inventory chest = Reconciler.chest(Custody.chest(key), true);
        if (chest == null) {
            return null;
        }
        java.util.Map<String, Integer> sent = new java.util.TreeMap<>();
        for (MailRecord r : out) {
            if (hand_off(store, chest, r, arrives)) {
                sent.merge(r.dest_server, 1, Integer::sum);
            }
        }
        if (sent.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        sent.forEach((server, n) -> announce(proper(vehicle()) + " departs for &f" + server + "&7 with " + letters(n)
                + "; it arrives in " + duration(arrives - now) + "."));
        return CentralDispatcher.line(CentralDispatcher.Voyage.DEPARTURE, and(sent.keySet()), total(sent));
    }

    /** The dispatcher brings the arrived letters: returns their line, or null if none could be claimed. */
    private static String arrive(MailStore store, String key, List<MailRecord> arriving) {
        Inventory chest = Reconciler.chest(Custody.chest(key), true);
        if (chest == null) {
            return null;
        }
        java.util.Map<String, Integer> received = new java.util.TreeMap<>();
        for (MailRecord r : arriving) {
            if (chest.firstEmpty() < 0) {
                break; // Central is full: the rest wait in the network
            }
            if (claim(store, chest, key, r)) {
                received.merge(r.origin_server, 1, Integer::sum);
            }
        }
        if (received.isEmpty()) {
            return null;
        }
        received.forEach((server, n) -> announce(proper(vehicle()) + " from &f" + server + "&7 has arrived with " + letters(n) + "."));
        return CentralDispatcher.line(CentralDispatcher.Voyage.ARRIVAL, and(received.keySet()), total(received));
    }

    private static int total(java.util.Map<String, Integer> counts) {
        int n = 0;
        for (int c : counts.values()) {
            n += c;
        }
        return n;
    }

    private static String and(java.util.Collection<String> names) {
        List<String> list = new ArrayList<>(names);
        if (list.size() <= 1) {
            return list.isEmpty() ? "" : list.get(0);
        }
        return String.join(", ", list.subList(0, list.size() - 1)) + " and " + list.get(list.size() - 1);
    }

    /** A broadcast ({@code Network.Broadcast}), or just the log. The bell is the dispatcher's (or {@link CentralDispatcher#voyage}'s). */
    private static void announce(String message) {
        if (VA_postal.plugin.getConfig().getBoolean(GetConfig.path_format("network.broadcast"), true)) {
            Bukkit.broadcastMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&9[Postal] &7" + message));
        } else {
            Util.cinform("[Postal] " + message.replace("&f", "").replace("&7", ""));
        }
    }

    /** The origin's side: postage is settled here (the origin keeps it), then the book leaves Central. */
    private static boolean hand_off(MailStore store, Inventory chest, MailRecord r, long arrives) {
        if (r.kind != MailKind.LETTER) {
            return false; // never: parcels can't be addressed to another server, and the store refuses them too
        }
        int slot = slot_of(chest, r.id);
        if (slot < 0) {
            return false; // not in the chest: reconciliation decides
        }
        try {
            String hold = HoldTag.read(chest.getItem(slot));
            P_Economy.settle_network_postage(hold != null ? hold : r.hold_id, r.origin_office);
            MailRecord moving = store.begin_move(r, MailState.IN_NETWORK, Custody.NONE, Actor.central(), "to " + r.dest_server);
            chest.setItem(slot, null);
            store.depart(moving, arrives, Actor.central(), "left on " + vehicle());
            Util.dinform("[Postal] Letter " + r.id + " left for " + r.dest_server + ":" + r.dest_office);
            return true;
        } catch (ConflictException e) {
            return false; // changed meanwhile: the next poll sees its new state
        } catch (StoreException e) {
            Util.cinform("[Postal] Letter " + r.id + " can't leave for " + r.dest_server + " now: " + e.getMessage());
            return false;
        }
    }

    /** The destination's side: claim the row, rebuild the book in Central, and let Central deliver it as usual. */
    private static boolean claim(MailStore store, Inventory chest, String key, MailRecord r) {
        MailRecord claimed;
        try {
            claimed = store.claim(r, Custody.chest(key), Actor.central());
        } catch (ConflictException e) {
            return false; // another claim won, or it changed: not ours this time
        } catch (StoreException e) {
            Util.cinform("[Postal] Could not claim letter " + r.id + " from the network: " + e.getMessage());
            return false;
        }
        ItemStack book = Letters.materialise(claimed);
        try {
            if (chest.addItem(book).isEmpty()) {
                store.commit_move(claimed, Actor.central(), "arrived from " + r.origin_server);
            } else {
                store.cancel_move(claimed, Actor.central(), "Central chest full");
                return false;
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
        return true;
    }

    // ---- /postal network ---------------------------------------------------------------------

    /** The schedule, what's waiting to leave, and what's on its way here (as of the last check). */
    public static List<String> schedule() {
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        lines.add("&6[Postal] " + proper(vehicle()) + "&7 leaves every " + duration(departure_millis())
                + " and takes " + duration(transit_millis()) + ".");
        java.util.Map<String, Integer> waiting = new java.util.TreeMap<>();
        for (MailRecord r : last_out) {
            waiting.merge(r.dest_server, 1, Integer::sum);
        }
        StringBuilder to = new StringBuilder();
        waiting.forEach((server, n) -> to.append(to.length() == 0 ? " (" : ", ").append(server).append(": ").append(n));
        if (to.length() > 0) {
            to.append(")");
        }
        lines.add("&7Next departure in &f" + duration(next_departure(now) - now) + "&7: " + letters(last_out.size())
                + " waiting at Central" + to + ".");
        long next = Long.MAX_VALUE;
        for (MailRecord r : last_in) {
            next = Math.min(next, r.due_at);
        }
        lines.add(last_in.isEmpty() ? "&7Nothing on its way here."
                : "&7On its way here: " + letters(last_in.size()) + ", the next "
                + (next <= now ? "arriving now" : "arriving in &f" + duration(next - now)) + "&7.");
        java.util.Set<String> servers = new java.util.TreeSet<>();
        for (DirectoryEntry e : directory()) {
            servers.add(e.server_id());
        }
        servers.remove(local_server());
        lines.add(servers.isEmpty() ? "&7No other servers on this network yet."
                : "&7Other servers: &f" + String.join("&7, &f", servers));
        return lines;
    }

    static String duration(long millis) {
        long s = Math.max(0L, (millis + 999L) / 1000L);
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        return m + " min" + (s % 60 == 0 ? "" : " " + (s % 60) + "s");
    }

    private static String letters(int n) {
        return n + (n == 1 ? " letter" : " letters");
    }

    private static String proper(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
