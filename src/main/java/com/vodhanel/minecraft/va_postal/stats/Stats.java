package com.vodhanel.minecraft.va_postal.stats;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.config.C_Address;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Postoffice;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.store.MailFact;
import com.vodhanel.minecraft.va_postal.store.MailStore;
import com.vodhanel.minecraft.va_postal.store.MailStores;
import com.vodhanel.minecraft.va_postal.store.OfficeDay;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import com.vodhanel.minecraft.va_postal.store.StoreQueue;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Office stats (docs/design/central-office.md §7): every hour the mail store's history is summarised into one row per
 * office per day ({@link OfficeStats}), and the leaderboards read those rows over the last
 * {@code Settings.Stats.Period_days}. {@code /postal stats} shows them, as does Central's counter.
 */
public final class Stats {
    private Stats() {
    }

    // ---- Settings ---------------------------------------------------------------------------

    /** The days the leaderboards cover ({@code Settings.Stats.Period_days}, default 30). */
    public static int period_days() {
        return Math.max(1, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.stats.period_days"), 30));
    }

    /** Deliveries an office needs in the period to rank for punctuality and losses ({@code Min_deliveries}, 5). */
    public static int min_deliveries() {
        return Math.max(1, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.stats.min_deliveries"), 5));
    }

    private static double on_time_factor() {
        return GetConfig.double_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.stats.on_time_factor"), 1.5D);
    }

    private static int on_time_grace() {
        return GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.stats.on_time_grace"), 300);
    }

    // ---- Roll-up ----------------------------------------------------------------------------

    /** The day the roll-up starts from on a store with no rows yet: up to a period back. */
    private static int first_day(int today) {
        return today - period_days() + 1;
    }

    /**
     * Summarises the history since the last day with rows (that day again, as it may not have been complete) up to
     * today, on the store's thread; {@code done} hears how many rows were written, on the main thread.
     */
    public static void roll_up(Consumer<Integer> done) {
        MailStore store = MailStores.active();
        if (store == null) {
            return;
        }
        // Read the config here, on the main thread; the store's work is done on its own.
        Map<String, Long> rounds = office_rounds();
        long central_round = (long) Math.max(0, GetConfig.central_cool_sec()) * Math.max(1, rounds.size());
        double factor = on_time_factor();
        long grace = on_time_grace();
        String server = store.server_id();
        long now = System.currentTimeMillis();
        int today = OfficeStats.day(now);
        int backfill = first_day(today);
        StoreQueue.later("Stats roll-up", () -> {
            int last = store.last_stats_day();
            int from = last < 0 ? backfill : Math.max(backfill, last);
            List<MailFact> facts = store.mail_facts(from * OfficeStats.DAY_MS, now + 1L);
            List<OfficeDay> rows = new ArrayList<>();
            for (int day = from; day <= today; day++) {
                rows.addAll(OfficeStats.summarize(server, day, facts, f -> {
                    String dest = f.dest_office() == null ? "" : f.dest_office().toLowerCase(Locale.ROOT).trim();
                    boolean local = server.equals(f.origin_server()) && dest.equalsIgnoreCase(f.origin_office() == null ? "" : f.origin_office().trim());
                    return OfficeStats.expected_ms(local, rounds.getOrDefault(dest, 0L), central_round, factor, grace);
                }));
            }
            store.put_office_stats(rows);
            if (done != null) {
                int n = rows.size();
                Bukkit.getScheduler().runTask(VA_postal.plugin, () -> done.accept(n));
            }
        });
    }

    /** Each office's round in seconds: its postman serving every address once, cool-downs included. */
    private static Map<String, Long> office_rounds() {
        Map<String, Long> out = new HashMap<>();
        long cool = Math.max(0, GetConfig.postman_cool_sec());
        ConfigurationSection cs = VA_postal.plugin.getConfig().getConfigurationSection(GetConfig.path_format("postoffice.local"));
        if (cs == null) {
            return out;
        }
        for (String key : cs.getKeys(false)) {
            String office = key.toLowerCase(Locale.ROOT).trim();
            long round = 0L;
            String[] addresses = C_Arrays.addresses_list(office);
            if (addresses != null) {
                for (String a : addresses) {
                    try {
                        round += Math.max(0L, Long.parseLong(C_Address.get_addr_interval(office, a.trim()))) + cool;
                    } catch (NumberFormatException e) {
                        round += cool;
                    }
                }
            }
            out.put(office, round);
        }
        return out;
    }

    // ---- Reading ----------------------------------------------------------------------------

    /** Each office's totals over the last {@code days} days (today included), busiest first. */
    public static List<OfficeStats.Standing> standings(int days) {
        MailStore store = MailStores.active();
        if (store == null) {
            return List.of();
        }
        int today = OfficeStats.day(System.currentTimeMillis());
        return OfficeStats.standings(store.office_stats(today - Math.max(1, days) + 1, today));
    }

    /** The leaderboards as lines: busiest, most punctual and fewest losses, {@code n} each. */
    public static List<String> boards(List<OfficeStats.Standing> all, int n) {
        List<String> out = new ArrayList<>();
        int min = min_deliveries();
        out.add("&eBusiest:");
        add(out, OfficeStats.busiest(all, n), s -> s.delivered() + " delivered");
        out.add("&eMost punctual &8(" + min + "+ deliveries)&e:");
        add(out, OfficeStats.most_punctual(all, min, n), s -> percent(s.on_time_rate()) + " on time");
        out.add("&eFewest losses &8(" + min + "+ deliveries)&e:");
        add(out, OfficeStats.fewest_losses(all, min, n), s -> s.lost() + " lost of " + (s.delivered() + s.lost()));
        return out;
    }

    private static void add(List<String> out, List<OfficeStats.Standing> ranked, java.util.function.Function<OfficeStats.Standing, String> what) {
        if (ranked.isEmpty()) {
            out.add("  &8none yet");
            return;
        }
        int i = 1;
        for (OfficeStats.Standing s : ranked) {
            out.add("  &7" + i++ + ". &f" + DeedDocument.display(s.office()) + " &7" + what.apply(s));
        }
    }

    /** One office's totals as lines. */
    public static List<String> describe(OfficeStats.Standing s, int days) {
        List<String> out = new ArrayList<>();
        out.add("&6" + DeedDocument.display(s.office()) + " Post Office &7(last " + days + " days)");
        out.add("&7Picked up: &f" + s.sent() + "  &7Delivered: &f" + s.delivered() + "  &7Lost: &f" + s.lost());
        out.add("&7On time: &f" + (s.delivered() == 0 ? "-" : percent(s.on_time_rate()))
                + "  &7Average time: &f" + (s.delivered() == 0 ? "-" : duration(s.average_ms())));
        return out;
    }

    static String percent(double rate) {
        return Math.round(rate * 100.0D) + "%";
    }

    /** "45s", "12m 5s", "3h 20m". */
    static String duration(long ms) {
        long s = Math.max(0L, ms / 1000L);
        if (s < 60L) {
            return s + "s";
        }
        if (s < 3600L) {
            return (s / 60L) + "m " + (s % 60L) + "s";
        }
        return (s / 3600L) + "h " + ((s % 3600L) / 60L) + "m";
    }

    // ---- /postal stats ----------------------------------------------------------------------

    /** {@code /postal stats [office] [days]} for anyone; {@code /postal stats refresh} for admins. */
    public static void command(CommandSender sender, String[] args) {
        if (MailStores.active() == null) {
            send(sender, "&7Stats need the mail store, which isn't open.");
            return;
        }
        if (args.length > 1 && "refresh".equalsIgnoreCase(args[1])) {
            if (sender instanceof Player && !sender.hasPermission("postal.admin")) {
                send(sender, "&7Required permission not present.");
                return;
            }
            send(sender, "&7Summarising the mail store's history...");
            roll_up(n -> send(sender, "&6Stats updated: " + n + " office-day(s)."));
            return;
        }
        int days = period_days();
        String office = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].matches("\\d+")) {
                days = Math.max(1, Math.min(3650, Integer.parseInt(args[i])));
            } else {
                office = C_Postoffice.town_complete(args[i]);
                if ("null".equals(office)) {
                    send(sender, "&7No post office matches '" + args[i] + "'. See /tlist.");
                    return;
                }
            }
        }
        List<OfficeStats.Standing> all;
        try {
            all = standings(days);
        } catch (StoreException e) {
            send(sender, "&7The mail store can't be read now.");
            return;
        }
        if (office != null) {
            String key = office.toLowerCase(Locale.ROOT).trim();
            OfficeStats.Standing s = all.stream().filter(x -> x.office().equals(key)).findFirst()
                    .orElse(new OfficeStats.Standing(key, 0, 0, 0, 0, 0L));
            for (String line : describe(s, days)) {
                send(sender, line);
            }
            return;
        }
        send(sender, "&6[Postal] Leaderboards &7(last " + days + " days; updated hourly)");
        for (String line : boards(all, 5)) {
            send(sender, line);
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(Text.color(message));
    }
}
