package com.vodhanel.minecraft.va_postal.economy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Postal day's arithmetic (docs/design/economy.md §6, §8), as pure functions.
 */
public final class DailyMath {
    /**
     * Highest dividend cap. Mailing between two offices you own returns ⅔ of the postage in office shares,
     * plus at most cap × ⅔ in dividends: that stays a loss only for cap < ½ (at ½ it breaks even).
     */
    public static final double MAX_CAP = 0.45D;

    private DailyMath() {
    }

    /** Upkeep: a configurable mix of a flat fee, per-size terms and a tax on the day's revenue. */
    public static double upkeep(double base, double per_address, int addresses, double per_waypoint, int waypoints,
                                double revenue_rate, double revenue) {
        return round(Math.max(0.0D, base)
                + Math.max(0.0D, per_address) * Math.max(0, addresses)
                + Math.max(0.0D, per_waypoint) * Math.max(0, waypoints)
                + Math.max(0.0D, revenue_rate) * Math.max(0.0D, revenue));
    }

    /**
     * How much of {@code due} (today's upkeep plus arrears) an office pays: only from what it holds above
     * its reserve, so the escrow and the seeded floor are never touched. The rest stays in arrears.
     */
    public static double payable(double due, double balance, double reserve) {
        return round(Math.min(Math.max(0.0D, due), Math.max(0.0D, balance - reserve)));
    }

    /** Today's dividend pool: {@code k} of Central's surplus plus yesterday's carry, never more than the surplus. */
    public static double pool(double central_balance, double target, double release_rate, double carry) {
        double surplus = Math.max(0.0D, central_balance - target);
        double k = Math.min(1.0D, Math.max(0.0D, release_rate));
        return round(Math.min(surplus, k * surplus + Math.max(0.0D, carry)));
    }

    /**
     * Splits {@code pool} by work, each office capped at {@code cap} of its revenue. Offices with no work get
     * nothing; whatever the caps leave over is the caller's carry.
     *
     * @param work    per office: revenue or deliveries, per the configured basis
     * @param revenue per office: the day's revenue, which the cap applies to whatever the basis
     */
    public static Map<String, Double> allocate(double pool, Map<String, Double> work, Map<String, Double> revenue, double cap) {
        Map<String, Double> paid = new LinkedHashMap<>();
        double total = 0.0D;
        for (double w : work.values()) {
            total += Math.max(0.0D, w);
        }
        if (pool <= 0.0D || total <= 0.0D) {
            return paid;
        }
        double c = Math.min(MAX_CAP, Math.max(0.0D, cap));
        for (Map.Entry<String, Double> e : work.entrySet()) {
            double w = Math.max(0.0D, e.getValue());
            if (w <= 0.0D) {
                continue;
            }
            double share = pool * w / total;
            double limit = c * Math.max(0.0D, revenue.getOrDefault(e.getKey(), 0.0D));
            double amount = round(Math.min(share, limit));
            if (amount > 0.0D) {
                paid.put(e.getKey(), amount);
            }
        }
        return paid;
    }

    /** Rounded to cents. */
    public static double round2(double amount) {
        return round(amount);
    }

    static double round(double amount) {
        return Math.round(amount * 100.0D) / 100.0D;
    }
}
