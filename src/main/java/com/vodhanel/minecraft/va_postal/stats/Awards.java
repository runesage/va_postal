package com.vodhanel.minecraft.va_postal.stats;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.P_Bank;
import com.vodhanel.minecraft.va_postal.common.P_Economy;
import com.vodhanel.minecraft.va_postal.common.Text;
import com.vodhanel.minecraft.va_postal.common.Util;
import com.vodhanel.minecraft.va_postal.config.C_Owner;
import com.vodhanel.minecraft.va_postal.config.GetConfig;
import com.vodhanel.minecraft.va_postal.deed.DeedDocument;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.economy.PostalEconomy;
import com.vodhanel.minecraft.va_postal.store.StoreException;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Branch of the Month (docs/design/central-office.md §6): every {@code Settings.Awards.Period_days} (default 30) the
 * office with the best score over the period is named, on the notice board and to the server, and Central may pay it
 * a bonus ({@code Settings.Awards.Bonus}, default 0) from its money above its target, the same money dividends come
 * from; with too little there, the bonus is skipped.
 */
public final class Awards {
    private Awards() {
    }

    // ---- Scoring (pure) ---------------------------------------------------------------------

    /**
     * An office's score: its deliveries as a share of the busiest office's, its on-time share, and one less its loss
     * share (lost of delivered and lost), each weighted. Higher is better.
     */
    public static double score(OfficeStats.Standing s, int most_delivered, double w_deliveries, double w_on_time, double w_losses) {
        double busy = most_delivered <= 0 ? 0.0D : (double) s.delivered() / most_delivered;
        int handled = s.delivered() + s.lost();
        double kept = handled == 0 ? 0.0D : 1.0D - (double) s.lost() / handled;
        return w_deliveries * busy + w_on_time * s.on_time_rate() + w_losses * kept;
    }

    /** The winner among offices with at least {@code min_deliveries}, or null. Ties go to the busier office, then by name. */
    public static OfficeStats.Standing winner(List<OfficeStats.Standing> all, int min_deliveries, double w_deliveries,
                                              double w_on_time, double w_losses) {
        int most = all.stream().mapToInt(OfficeStats.Standing::delivered).max().orElse(0);
        return all.stream().filter(s -> s.delivered() >= Math.max(1, min_deliveries))
                .max(Comparator.<OfficeStats.Standing>comparingDouble(s -> score(s, most, w_deliveries, w_on_time, w_losses))
                        .thenComparingInt(OfficeStats.Standing::delivered)
                        .thenComparing(OfficeStats.Standing::office, Comparator.reverseOrder()))
                .orElse(null);
    }

    /** Whether an award is due: {@code period_days} have passed since the last one ({@code last} 0: never, not yet due). */
    public static boolean due(long last, long now, int period_days) {
        return last > 0L && now - last >= (long) Math.max(1, period_days) * OfficeStats.DAY_MS;
    }

    // ---- Settings ---------------------------------------------------------------------------

    public static int period_days() {
        return Math.max(1, GetConfig.int_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.awards.period_days"), 30));
    }

    public static double bonus() {
        return Math.max(0.0D, GetConfig.double_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.awards.bonus"), 0.0D));
    }

    private static double weight(String which) {
        return GetConfig.double_value(VA_postal.plugin.getConfig(), GetConfig.path_format("settings.awards.weights." + which), 1.0D);
    }

    private static String path(String key) {
        return GetConfig.path_format("awards." + key);
    }

    private static long last_at() {
        try {
            return Long.parseLong(VA_postal.plugin.getConfig().getString(path("last_at"), "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** The current Branch of the Month ({@code {office, date, line}}), or null. */
    public static String[] holder() {
        String office = VA_postal.plugin.getConfig().getString(path("office"));
        return office == null ? null : new String[]{office, VA_postal.plugin.getConfig().getString(path("date"), ""),
                VA_postal.plugin.getConfig().getString(path("line"), "")};
    }

    // ---- Awarding ---------------------------------------------------------------------------

    /** The leader over the period so far, or null. */
    public static OfficeStats.Standing leader() {
        return winner(Stats.standings(period_days()), Stats.min_deliveries(), weight("deliveries"), weight("on_time"), weight("losses"));
    }

    /** Run hourly: starts the clock on the first run, and names the Branch of the Month when a period is up. */
    public static void check() {
        long now = System.currentTimeMillis();
        long last = last_at();
        if (last <= 0L) {
            set_last(now); // the first period starts now
            return;
        }
        if (due(last, now, period_days())) {
            award(null);
        }
    }

    private static void set_last(long when) {
        VA_postal.plugin.getConfig().set(path("last_at"), Long.toString(when));
        VA_postal.plugin.saveConfig();
    }

    /** Names the leader over the period (if any) Branch of the Month; {@code sender} hears how it went. */
    public static void award(CommandSender sender) {
        set_last(System.currentTimeMillis());
        OfficeStats.Standing w;
        try {
            w = leader();
        } catch (StoreException e) {
            tell(sender, "&7The records can't be read now; no award.");
            return;
        }
        if (w == null) {
            tell(sender, "&7No office delivered enough (" + Stats.min_deliveries() + "+) for an award this time.");
            return;
        }
        String name = DeedDocument.display(w.office());
        String line = w.delivered() + " delivered, " + Stats.percent(w.on_time_rate()) + " on time, " + w.lost() + " lost";
        String paid = pay_bonus(w.office());
        String date = new SimpleDateFormat("d MMM yyyy", Locale.ROOT).format(new Date());
        VA_postal.plugin.getConfig().set(path("office"), w.office());
        VA_postal.plugin.getConfig().set(path("date"), date);
        VA_postal.plugin.getConfig().set(path("line"), line + (paid == null ? "" : "; bonus " + paid));
        VA_postal.plugin.saveConfig();
        Util.cinform("[Postal] Branch of the Month: " + name + " (" + line + ")" + (paid == null ? "" : ", bonus " + paid));
        Bukkit.broadcast(Text.component(Text.color("&6The &f" + name + "&6 Post Office is the Branch of the Month! &7(" + line + ")")));
        com.vodhanel.minecraft.va_postal.board.NoticeBoard.notice("Award " + name, "The " + name + " Post Office is the Branch of the Month: "
                + line + (paid == null ? "" : "; Central paid it a bonus of " + paid));
        UUID owner = C_Owner.get_owner_local_po_id(w.office());
        Player p = owner == null ? null : Bukkit.getPlayer(owner);
        if (p != null && paid != null) {
            Util.pinform(p, "&6Central paid the " + name + " Post Office a bonus of " + paid + ".");
        }
        tell(sender, "&6The " + name + " Post Office is the Branch of the Month (" + line + ")"
                + (paid == null ? "." : "; bonus " + paid + "."));
    }

    /** Pays the bonus from Central's money above its target; the amount paid, or null (none set, none spare, no economy). */
    private static String pay_bonus(String office) {
        double bonus = bonus();
        if (bonus <= 0.0D || !VA_postal.economy_configured) {
            return null;
        }
        double spare = P_Economy.central_balance() - P_Bank.central_target();
        if (spare < bonus) {
            Util.cinform("[Postal] Branch of the Month bonus skipped: Central has " + P_Economy.ef(Math.max(0.0D, spare))
                    + " above its target, the bonus is " + P_Economy.ef(bonus) + ".");
            return null;
        }
        if (!PostalEconomy.central_to_office(office, bonus)) {
            return null;
        }
        EconomyState.record(EconomyState.Flow.AWARD, bonus);
        return P_Economy.ef(bonus);
    }

    private static void tell(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(Text.color(message));
        }
    }

    // ---- /postal award ----------------------------------------------------------------------

    /** {@code /postal award} for anyone: the holder and the leader so far; {@code now} (admins) awards at once. */
    public static void command(CommandSender sender, String[] args) {
        if (args.length > 1 && "now".equalsIgnoreCase(args[1])) {
            if (sender instanceof Player && !sender.hasPermission("postal.admin")) {
                tell(sender, "&7Required permission not present.");
                return;
            }
            award(sender);
            return;
        }
        String[] h = holder();
        tell(sender, "&6[Postal] Branch of the Month");
        tell(sender, h == null ? "&7None named yet." : "&7Holder: &f" + DeedDocument.display(h[0]) + " &7(" + h[1] + "): " + h[2]);
        try {
            OfficeStats.Standing l = leader();
            tell(sender, l == null ? "&7No office has " + Stats.min_deliveries() + "+ deliveries this period yet."
                    : "&7Leading this period: &f" + DeedDocument.display(l.office()) + " &7(" + l.delivered() + " delivered, "
                    + Stats.percent(l.on_time_rate()) + " on time, " + l.lost() + " lost)");
        } catch (StoreException e) {
            tell(sender, "&7The records can't be read now.");
        }
        long last = last_at();
        if (last > 0L) {
            long left = last + (long) period_days() * OfficeStats.DAY_MS - System.currentTimeMillis();
            tell(sender, "&7Next award in " + Math.max(0L, left / OfficeStats.DAY_MS) + " day(s).");
        }
    }
}
