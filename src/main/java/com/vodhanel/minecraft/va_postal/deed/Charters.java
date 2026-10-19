package com.vodhanel.minecraft.va_postal.deed;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.AnsiColor;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.economy.Reserves;
import com.vodhanel.minecraft.va_postal.mail.BookManip;
import com.vodhanel.minecraft.va_postal.mail.ChestManip;
import com.vodhanel.minecraft.va_postal.store.Deed;
import com.vodhanel.minecraft.va_postal.store.DirectoryEntry;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.ServerInfo;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Charters (docs/design/central-office.md §3): a post office bought at Central as a deed of the CHARTER kind, and
 * opened where its holder uses it on a chest. The name is checked and reserved when it's bought, across the network;
 * Central holds the price until the charter is used (then it seeds the office as an office purchase does) or lapses.
 * Applications need an admin's approval unless {@code Settings.Charters.Require_approval} is off
 * ({@code /postal charter autoapprove}).
 */
public final class Charters {
    private Charters() {
    }

    private static MailStore store() {
        return MailStores.active();
    }

    // ---- Settings ---------------------------------------------------------------------------

    /** Whether charters need an admin's approval ({@code Settings.Charters.Require_approval}, default true). */
    public static boolean approval_required() {
        return GetConfig.bool_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.charters.require_approval"), true);
    }

    public static void require_approval(boolean on) {
        VA_postal.plugin.getConfig().set(GetConfig.path_format("settings.charters.require_approval"), Boolean.toString(on));
        VA_postal.plugin.saveConfig();
    }

    /** How near another post office (or Central) a new one may open ({@code Settings.Charters.Min_distance}). */
    public static int min_distance() {
        return Math.max(0, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.charters.min_distance"), 64));
    }

    private static boolean setting(String path, boolean fallback) {
        return GetConfig.bool_value(VA_postal.plugin.getConfig(), GetConfig.path_format(path), fallback);
    }

    /** What a charter costs: the post office purchase price. */
    public static double price() {
        return C_Economy.po_purchase_price();
    }

    // ---- Names ------------------------------------------------------------------------------

    /**
     * An office name as stored (lower case, underscores for spaces), or null with the reason in {@code why}: it fits a
     * sign line, and no office has it or holds a charter for it, here or on another server of the network.
     */
    public static String charter_name(String input, StringBuilder why) {
        String name = HouseNames.stored(input == null ? "" : input);
        if (name.isEmpty() || !name.matches("[a-z0-9_]+")) {
            why.append("an office's name is letters, numbers and spaces");
            return null;
        }
        if (name.length() > HouseNames.MAX_LENGTH) {
            why.append("an office's name fits a sign line: at most ").append(HouseNames.MAX_LENGTH).append(" characters");
            return null;
        }
        if ("central".equals(name)) {
            why.append("Central is Central");
            return null;
        }
        if (C_Postoffice.is_local_po_name_defined(name)) {
            why.append("there's already a post office called ").append(DeedDocument.display(name));
            return null;
        }
        MailStore store = store();
        if (store != null) {
            try {
                if (store.deed_name_reserved(Deed.Kind.CHARTER, name, name)) {
                    why.append("someone already holds a charter for ").append(DeedDocument.display(name));
                    return null;
                }
                String elsewhere = other_server_with(store, name);
                if (elsewhere != null) {
                    why.append(elsewhere).append(" already has a post office called ").append(DeedDocument.display(name));
                    return null;
                }
            } catch (StoreException e) {
                why.append("the mail store can't check the name now");
                return null;
            }
        }
        return name;
    }

    /** Another server of the network with an office called {@code name}, from the shared directory; null if none. */
    private static String other_server_with(MailStore store, String name) {
        for (ServerInfo s : store.servers()) {
            if (s.server_id().equals(store.server_id())) {
                continue;
            }
            for (DirectoryEntry e : store.directory(s.server_id())) {
                if (!e.central() && name.equalsIgnoreCase(e.office())) {
                    return s.server_id();
                }
            }
        }
        return null;
    }

    // ---- Issuing ----------------------------------------------------------------------------

    /**
     * A charter for an office called {@code input}, held by {@code holder}, charged {@code price}; with {@code pending}
     * an application for an admin to decide (Applications), otherwise issued and handed over at once.
     */
    public static Deeds.Result issue(OfflinePlayer holder, String holder_name, String input, double price, boolean pending) {
        StringBuilder why = new StringBuilder();
        String name = charter_name(input, why);
        if (name == null) {
            return Deeds.Result.failed(why.toString());
        }
        return Deeds.issue(Deed.Kind.CHARTER, holder, holder_name, name, name, price, pending);
    }

    /** This server's charter applications, oldest first. */
    public static List<Deed> pending() {
        MailStore store = store();
        return store == null ? List.of() : store.deeds_pending(Deed.Kind.CHARTER);
    }

    // ---- Opening an office ------------------------------------------------------------------

    /** {@code player} used their (usable) charter on {@code chest}. */
    static void use(Player player, Block chest, Deed deed) {
        String problem = place_problem(chest);
        if (problem != null) {
            Util.pinform(player, "&7&o" + problem);
            return;
        }
        open(deed, chest, player, outcome -> Util.pinform(player, outcome));
    }

    /** Uses an issued charter on {@code chest} with nobody holding it: the console test helper. */
    public static void use_as_admin(Deed deed, Block chest, Consumer<String> report) {
        String problem = deed.state() == Deed.State.ISSUED ? place_problem(chest) : "the charter isn't issued";
        if (problem != null) {
            report.accept(problem);
            return;
        }
        open(deed, chest, null, report);
    }

    /** Why {@code chest} can't become a new office's chest, or null. */
    static String place_problem(Block chest) {
        if (!ChestManip.ok_to_use_chest(chest, true)) {
            return "This chest is already a Postal chest, or has a sign on it.";
        }
        Location front = Deeds.front_of(chest).getLocation();
        if (!front.getBlock().isPassable() || !front.getBlock().getRelative(BlockFace.UP).isPassable()) {
            return "The postman needs room to stand two blocks in front of this chest.";
        }
        int min = min_distance();
        String near = nearest_office_within(chest.getLocation(), min);
        if (near != null) {
            return "This is too near " + near + ": a new post office must be at least " + min + " blocks from another.";
        }
        return null;
    }

    /** The name of an office (or Central) within {@code distance} of {@code at}, or null. */
    static String nearest_office_within(Location at, int distance) {
        if (distance <= 0) {
            return null;
        }
        List<String[]> offices = new ArrayList<>();
        offices.add(new String[]{"Central", C_Postoffice.get_central_po_location()});
        ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                offices.add(new String[]{DeedDocument.display(key), C_Postoffice.get_local_po_location_by_name(key)});
            }
        }
        for (String[] o : offices) {
            Location l = o[1] == null || "null".equals(o[1]) ? null : Util.str2location(o[1]);
            if (l != null && l.getWorld() == at.getWorld() && l.distance(at) < distance) {
                return o[0].startsWith("Central") ? "Central" : "the " + o[0] + " Post Office";
            }
        }
        return null;
    }

    private static void open(Deed deed, Block chest, Player player, Consumer<String> report) {
        MailStore store = store();
        if (C_Postoffice.is_local_po_name_defined(deed.office())) {
            report.accept("&7&oThere's already a post office called " + DeedDocument.display(deed.office()) + ".");
            return;
        }
        if (store == null || !store.deed_state(deed.id(), Deed.State.ISSUED, Deed.State.USED, System.currentTimeMillis())) {
            report.accept("&7&oThis charter can't be used any more.");
            return;
        }
        create_office(deed, chest);
        if (player != null) {
            Deeds.take_documents(player, deed.id());
        }
        ceremony(deed, chest);
        report.accept("&aThe &f" + DeedDocument.display(deed.office()) + "&a Post Office is open!");
    }

    private static void create_office(Deed deed, Block chest) {
        String office = deed.office();
        Block front = Deeds.front_of(chest);
        C_Postoffice.save_local_postoffice(office, Util.location2str(front.getLocation()));
        // The owner first: the sign's owner line is read from it. A charter held by the server makes a server office.
        boolean server_held = VA_postal.SERVER != null && VA_postal.SERVER.getUniqueId().equals(deed.holder());
        Player owner = server_held ? VA_postal.SERVER : Deeds.holder_player(Bukkit.getOfflinePlayer(deed.holder()));
        P_Economy.synchronize_bank_owner(null, office, owner);
        BookManip.standard_addr_sign(Util.location2str(chest.getLocation()), 1, office, "[Local]", null);
        if (!C_Postoffice.init_local_postoffice(office)) {
            Util.cinform(AnsiColor.RED + "[Postal] " + office + " opened, but there are no NPC slots left for its postman; restart the server.");
        }
        if (deed.price() > 0.0D && VA_postal.economy_configured) {
            // Seeded as an office purchase is (docs/economy.md): the floor to the office, the rest stays with Central.
            double seed = Reserves.office_seed(deed.price(), C_Economy.office_floor());
            if (seed > 0.0D && P_Economy.withdraw_from_central(seed)) {
                P_Economy.deposit_to_local(office, seed);
            }
        }
        Util.cinform("[Postal] Charter " + deed.id() + ": " + deed.holder_name() + " opened the " + office + " post office");
        boolean by_server = VA_postal.SERVER != null && VA_postal.SERVER.getUniqueId().equals(deed.holder());
        com.vodhanel.minecraft.va_postal.board.NoticeBoard.notice("Open " + DeedDocument.display(office), "The " + DeedDocument.display(office)
                + " Post Office opened" + (by_server ? "" : ", chartered to " + deed.holder_name()));
    }

    /** A burst of particles and a bell over the chest, and the news for everyone ({@code Charters.Effects}, {@code Announce}). */
    private static void ceremony(Deed deed, Block chest) {
        if (setting("settings.charters.effects", true)) {
            Location at = chest.getLocation().add(0.5D, 1.2D, 0.5D);
            at.getWorld().spawnParticle(Particle.FIREWORK, at, 60, 0.6D, 0.6D, 0.6D, 0.05D);
            at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 30, 0.8D, 0.5D, 0.8D, 0.0D);
            at.getWorld().playSound(at, Sound.BLOCK_BELL_USE, 2.0F, 1.0F);
        }
        if (setting("settings.charters.announce", true)) {
            boolean server_held = VA_postal.SERVER != null && VA_postal.SERVER.getUniqueId().equals(deed.holder());
            String news = "&6A new post office has opened in &f" + DeedDocument.display(deed.office()) + "&6!"
                    + (server_held ? "" : " Chartered to &f" + deed.holder_name() + "&6.");
            Bukkit.broadcast(Text.component(Text.color(news)));
        }
    }
}
