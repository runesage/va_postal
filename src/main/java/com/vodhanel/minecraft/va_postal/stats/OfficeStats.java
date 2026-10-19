package com.vodhanel.minecraft.va_postal.stats;

import com.vodhanel.minecraft.va_postal.store.MailFact;
import com.vodhanel.minecraft.va_postal.store.MailState;
import com.vodhanel.minecraft.va_postal.store.OfficeDay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * The per-office numbers behind the leaderboards, Branch of the Month and inspections (docs/design/central-office.md
 * §7), worked out from the mail store's history. Pure functions, so they can be tested without a server.
 * <ul>
 *   <li>a pickup counts as sent by the office that picked it up (the letter's origin office);</li>
 *   <li>a delivery counts for the office that delivered it, with the time from pickup to delivery, and as on time if
 *       that's within what was expected;</li>
 *   <li>a letter gone missing counts as lost by the office holding it: the sending office before it reached Central,
 *       the delivering office after. Lost at Central counts for no office.</li>
 * </ul>
 */
public final class OfficeStats {
    private OfficeStats() {
    }

    public static final long DAY_MS = 86_400_000L;

    /** The UTC day (since the epoch) of {@code millis}. */
    public static int day(long millis) {
        return (int) Math.floorDiv(millis, DAY_MS);
    }

    /**
     * Day {@code day}'s rows from its facts, one per office with anything to count. {@code expected_ms} says how long a
     * delivery may take and still be on time.
     */
    public static List<OfficeDay> summarize(String server, int day, List<MailFact> facts, ToLongFunction<MailFact> expected_ms) {
        Map<String, long[]> by_office = new LinkedHashMap<>(); // sent, delivered, on_time, lost, transit
        for (MailFact f : facts) {
            if (day(f.at()) != day) {
                continue;
            }
            switch (f.to()) {
                case AT_ORIGIN_BRANCH -> {
                    long[] n = count(by_office, f.origin_office());
                    if (n != null) {
                        n[0]++;
                    }
                }
                case DELIVERED -> {
                    long[] n = count(by_office, f.dest_office());
                    if (n == null) {
                        break;
                    }
                    long transit = Math.max(0L, f.at() - f.picked_up_at());
                    n[1]++;
                    n[4] += transit;
                    if (transit <= expected_ms.applyAsLong(f)) {
                        n[2]++;
                    }
                }
                case MISSING -> {
                    long[] n = count(by_office, holder(f));
                    if (n != null) {
                        n[3]++;
                    }
                }
                default -> {
                }
            }
        }
        List<OfficeDay> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : by_office.entrySet()) {
            long[] n = e.getValue();
            out.add(new OfficeDay(server, e.getKey(), day, (int) n[0], (int) n[1], (int) n[2], (int) n[3], n[4]));
        }
        return out;
    }

    /** The office's counters, or null for no office (counted nowhere). */
    private static long[] count(Map<String, long[]> by_office, String office) {
        if (office == null || office.isBlank()) {
            return null;
        }
        return by_office.computeIfAbsent(office.toLowerCase().trim(), k -> new long[5]);
    }

    /** Who held a letter when it went missing: its sending office, its delivering office, or nobody (Central). */
    static String holder(MailFact f) {
        if (f.from() == null) {
            return null;
        }
        return switch (f.from()) {
            case POSTED, AT_ORIGIN_BRANCH -> f.origin_office();
            case AT_DEST_BRANCH, OUT_FOR_DELIVERY, DELIVERED -> f.dest_office();
            default -> null;
        };
    }

    /**
     * How long a delivery may take and still be on time: the delivering office's round (the time its postman takes to
     * serve every address once, cool-downs included) twice over for local mail, picked up on one round and delivered on
     * the next; plus two of Central's rounds (to Central, then out to the office) for mail between offices; times
     * {@code factor}, plus {@code grace_s}.
     */
    public static long expected_ms(boolean local, long office_round_s, long central_round_s, double factor, long grace_s) {
        double s = local ? 2.0D * office_round_s : office_round_s + 2.0D * central_round_s;
        return (long) (Math.max(0.0D, s) * Math.max(0.0D, factor) * 1000.0D) + Math.max(0L, grace_s) * 1000L;
    }

    // ---- Standings --------------------------------------------------------------------------

    /** An office's totals over a period. */
    public record Standing(String office, int sent, int delivered, int on_time, int lost, long transit_ms) {
        /** The share delivered on time, 0 with no deliveries. */
        public double on_time_rate() {
            return delivered == 0 ? 0.0D : (double) on_time / delivered;
        }

        /** The average time from pickup to delivery, 0 with no deliveries. */
        public long average_ms() {
            return delivered == 0 ? 0L : transit_ms / delivered;
        }
    }

    /** Each office's totals over {@code rows}, busiest first. */
    public static List<Standing> standings(List<OfficeDay> rows) {
        Map<String, long[]> sum = new LinkedHashMap<>();
        for (OfficeDay r : rows) {
            long[] n = sum.computeIfAbsent(r.office(), k -> new long[5]);
            n[0] += r.sent();
            n[1] += r.delivered();
            n[2] += r.on_time();
            n[3] += r.lost();
            n[4] += r.transit_ms();
        }
        List<Standing> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : sum.entrySet()) {
            long[] n = e.getValue();
            out.add(new Standing(e.getKey(), (int) n[0], (int) n[1], (int) n[2], (int) n[3], n[4]));
        }
        out.sort(busiest());
        return out;
    }

    /** Most deliveries first; ties by name. */
    public static Comparator<Standing> busiest() {
        return Comparator.comparingInt(Standing::delivered).reversed().thenComparing(Standing::office);
    }

    /** The busiest offices, at most {@code n}, with at least one delivery. */
    public static List<Standing> busiest(List<Standing> all, int n) {
        return all.stream().filter(s -> s.delivered() > 0).sorted(busiest()).limit(n).toList();
    }

    /** The best on-time rates among offices with at least {@code min_deliveries}; ties go to the busier office. */
    public static List<Standing> most_punctual(List<Standing> all, int min_deliveries, int n) {
        return all.stream().filter(s -> s.delivered() >= Math.max(1, min_deliveries))
                .sorted(Comparator.comparingDouble(Standing::on_time_rate).reversed().thenComparing(busiest()))
                .limit(n).toList();
    }

    /** The fewest losses among offices with at least {@code min_deliveries}; ties go to the busier office. */
    public static List<Standing> fewest_losses(List<Standing> all, int min_deliveries, int n) {
        return all.stream().filter(s -> s.delivered() >= Math.max(1, min_deliveries))
                .sorted(Comparator.comparingInt(Standing::lost).thenComparing(busiest()))
                .limit(n).toList();
    }
}
