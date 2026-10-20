package com.vodhanel.minecraft.va_postal.board;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.stats.OfficeStats;
import com.vodhanel.minecraft.va_postal.stats.Stats;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Central's notice board (docs/design/central-office.md §6): signs on a wall at Central, each showing one panel (the
 * postage, the parcel rates, a leaderboard, the latest notices). An admin registers them with {@code /postal board add};
 * they're refreshed every hour with the stats, and at once when the rates change or an office opens. Central's
 * protection covers them (CentralGuard), and they're waxed so nobody edits them.
 */
public final class NoticeBoard {
    private NoticeBoard() {
    }

    /** What a sign shows. */
    public enum Panel {
        POSTAGE, PARCELS, BUSIEST, ON_TIME, FEWEST_LOST, NOTICES, AWARD, INSPECTIONS;

        static Panel parse(String s) {
            for (Panel p : values()) {
                if (p.name().equalsIgnoreCase(s.replace('-', '_'))) {
                    return p;
                }
            }
            return null;
        }
    }

    /** A sign on the board. */
    record Entry(Panel panel, String location) {
    }

    /** A sign line fits 15 characters. */
    static final int WIDTH = 15;
    /** Notices kept: the sign shows the latest three, a right-click all of them. */
    private static final int MAX_NOTICES = 10;

    // ---- Registry ---------------------------------------------------------------------------

    private static String path(String key) {
        return GetConfig.path_format("board." + key);
    }

    static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (String s : VA_postal.plugin.getConfig().getStringList(path("signs"))) {
            int at = s.indexOf(';');
            Panel p = at < 0 ? null : Panel.parse(s.substring(0, at));
            if (p != null) {
                out.add(new Entry(p, s.substring(at + 1)));
            }
        }
        return out;
    }

    private static void save(List<Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            out.add(e.panel().name() + ";" + e.location());
        }
        VA_postal.plugin.getConfig().set(path("signs"), out.isEmpty() ? null : out);
        VA_postal.plugin.saveConfig();
    }

    /** True if {@code b} is one of the board's signs. */
    public static boolean is_board(Block b) {
        return panel_at(b) != null;
    }

    /** The panel the board's sign {@code b} shows, or null if it isn't on the board. */
    static Panel panel_at(Block b) {
        if (b == null || !Tag.ALL_SIGNS.isTagged(b.getType())) {
            return null;
        }
        String key = key(b);
        for (Entry e : entries()) {
            if (e.location().equals(key)) {
                return e.panel();
            }
        }
        return null;
    }

    private static String key(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private static Block block(String key) {
        String[] p = key.split(",");
        if (p.length != 4) {
            return null;
        }
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(p[0]);
        try {
            return w == null ? null : w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** How far from Central's chest the board may be ({@code Settings.Board.Max_distance}, default 32). */
    static int max_distance() {
        return Math.max(1, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.board.max_distance"), 32));
    }

    /** Why {@code b} can't be on the board, or null. */
    static String place_problem(Block b) {
        if (b == null || !Tag.ALL_SIGNS.isTagged(b.getType())) {
            return "That isn't a sign.";
        }
        String central = C_Postoffice.get_central_po_location();
        Location c = central == null || "null".equals(central) ? null : Util.str2location(central);
        if (c == null) {
            return "Central isn't set up (/setcentral).";
        }
        if (c.getWorld() != b.getWorld() || c.distance(b.getLocation()) > max_distance()) {
            return "The notice board goes at Central: within " + max_distance() + " blocks of its chest.";
        }
        Block chest = com.vodhanel.minecraft.va_postal.clerk.Clerks.office_chest(com.vodhanel.minecraft.va_postal.clerk.Clerks.CENTRAL);
        if (chest != null && b.equals(com.vodhanel.minecraft.va_postal.mail.ChestManip.chest2sign_block(chest))) {
            return "That's Central's own sign.";
        }
        return null;
    }

    // ---- Notices ----------------------------------------------------------------------------

    /**
     * Posts a notice and refreshes the board: {@code short_text} for the sign (a line holds the date and about 9
     * characters more), {@code full_text} for chat when the sign is right-clicked.
     */
    public static void notice(String short_text, String full_text) {
        List<String> notices = new ArrayList<>(VA_postal.plugin.getConfig().getStringList(path("notices")));
        String date = new SimpleDateFormat("d/M", Locale.ROOT).format(new Date());
        notices.add(0, date + "|" + short_text.replace("|", "/") + "|" + full_text.replace("|", "/"));
        while (notices.size() > MAX_NOTICES) {
            notices.remove(notices.size() - 1);
        }
        VA_postal.plugin.getConfig().set(path("notices"), notices);
        VA_postal.plugin.saveConfig();
        refresh();
    }

    /** A stored notice as the sign shows it: the date and its short text. */
    static String short_notice(String stored) {
        String[] p = stored.split("\\|", 3);
        return p.length == 3 ? p[0] + " " + p[1] : stored;
    }

    /** A stored notice as chat shows it: the date and its full text. */
    static String full_notice(String stored) {
        String[] p = stored.split("\\|", 3);
        return p.length == 3 ? p[0] + " " + p[2] : stored;
    }

    // ---- Drawing ----------------------------------------------------------------------------

    /** Redraws every sign on the board; signs that are gone are dropped from it. Returns the signs drawn. */
    public static int refresh() {
        List<Entry> entries = entries();
        if (entries.isEmpty()) {
            return 0;
        }
        List<OfficeStats.Standing> standings = null;
        List<Entry> kept = new ArrayList<>();
        int drawn = 0;
        for (Entry e : entries) {
            Block b = block(e.location());
            if (b != null && !b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) {
                kept.add(e); // drawn when its chunk is loaded at the next refresh
                continue;
            }
            if (b == null || !(b.getState() instanceof Sign sign)) {
                Util.cinform("[Postal] The notice board's " + e.panel() + " sign at " + e.location() + " is gone; dropped it.");
                continue;
            }
            kept.add(e);
            if (standings == null && (e.panel() == Panel.BUSIEST || e.panel() == Panel.ON_TIME || e.panel() == Panel.FEWEST_LOST)) {
                try {
                    standings = Stats.standings(Stats.period_days());
                } catch (StoreException ex) {
                    standings = List.of();
                }
            }
            String[] lines = lines(e.panel(), standings);
            for (int i = 0; i < 4; i++) {
                Text.line(sign, i, Text.color(lines[i]));
            }
            sign.setWaxed(true);
            sign.update(true, false);
            drawn++;
        }
        if (kept.size() != entries.size()) {
            save(kept);
        }
        return drawn;
    }

    /** The four lines of {@code panel}. */
    static String[] lines(Panel panel, List<OfficeStats.Standing> standings) {
        return switch (panel) {
            case POSTAGE -> VA_postal.economy_configured
                    ? new String[]{"&1&lPOSTAGE", rate("Local", C_Economy.postage_price(true)),
                    rate("Office", C_Economy.postage_price(false)), rate("Server", C_Economy.network_postage_price())}
                    : new String[]{"&1&lPOSTAGE", "", "&2Free", ""};
            case PARCELS -> VA_postal.economy_configured
                    ? new String[]{"&1&lPARCELS", rate("Local", C_Economy.ship_price(true)),
                    rate("Office", C_Economy.ship_price(false)), rate("COD +", C_Economy.cod_surchg())}
                    : new String[]{"&1&lPARCELS", "", "&2Free", ""};
            case BUSIEST -> board("&1&lBUSIEST", OfficeStats.busiest(standings, 3), s -> Integer.toString(s.delivered()));
            case ON_TIME -> board("&1&lON TIME", OfficeStats.most_punctual(standings, Stats.min_deliveries(), 3),
                    s -> Math.round(s.on_time_rate() * 100.0D) + "%");
            case FEWEST_LOST -> board("&1&lFEWEST LOST", OfficeStats.fewest_losses(standings, Stats.min_deliveries(), 3),
                    s -> Integer.toString(s.lost()));
            case AWARD -> {
                String[] h = com.vodhanel.minecraft.va_postal.stats.Awards.holder();
                yield h == null ? new String[]{"&6&lBRANCH OF", "&6&lTHE MONTH", "", "&8none yet"}
                        : new String[]{"&6&lBRANCH OF", "&6&lTHE MONTH", fit(DeedDocument.display(h[0])), fit(h[1])};
            }
            case INSPECTIONS -> {
                List<com.vodhanel.minecraft.va_postal.inspect.Inspections.Report> r = com.vodhanel.minecraft.va_postal.inspect.Inspections.reports();
                String[] out = {"&1&lINSPECTIONS", "", "", ""};
                for (int i = 0; i < 3; i++) {
                    if (i < r.size()) {
                        String n = Integer.toString(r.get(i).findings().size());
                        String name = DeedDocument.display(r.get(i).office());
                        name = name.length() > WIDTH - 1 - n.length() ? name.substring(0, WIDTH - 1 - n.length()) : name;
                        out[i + 1] = name + " ".repeat(Math.max(1, WIDTH - name.length() - n.length())) + n;
                    } else if (i == 0) {
                        out[2] = "&8none yet";
                    }
                }
                yield out;
            }
            case NOTICES -> {
                List<String> n = VA_postal.plugin.getConfig().getStringList(path("notices"));
                String[] out = {"&1&lNOTICES", "", "", ""};
                for (int i = 0; i < 3; i++) {
                    out[i + 1] = i < n.size() ? fit(short_notice(n.get(i))) : (i == 0 && n.isEmpty() ? "&8none yet" : "");
                }
                yield out;
            }
        };
    }

    private static String rate(String label, double price) {
        String p = P_Economy.ef(price);
        int pad = Math.max(1, WIDTH - label.length() - p.length());
        return fit(label + " ".repeat(pad) + p);
    }

    private static String[] board(String title, List<OfficeStats.Standing> ranked, java.util.function.Function<OfficeStats.Standing, String> value) {
        String[] out = {title, "", "", ""};
        if (ranked.isEmpty()) {
            out[2] = "&8none yet";
            return out;
        }
        for (int i = 0; i < ranked.size() && i < 3; i++) {
            OfficeStats.Standing s = ranked.get(i);
            String v = value.apply(s);
            String name = DeedDocument.display(s.office());
            int room = WIDTH - 3 - v.length(); // "1 " and a space before the value
            if (name.length() > room) {
                name = name.substring(0, Math.max(1, room));
            }
            out[i + 1] = (i + 1) + " " + name + " ".repeat(Math.max(1, WIDTH - 2 - name.length() - v.length())) + v;
        }
        return out;
    }

    /** {@code s} cut to a sign line. */
    static String fit(String s) {
        return s.length() <= WIDTH ? s : s.substring(0, WIDTH);
    }

    // ---- In full -----------------------------------------------------------------------------

    /** {@code panel} in full, in {@code sender}'s chat: what a sign has no room for. */
    static void details(CommandSender sender, Panel panel) {
        switch (panel) {
            case POSTAGE, PARCELS -> {
                send(sender, "&6[Postal] Postage rates");
                if (!VA_postal.economy_configured) {
                    send(sender, "&7Postage is free on this server.");
                    return;
                }
                for (String line : com.vodhanel.minecraft.va_postal.economy.Rates.table()) {
                    send(sender, line);
                }
                send(sender, "&7A new address: &ffrom " + P_Economy.ef(C_Economy.addr_purchase_price()) + " &8(offices set their own)");
                send(sender, "&8Offices may take up to " + com.vodhanel.minecraft.va_postal.economy.Rates.discount_text_max()
                        + " off the postage of mail they send: /postal office <office> discount");
            }
            case BUSIEST, ON_TIME, FEWEST_LOST -> {
                int days = Stats.period_days();
                List<OfficeStats.Standing> standings;
                try {
                    standings = Stats.standings(days);
                } catch (StoreException e) {
                    send(sender, "&7The records can't be read now.");
                    return;
                }
                send(sender, "&6[Postal] Leaderboards &7(last " + days + " days)");
                for (String line : Stats.boards(standings, 10)) {
                    send(sender, line);
                }
                send(sender, "&8One office's numbers: /postal stats <office>");
            }
            case AWARD -> com.vodhanel.minecraft.va_postal.stats.Awards.command(sender, new String[]{"award"});
            case INSPECTIONS -> com.vodhanel.minecraft.va_postal.inspect.Inspections.command(sender, new String[]{"inspect"});
            case NOTICES -> {
                List<String> n = VA_postal.plugin.getConfig().getStringList(path("notices"));
                send(sender, "&6[Postal] Notices");
                if (n.isEmpty()) {
                    send(sender, "&8None yet.");
                }
                for (String line : n) {
                    send(sender, "&7" + full_notice(line));
                }
            }
        }
    }

    // ---- /postal board ----------------------------------------------------------------------

    /** {@code /postal board add [panel] [x y z [world]] | remove [x y z [world]] | list | refresh} (admins). */
    public static void command(CommandSender sender, String[] args) {
        if (sender instanceof Player && !sender.hasPermission("postal.admin")) {
            send(sender, "&7Required permission not present.");
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "refresh" -> send(sender, "&6Notice board refreshed: " + refresh() + " sign(s).");
            case "list" -> {
                List<Entry> entries = entries();
                send(sender, "&6[Postal] Notice board: " + entries.size() + " sign(s)");
                for (Entry e : entries) {
                    send(sender, "&7" + e.panel().name().toLowerCase(Locale.ROOT) + " &8at &f" + e.location());
                }
            }
            default -> send(sender, "&7/postal board add [" + panels() + "] [x y z [world]] | remove [x y z [world]] | list | refresh");
        }
    }

    private static String panels() {
        List<String> out = new ArrayList<>();
        for (Panel p : Panel.values()) {
            out.add(p.name().toLowerCase(Locale.ROOT));
        }
        return String.join("|", out);
    }

    /** The sign meant: the coordinates given from index {@code i}, or the one the player looks at. */
    private static Block target(CommandSender sender, String[] args, int i) {
        if (args.length >= i + 3) {
            try {
                org.bukkit.World w = args.length > i + 3 ? org.bukkit.Bukkit.getWorld(args[i + 3])
                        : sender instanceof Player p ? p.getWorld() : org.bukkit.Bukkit.getWorlds().get(0);
                return w == null ? null : w.getBlockAt(Integer.parseInt(args[i]), Integer.parseInt(args[i + 1]), Integer.parseInt(args[i + 2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return sender instanceof Player p ? p.getTargetBlockExact(6) : null;
    }

    private static void add(CommandSender sender, String[] args) {
        List<Entry> entries = entries();
        int i = 2;
        Panel panel = null;
        if (args.length > 2 && !args[2].matches("-?\\d+")) {
            panel = Panel.parse(args[2]);
            if (panel == null) {
                send(sender, "&7Panels: " + panels());
                return;
            }
            i = 3;
        }
        if (panel == null) { // the next panel the board doesn't show yet
            for (Panel p : Panel.values()) {
                if (entries.stream().noneMatch(e -> e.panel() == p)) {
                    panel = p;
                    break;
                }
            }
            if (panel == null) {
                panel = Panel.NOTICES;
            }
        }
        Block b = target(sender, args, i);
        if (b == null) {
            send(sender, "&7Look at a sign, or give its coordinates.");
            return;
        }
        String problem = place_problem(b);
        if (problem != null) {
            send(sender, "&7" + problem);
            return;
        }
        String key = key(b);
        entries.removeIf(e -> e.location().equals(key));
        entries.add(new Entry(panel, key));
        save(entries);
        refresh();
        send(sender, "&6The sign at " + key + " now shows " + panel.name().toLowerCase(Locale.ROOT) + ".");
    }

    private static void remove(CommandSender sender, String[] args) {
        Block b = target(sender, args, 2);
        if (b == null) {
            send(sender, "&7Look at a sign, or give its coordinates.");
            return;
        }
        List<Entry> entries = entries();
        String key = key(b);
        if (!entries.removeIf(e -> e.location().equals(key))) {
            send(sender, "&7That sign isn't on the notice board.");
            return;
        }
        save(entries);
        if (b.getState() instanceof Sign sign) {
            sign.setWaxed(false);
            sign.update(true, false);
        }
        send(sender, "&6Took the sign at " + key + " off the notice board.");
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }
}
