package com.vodhanel.minecraft.va_postal.inspect;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.P_MailAdmin;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.stats.OfficeStats;
import com.vodhanel.minecraft.va_postal.stats.Stats;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Inspections (docs/design/central-office.md §5): every hour Central looks over its branches, and any office with
 * something wrong (Inspection's rules) is due an inspector, at most once every {@code Settings.Inspection.Interval_days}
 * (default 7). The inspector travels with Central's postman: on his next visit he leaves a report, a sheet of paper, in
 * the office chest, and the owner is told. An office whose chest can't be found gets its report without a visit.
 * Reports only: nothing is charged or taken. The latest report of each office is kept, for /postal inspect and the
 * notice board.
 */
public final class Inspections {
    private Inspections() {
    }

    // ---- Settings ---------------------------------------------------------------------------

    public static int interval_days() {
        return Math.max(1, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.inspection.interval_days"), 7));
    }

    static boolean enabled() {
        return GetConfig.bool_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.inspection.enabled"), true);
    }

    static Inspection.Rules rules() {
        double on_time = GetConfig.double_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.inspection.min_on_time"), 0.5D);
        int stuck = GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.inspection.stuck_resets"), 10);
        return new Inspection.Rules(Stats.min_deliveries(), Double.isFinite(on_time) ? on_time : 0.5D, Math.max(0, stuck));
    }

    // ---- Stuck postmen ----------------------------------------------------------------------

    private static final Map<String, Deque<Long>> stuck = new HashMap<>();

    /** A postman of {@code office} was stuck and had to be reset (Stuck_NPC). Kept in memory, over the interval. */
    public static synchronized void stuck(String office) {
        if (office != null) {
            stuck.computeIfAbsent(key(office), k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
        }
    }

    static synchronized int stuck_count(String office, long since) {
        Deque<Long> d = stuck.get(key(office));
        if (d == null) {
            return 0;
        }
        while (!d.isEmpty() && d.peekFirst() < since) {
            d.removeFirst();
        }
        return d.size();
    }

    // ---- Looking ----------------------------------------------------------------------------

    /** What an inspector would find at {@code office} now, over the last interval. */
    static List<Inspection.Finding> look(String office, Map<String, OfficeStats.Standing> standings) {
        String k = key(office);
        boolean missing = false, full = false;
        Block chest = P_MailAdmin.office_chest(k);
        if (chest == null || !(chest.getState() instanceof Chest c)) {
            missing = true;
        } else {
            full = c.getInventory().firstEmpty() < 0;
        }
        OfficeStats.Standing s = standings.get(k);
        long since = System.currentTimeMillis() - (long) interval_days() * OfficeStats.DAY_MS;
        Inspection.Facts facts = new Inspection.Facts(VA_postal.economy_configured ? EconomyState.arrears(k) : 0.0D, missing, full,
                stuck_count(k, since), s == null ? 0 : s.delivered(), s == null ? 0.0D : s.on_time_rate(), s == null ? 0 : s.lost());
        return Inspection.findings(facts, rules(), k, P_Economy::ef);
    }

    private static Map<String, OfficeStats.Standing> standings() {
        Map<String, OfficeStats.Standing> out = new HashMap<>();
        try {
            for (OfficeStats.Standing s : Stats.standings(interval_days())) {
                out.put(key(s.office()), s);
            }
        } catch (StoreException e) {
            // no records to judge by now: the other checks still run
        }
        return out;
    }

    /** Run hourly: offices with something wrong, and no inspection within the interval, are due one. */
    public static void check() {
        if (!enabled()) {
            return;
        }
        Map<String, OfficeStats.Standing> standings = standings();
        long now = System.currentTimeMillis();
        for (String office : offices()) {
            if (!pending(office).isEmpty() || !Inspection.due(last_at(office), now, interval_days())) {
                continue;
            }
            List<Inspection.Finding> found = look(office, standings);
            if (!found.isEmpty()) {
                send(office, found);
            }
        }
    }

    /** Central sends an inspector to {@code office}: on the postman's next visit, or now if its chest can't be found. */
    static void send(String office, List<Inspection.Finding> found) {
        String k = key(office);
        if (P_MailAdmin.office_chest(k) == null) {
            file(k, found, false); // no chest, so no visit: the report goes to the owner
            return;
        }
        set_pending(k, found);
        VA_postal.plugin.saveConfig();
        Util.cinform("[Postal] Central sends an inspector to " + k + " (" + found.size() + " finding(s)); he goes with the next visit");
    }

    /**
     * Central's postman is at office {@code id} ({@code office}): the inspector with him leaves the report in its chest.
     * A full chest (one of the findings, then) keeps no copy: the report is filed and the owner told all the same.
     */
    public static void deliver(int id, String office) {
        if (office == null || VA_postal.wtr_inventory_postoffice == null || id < 0 || id >= VA_postal.wtr_inventory_postoffice.length
                || VA_postal.wtr_inventory_postoffice[id] == null) {
            return;
        }
        String k = key(office);
        List<Inspection.Finding> found = pending(k);
        if (found.isEmpty()) {
            return;
        }
        Inventory chest = VA_postal.wtr_inventory_postoffice[id];
        boolean left = chest.addItem(report(k, found, today())).isEmpty();
        file(k, found, left);
    }

    /** Records the report as the office's latest and tells the owner; {@code left}: a copy is in the office chest. */
    static void file(String office, List<Inspection.Finding> found, boolean left) {
        String k = key(office);
        List<String> stored = new ArrayList<>();
        for (Inspection.Finding f : found) {
            stored.add(f.stored());
        }
        VA_postal.plugin.getConfig().set(path(k, "report"), stored);
        VA_postal.plugin.getConfig().set(path(k, "date"), today());
        VA_postal.plugin.getConfig().set(path(k, "last_at"), Long.toString(System.currentTimeMillis()));
        set_pending(k, List.of());
        VA_postal.plugin.saveConfig();
        String name = DeedDocument.display(k);
        Util.cinform("[Postal] Central's inspector " + (left ? "left a report at " : "filed a report on ") + k + ": "
                + found.size() + " finding(s)");
        UUID owner = C_Owner.get_owner_local_po_id(k);
        Player p = owner == null ? null : Bukkit.getPlayer(owner);
        if (p != null) {
            Util.pinform(p, "&6Central's inspector " + (left ? "visited" : "reported on") + " the &f" + name + "&6 Post Office"
                    + (left ? " and left a report in its chest" : "") + ":");
            for (Inspection.Finding f : found) {
                Util.pinform(p, "&c- " + f.what() + " &7" + f.fix());
            }
            Util.pinform(p, "&8Reports only: nothing is charged. /postal inspect " + k);
        }
        com.vodhanel.minecraft.va_postal.board.NoticeBoard.refresh();
    }

    /** The report: a sheet of paper (books in office chests are mail). */
    static ItemStack report(String office, List<Inspection.Finding> found, String date) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.component(Text.color("&6Inspection report")));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(Text.component(Text.color("&8The " + DeedDocument.display(office) + " Post Office, " + date)));
        for (Inspection.Finding f : found) {
            lore.add(Text.component(Text.color("&c" + f.what())));
            lore.add(Text.component(Text.color("&7  " + f.fix())));
        }
        lore.add(Text.component(Text.color("&8Reports only: nothing is charged.")));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ---- Stored -----------------------------------------------------------------------------

    private static String key(String office) {
        return office.toLowerCase(Locale.ROOT).trim();
    }

    private static String path(String office, String what) {
        return GetConfig.path_format("postoffice.local." + key(office) + ".inspection." + what);
    }

    static List<Inspection.Finding> pending(String office) {
        List<Inspection.Finding> out = new ArrayList<>();
        for (String s : VA_postal.plugin.getConfig().getStringList(path(office, "pending"))) {
            out.add(Inspection.Finding.of_stored(s));
        }
        return out;
    }

    private static void set_pending(String office, List<Inspection.Finding> found) {
        List<String> stored = new ArrayList<>();
        for (Inspection.Finding f : found) {
            stored.add(f.stored());
        }
        VA_postal.plugin.getConfig().set(path(office, "pending"), stored.isEmpty() ? null : stored);
    }

    static long last_at(String office) {
        try {
            return Long.parseLong(VA_postal.plugin.getConfig().getString(path(office, "last_at"), "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** The office's latest report ({@code date}, findings), or null if it was never inspected. */
    public static Report latest(String office) {
        String date = VA_postal.plugin.getConfig().getString(path(office, "date"));
        if (date == null) {
            return null;
        }
        List<Inspection.Finding> found = new ArrayList<>();
        for (String s : VA_postal.plugin.getConfig().getStringList(path(office, "report"))) {
            found.add(Inspection.Finding.of_stored(s));
        }
        return new Report(key(office), date, last_at(office), found);
    }

    public record Report(String office, String date, long at, List<Inspection.Finding> findings) {
    }

    /** Every office's latest report, newest first. */
    public static List<Report> reports() {
        List<Report> out = new ArrayList<>();
        for (String office : offices()) {
            Report r = latest(office);
            if (r != null) {
                out.add(r);
            }
        }
        out.sort((a, b) -> Long.compare(b.at(), a.at()));
        return out;
    }

    private static List<String> offices() {
        List<String> out = new ArrayList<>();
        ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                if (C_Postoffice.is_local_po_name_defined(key(k))) {
                    out.add(key(k));
                }
            }
        }
        return out;
    }

    private static String today() {
        return new SimpleDateFormat("d MMM yyyy", Locale.ROOT).format(new Date());
    }

    // ---- /postal inspect --------------------------------------------------------------------

    /**
     * {@code /postal inspect}: the latest reports; {@code inspect <office>}: that office's, and for its owner or an
     * admin what an inspector would find now; {@code inspect <office> now} (admins): send one now, whatever the interval.
     */
    public static void command(CommandSender sender, String[] args) {
        boolean admin = !(sender instanceof Player) || sender.hasPermission("postal.admin");
        if (args.length < 2) {
            List<Report> all = reports();
            tell(sender, "&6[Postal] Inspection reports");
            if (all.isEmpty()) {
                tell(sender, "&8None yet: Central sends an inspector only where something is wrong.");
            }
            for (Report r : all) {
                tell(sender, "&f" + DeedDocument.display(r.office()) + " &8(" + r.date() + ")&7: " + summary(r));
            }
            tell(sender, "&8One office's report: /postal inspect <office>");
            return;
        }
        String office = C_Postoffice.town_complete(args[1]);
        if ("null".equals(office)) {
            tell(sender, "&7No post office matches '" + args[1] + "'. See /tlist.");
            return;
        }
        office = key(office);
        boolean owner = sender instanceof Player p && p.getUniqueId().equals(C_Owner.get_owner_local_po_id(office));
        if (args.length > 2 && "now".equalsIgnoreCase(args[2])) {
            if (!admin) {
                tell(sender, "&7Required permission not present.");
                return;
            }
            List<Inspection.Finding> found = look(office, standings());
            if (found.isEmpty()) {
                tell(sender, "&6Nothing wrong at the " + DeedDocument.display(office) + " Post Office: no inspector needed.");
                return;
            }
            send(office, found);
            tell(sender, pending(office).isEmpty()
                    ? "&6The " + DeedDocument.display(office) + " Post Office's chest can't be found: its report went to the owner."
                    : "&6Central's inspector goes to the " + DeedDocument.display(office) + " Post Office with the postman's next visit ("
                    + found.size() + " finding(s)).");
            return;
        }
        Report r = latest(office);
        tell(sender, "&6[Postal] The " + DeedDocument.display(office) + " Post Office");
        if (r == null) {
            tell(sender, "&8Never inspected.");
        } else {
            tell(sender, "&7Last inspected " + r.date() + ": " + summary(r));
            for (Inspection.Finding f : r.findings()) {
                tell(sender, "&c- " + f.what() + " &7" + f.fix());
            }
        }
        if (!pending(office).isEmpty()) {
            tell(sender, "&7An inspector goes there with Central's postman's next visit.");
        }
        if (owner || admin) {
            List<Inspection.Finding> now = look(office, standings());
            tell(sender, now.isEmpty() ? "&2Now: nothing wrong." : "&eNow: " + now.size() + " thing(s) an inspector would find:");
            for (Inspection.Finding f : now) {
                tell(sender, "&e- " + f.what());
            }
        }
    }

    public static String summary(Report r) {
        return r.findings().isEmpty() ? "nothing wrong" : r.findings().size() + " finding(s)";
    }

    private static void tell(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }
}
