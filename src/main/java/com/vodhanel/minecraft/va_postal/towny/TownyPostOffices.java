package com.vodhanel.minecraft.va_postal.towny;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.clerk.Clerks;
import com.vodhanel.minecraft.va_postal.common.P_Towny;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.deed.Notices;
import com.vodhanel.minecraft.va_postal.deed.OfficeSales;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Post offices on Towny post office plots (docs/design/towny.md): a town office stands on a post office plot and
 * belongs to the plot's owner (the mayor while the town owns it). Everything here is safe to call without Towny: it
 * does nothing unless Towny is in use.
 */
public final class TownyPostOffices {
    private TownyPostOffices() {
    }

    // P_Towny names Towny's classes, so it may only be loaded where Towny is installed: ask first.
    private static boolean towny() {
        return VA_postal.towny_configured && VA_postal.towny_opt_in;
    }

    /** The town plot at {@code location}, or null (outside towns, or no Towny). */
    public static PostOfficePlots.Plot plot(Location location) {
        return towny() && location != null ? P_Towny.plot_at(location) : null;
    }

    /** The plot of {@code office}'s chest if it's a post office plot, else null. */
    public static PostOfficePlots.Plot office_plot(String office) {
        if (!towny()) {
            return null;
        }
        Block chest = Clerks.office_chest(office);
        PostOfficePlots.Plot p = chest == null ? null : P_Towny.plot_at(chest.getLocation());
        return p != null && p.postoffice() ? p : null;
    }

    /** The local offices on this server, by stored name. */
    private static List<String> offices() {
        List<String> out = new ArrayList<>();
        ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                out.add(key.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** The offices whose chests stand on the plot named {@code key}. */
    public static List<String> offices_on(String key) {
        List<String> out = new ArrayList<>();
        if (!towny() || key == null) {
            return out;
        }
        for (String office : offices()) {
            PostOfficePlots.Plot p = office_plot(office);
            if (p != null && key.equals(p.key())) {
                out.add(office);
            }
        }
        return out;
    }

    /** Why {@code holder} can't open an office with {@code chest}, or null. */
    public static String charter_problem(UUID holder, Block chest) {
        PostOfficePlots.Plot p = plot(chest.getLocation());
        return PostOfficePlots.charter_problem(p, holder, p != null && !offices_on(p.key()).isEmpty());
    }

    /** Why {@code barrel} can't be {@code office}'s box cabinet, or null. */
    public static String cabinet_problem(String office, Block barrel) {
        PostOfficePlots.Plot at = office_plot(office);
        return at == null ? null : PostOfficePlots.cabinet_problem(at, plot(barrel.getLocation()));
    }

    /** Why {@code office} can't be sold through Postal (it's a town office, sold with its plot), or null. */
    public static String sale_problem(String office) {
        return office_plot(office) == null ? null : PostOfficePlots.sale_problem(DeedDocument.display(office));
    }

    /**
     * Makes {@code office}'s owner its plot's owner, if it stands on a post office plot. The account and its arrears
     * stay with the office; the sign is put up again, and the old and new owners are told.
     */
    public static void reconcile(String office) {
        PostOfficePlots.Plot p = office_plot(office);
        if (p == null) {
            return;
        }
        UUID now = C_Owner.get_owner_local_po_id(office);
        UUID owner = PostOfficePlots.office_owner(p);
        if (Objects.equals(now, owner)) {
            return;
        }
        if (OfficeSales.open_offer(office) != null) {
            OfficeSales.withdraw(office);
        }
        String name = DeedDocument.display(office);
        if (owner == null) {
            C_Owner.del_owner_local_po(office);
        } else {
            C_Owner.set_owner_local_po(office, Util.UUID2Player(owner));
        }
        OfficeSales.resign(office);
        String who = owner == null ? "the server" : Bukkit.getOfflinePlayer(owner).getName();
        Util.cinform("[Postal] " + office + " follows its post office plot in " + p.town() + ": now owned by " + who);
        if (now != null) {
            tell(now, "The " + name + " Post Office has passed with its plot to " + who + ".");
        }
        if (owner != null) {
            tell(owner, "The " + name + " Post Office in " + p.town() + " is yours now, with its plot."
                    + (p.owner() == null ? " (The town owns the plot, so its mayor runs the office.)" : ""));
        }
    }

    /** {@link #reconcile} for every office on this server. */
    public static void reconcile_all() {
        if (!towny()) {
            return;
        }
        for (String office : offices()) {
            reconcile(office);
        }
    }

    /** {@link #reconcile} for the offices on plot {@code key}. */
    public static void reconcile_plot(String key) {
        for (String office : offices_on(key)) {
            reconcile(office);
        }
    }

    private static void tell(UUID player, String message) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            Util.pinform(online, "&7&o" + message);
        } else {
            Notices.add(player, message);
        }
    }
}
