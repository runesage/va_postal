package com.vodhanel.minecraft.va_postal.inspect;

import java.util.ArrayList;
import java.util.List;

/**
 * What an inspector finds at an office (docs/design/central-office.md §5): pure, so the rules are tested without a
 * server. Each finding is what's wrong and how to put it right; an office with none isn't inspected.
 */
public final class Inspection {
    private Inspection() {
    }

    /** What Central knows about an office over the inspection period. */
    public record Facts(double arrears, boolean chest_missing, boolean chest_full, int stuck_resets, int delivered,
                        double on_time_rate, int lost) {
    }

    /** The rules' thresholds: on-time share below {@code min_on_time} (with enough deliveries), stuck resets at or above {@code stuck_limit}. */
    public record Rules(int min_deliveries, double min_on_time, int stuck_limit) {
    }

    /** One thing found: what, and the fix. */
    public record Finding(String what, String fix) {
        /** "what|fix", as kept in config. */
        public String stored() {
            return what.replace('|', '/') + "|" + fix.replace('|', '/');
        }

        public static Finding of_stored(String s) {
            int bar = s.indexOf('|');
            return bar < 0 ? new Finding(s, "") : new Finding(s.substring(0, bar), s.substring(bar + 1));
        }
    }

    /** What's wrong at {@code office}, worst first; {@code money} formats amounts as the server's economy does. */
    public static List<Finding> findings(Facts f, Rules r, String office, java.util.function.DoubleFunction<String> money) {
        List<Finding> out = new ArrayList<>();
        if (f.chest_missing()) {
            out.add(new Finding("The office chest can't be found.",
                    "Put back the chest with its [Postal_Mail] sign by the office; mail can't come or go without it."));
        } else if (f.chest_full()) {
            out.add(new Finding("The office chest is full.",
                    "Clear out what isn't mail: letters for the office wait when there's no room for them."));
        }
        if (f.arrears() > 0.005D) {
            out.add(new Finding("The office owes " + money.apply(f.arrears()) + " of upkeep.",
                    "Pay it in: /postal office " + office + " deposit <amount>. Deposits settle arrears first."));
        }
        if (r.stuck_limit() > 0 && f.stuck_resets() >= r.stuck_limit()) {
            out.add(new Finding("The postman got stuck " + f.stuck_resets() + " times.",
                    "Walk the routes and clear what blocks them, or survey them again: /postal survey."));
        }
        if (f.delivered() >= Math.max(1, r.min_deliveries()) && f.on_time_rate() < r.min_on_time()) {
            out.add(new Finding("Only " + Math.round(f.on_time_rate() * 100.0D) + "% of mail arrived on time.",
                    "Shorten long routes, and check nothing holds the postman up."));
        }
        if (f.lost() > 0) {
            out.add(new Finding(f.lost() + (f.lost() == 1 ? " letter was" : " letters were") + " lost.",
                    "Lost mail vanished from a chest the office held: keep its chests and mailboxes whole, and don't empty them by hand."));
        }
        return out;
    }

    /** Whether an inspection is due: never inspected ({@code last} 0), or {@code interval_days} since the last. */
    public static boolean due(long last, long now, int interval_days) {
        return last <= 0L || now - last >= (long) Math.max(1, interval_days) * 86_400_000L;
    }
}
