package com.vodhanel.minecraft.va_postal.economy;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Postage rates as Central's policy (docs/design/central-office.md §2): the base rates are set with
 * {@code /postal bank policy rates.<rate> <amount>}; each office may give a discount on the postage of mail it sends,
 * up to {@code economy.rates.max_discount} percent, out of its own share (Postage). When a base rate changes, every
 * office is owed a notice, which Central's postman leaves in its chest on his next visit.
 */
public final class Rates {
    private Rates() {
    }

    // ---- Discounts --------------------------------------------------------------------------

    /** The most an office may take off its postage, in percent ({@code economy.rates.max_discount}, default 25). */
    public static double max_discount() {
        double v = GetConfig.double_value(VA_postal.plugin.getConfig(), GetConfig.path_format("economy.rates.max_discount"), 25.0D);
        return Double.isFinite(v) ? Math.max(0.0D, Math.min(100.0D, v)) : 25.0D;
    }

    /** The discount {@code office} gives, in percent, as set but never more than the server allows now. */
    public static double discount(String office) {
        Double own = own_discount(office);
        return own == null ? 0.0D : Math.min(own, max_discount());
    }

    /** The discount as the owner set it, or null. */
    public static Double own_discount(String office) {
        if (office == null) {
            return null;
        }
        String s = VA_postal.plugin.getConfig().getString(GetConfig.path_format("postoffice.local." + office.toLowerCase(Locale.ROOT).trim() + ".discount"));
        return parse_percent(s);
    }

    /** A percentage as typed ("10" or "10%"): finite, 0 to 100, or null. */
    public static Double parse_percent(String s) {
        if (s == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(s.trim().replace("%", ""));
            return Double.isFinite(v) && v >= 0.0D && v <= 100.0D ? Math.round(v * 100.0D) / 100.0D : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sets (or, with null or 0, clears) the office's discount. */
    public static void set_discount(String office, Double percent) {
        VA_postal.plugin.getConfig().set(GetConfig.path_format("postoffice.local." + office + ".discount"),
                percent == null || percent <= 0.0D ? null : trim(percent));
        VA_postal.plugin.saveConfig();
    }

    /** "10%", or "none". */
    public static String discount_text(String office) {
        double d = discount(office);
        return d <= 0.0D ? "none" : trim(d) + "%";
    }

    /** "25%": the most an office may take off. */
    public static String discount_text_max() {
        return trim(max_discount()) + "%";
    }

    // ---- The base rates ---------------------------------------------------------------------

    /** The rate settings of {@code /postal bank policy}: key, config path, and what the notice calls it. */
    public static final String[][] RATES = {
            {"rates.letter_local", "economy.postage.letter.local", "A letter, within an office"},
            {"rates.letter_out_town", "economy.postage.letter.out_town", "A letter, to another office"},
            {"rates.letter_network", "economy.postage.letter.network", "A letter, to another server"},
            {"rates.parcel_local", "economy.postage.shipment.local", "A parcel, within an office"},
            {"rates.parcel_out_town", "economy.postage.shipment.out_town", "A parcel, to another office"},
            {"rates.cod_surcharge", "economy.postage.shipment.cod_surchg", "Cash on delivery"},
    };

    /** The notice's name for the rate setting {@code key}, or null if it isn't one. */
    public static String label(String key) {
        for (String[] r : RATES) {
            if (r[0].equals(key)) {
                return r[2];
            }
        }
        return null;
    }

    /** The current rates, as the notice and Central's counter show them. */
    public static List<String> table() {
        List<String> out = new ArrayList<>();
        out.add("&7A letter, within an office: &f" + P_Economy.ef(C_Economy.postage_price(true)));
        out.add("&7A letter, to another office: &f" + P_Economy.ef(C_Economy.postage_price(false)));
        out.add("&7A letter, to another server: &f" + P_Economy.ef(C_Economy.network_postage_price()));
        out.add("&7A parcel, within an office: &f" + P_Economy.ef(C_Economy.ship_price(true)));
        out.add("&7A parcel, to another office: &f" + P_Economy.ef(C_Economy.ship_price(false)));
        out.add("&7Cash on delivery: &f+" + P_Economy.ef(C_Economy.cod_surchg()));
        return out;
    }

    // ---- Notices ----------------------------------------------------------------------------

    /** A base rate changed: every office here is owed a notice of it. */
    public static void changed(String key, String from, String to) {
        String label = label(key);
        if (label == null) {
            return;
        }
        String line = "&7" + label + ": &8" + money(from) + " &7-> &f" + money(to);
        for (String office : offices()) {
            List<String> pending = pending(office);
            pending.add(line);
            set_pending(office, pending);
        }
        VA_postal.plugin.saveConfig();
        com.vodhanel.minecraft.va_postal.board.NoticeBoard.notice("New rates", label + ": was " + money(from) + ", now " + money(to));
    }

    /** The changes {@code office} hasn't had a notice of yet. */
    public static List<String> pending(String office) {
        return new ArrayList<>(VA_postal.plugin.getConfig().getStringList(path(office)));
    }

    private static void set_pending(String office, List<String> lines) {
        VA_postal.plugin.getConfig().set(path(office), lines.isEmpty() ? null : lines);
    }

    private static String path(String office) {
        return GetConfig.path_format("postoffice.local." + office + ".rate_notice");
    }

    /**
     * Central's postman is at office {@code id} ({@code office}): leaves the notice it's owed, if any, in its chest.
     * If the chest is full the notice waits for the next visit.
     */
    public static void deliver(int id, String office) {
        if (office == null || VA_postal.wtr_inventory_postoffice == null || id < 0 || id >= VA_postal.wtr_inventory_postoffice.length
                || VA_postal.wtr_inventory_postoffice[id] == null) {
            return;
        }
        String key = office.toLowerCase(Locale.ROOT).trim();
        List<String> changes = pending(key);
        if (changes.isEmpty()) {
            return;
        }
        if (!VA_postal.wtr_inventory_postoffice[id].addItem(notice(key, changes)).isEmpty()) {
            Util.dinform("[Postal] No room for the rate notice at " + key + "; it waits for the next visit");
            return;
        }
        set_pending(key, new ArrayList<>());
        VA_postal.plugin.saveConfig();
        Util.cinform("[Postal] Central's postman left a rate notice at " + key);
        UUID owner = C_Owner.get_owner_local_po_id(key);
        Player p = owner == null ? null : Bukkit.getPlayer(owner);
        if (p != null) {
            Util.pinform(p, "&6Central's postman left a notice of new postage rates at the &f"
                    + DeedDocument.display(key) + "&6 Post Office.");
        }
    }

    /** The notice: a sheet of paper with the changes, the rates now and the office's discount. */
    public static ItemStack notice(String office, List<String> changes) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("&6Notice of postage rates"));
        List<String> lore = new ArrayList<>();
        lore.add("&8To the " + DeedDocument.display(office) + " Post Office, "
                + new SimpleDateFormat("d MMM yyyy", Locale.ROOT).format(new Date()));
        lore.add("&eThe Postmaster General has changed:");
        lore.addAll(changes);
        lore.add("&eRates now:");
        lore.addAll(table());
        double d = discount(office);
        lore.add("&7This office's discount: &f" + (d <= 0.0D ? "none" : trim(d) + "%")
                + " &8(up to " + trim(max_discount()) + "%)");
        List<net.kyori.adventure.text.Component> components = new ArrayList<>();
        for (String l : lore) {
            components.add(plain(l));
        }
        meta.lore(components);
        item.setItemMeta(meta);
        return item;
    }

    private static net.kyori.adventure.text.Component plain(String legacy) {
        return Text.component(Text.color(legacy)).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    private static List<String> offices() {
        List<String> out = new ArrayList<>();
        ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                out.add(key);
            }
        }
        return out;
    }

    private static String money(String amount) {
        try {
            return P_Economy.ef(Double.parseDouble(amount));
        } catch (Exception e) {
            return amount == null ? "unset" : amount;
        }
    }

    static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }
}
