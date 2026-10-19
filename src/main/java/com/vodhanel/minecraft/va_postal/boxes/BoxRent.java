package com.vodhanel.minecraft.va_postal.boxes;

/**
 * The rent arithmetic of a P.O. box lease (docs/design/po-boxes.md), as pure functions. Each Postal day the renter
 * owes the day's rent plus any arrears; they pay what they can (up to what they owe), the rest is arrears. A lease
 * that has owed arrears for more than the grace period lapses.
 */
public final class BoxRent {
    private BoxRent() {
    }

    /** One Postal day: what was paid, the arrears after it, the days they've been owed, and whether the lease lapses. */
    public record Day(double paid, double arrears, int arrears_days, boolean lapses) {
    }

    /**
     * @param rent         the day's rent
     * @param arrears      owed from earlier days
     * @param arrears_days how many days {@code arrears} has been owed
     * @param available    what the renter can pay
     * @param grace_days   days of arrears allowed before the lease lapses
     */
    public static Day day(double rent, double arrears, int arrears_days, double available, int grace_days) {
        double due = round2(Math.max(0.0D, rent) + Math.max(0.0D, arrears));
        double paid = round2(Math.max(0.0D, Math.min(available, due)));
        double left = round2(due - paid);
        int days = left > 0.0D ? Math.max(0, arrears_days) + 1 : 0;
        return new Day(paid, left, days, days > Math.max(0, grace_days));
    }

    static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }
}
