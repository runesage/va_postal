package com.vodhanel.minecraft.va_postal.mail;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Address;
import com.vodhanel.minecraft.va_postal.config.C_Dispatcher;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.store.DirectoryEntry;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This server's part of the network (persistent-state phase P3, §5): every minute it tells the database it's
 * running (so two servers sharing a {@code Network.Server_id} are noticed) and republishes its offices and
 * addresses to the shared directory if they've changed. The directory is built from config.yml on the main
 * thread; the database work runs off it.
 */
public final class Directory {
    static final long PERIOD_TICKS = 1200L;

    private static final UUID INSTANCE = UUID.randomUUID();
    private static final long STARTED = System.currentTimeMillis();
    private static BukkitTask task;
    private static boolean first = true;
    private static volatile List<DirectoryEntry> published;
    private static volatile String last_conflict;

    private Directory() {
    }

    public static UUID instance() {
        return INSTANCE;
    }

    /** The last time another server was found using this server id (null if never). */
    public static String last_conflict() {
        return last_conflict;
    }

    public static void start() {
        stop();
        first = true;
        published = null;
        task = Bukkit.getScheduler().runTaskTimer(VA_postal.plugin, Directory::tick, 100L, PERIOD_TICKS);
    }

    public static void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        MailStore store = MailStores.active();
        if (store != null) {
            try {
                store.sign_off(INSTANCE);
            } catch (StoreException ignored) {
            }
        }
    }

    /** Publishes the directory now (at the next tick), e.g. after an admin command. */
    public static void publish_now() {
        published = null;
        Bukkit.getScheduler().runTask(VA_postal.plugin, Directory::tick);
    }

    private static void tick() {
        MailStore store = MailStores.active();
        if (store == null) {
            return;
        }
        List<DirectoryEntry> entries = build(store.server_id());
        boolean changed = !entries.equals(published);
        boolean beat_first = first;
        first = false;
        Bukkit.getScheduler().runTaskAsynchronously(VA_postal.plugin, () -> {
            try {
                long now = System.currentTimeMillis();
                UUID other = store.heartbeat(INSTANCE, STARTED, now, beat_first);
                if (other != null) {
                    last_conflict = new java.util.Date(now) + ": instance " + other;
                    VA_postal.plugin.getLogger().severe("Another server is using Network.Server_id '" + store.server_id()
                            + "' (instance " + other + "). Every server sharing the mail database needs its own "
                            + "Server_id, or they will move each other's mail.");
                }
                if (changed) {
                    store.publish_directory(entries, now);
                    published = entries;
                }
            } catch (StoreException e) {
                Util.cinform("[Postal] Could not update the network directory: " + e.getMessage());
            }
        });
    }

    /** This server's offices and addresses, from config.yml. */
    static List<DirectoryEntry> build(String server_id) {
        List<DirectoryEntry> out = new ArrayList<>();
        String central = C_Postoffice.get_central_po_location();
        if (central != null && !"null".equals(central)) {
            out.add(new DirectoryEntry(server_id, "central", null, null, true, true, central));
        }
        ConfigurationSection locals = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
        if (locals != null) {
            for (String office : locals.getKeys(false)) {
                String location = C_Postoffice.get_local_po_location_by_name(office);
                out.add(new DirectoryEntry(server_id, office, null, C_Owner.get_owner_local_po_id(office), true, false,
                        "null".equals(location) ? null : location));
                ConfigurationSection addresses = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("address." + office));
                if (addresses == null) {
                    continue;
                }
                for (String address : addresses.getKeys(false)) {
                    String where = C_Address.get_address_location(office, address);
                    out.add(new DirectoryEntry(server_id, office, address, C_Owner.get_owner_address_id(office, address),
                            C_Dispatcher.is_address_open(office, address), false, "null".equals(where) ? null : where));
                }
            }
        }
        return out;
    }
}
