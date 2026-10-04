package com.vodhanel.minecraft.va_postal.economy;

/**
 * Full-reserve arithmetic (docs/design/economy.md §3, §5). An account's liabilities are the refunds it
 * would owe if every player-owned place it holds escrow for changed hands at today's prices:
 * <ul>
 *   <li>an office owes half the address price for each player-owned address it serves;</li>
 *   <li>Central owes the office price for each player-owned office, plus the other half of every
 *       player-owned address's price.</li>
 * </ul>
 * Pure functions, so the policy can be unit tested without a server.
 */
public final class Reserves {
    private Reserves() {
    }

    /** Office's share of address refunds: half the address price per player-owned address. */
    public static double office_liability(int player_owned_addresses, double address_price) {
        return Math.max(0, player_owned_addresses) * address_share(address_price);
    }

    /** Central's refunds: the office price per player-owned office, plus its half of each address. */
    public static double central_liability(int player_owned_offices, double office_price,
                                           int player_owned_addresses, double address_price) {
        return Math.max(0, player_owned_offices) * Math.max(0.0D, office_price)
                + Math.max(0, player_owned_addresses) * (Math.max(0.0D, address_price) - address_share(address_price));
    }

    /** What an office must keep: its liabilities plus the working floor. */
    public static double office_reserve(double liability, double floor) {
        return Math.max(0.0D, liability) + Math.max(0.0D, floor);
    }

    /** Central's target balance: its liabilities plus the admin's buffer. */
    public static double central_target(double liability, double buffer) {
        return Math.max(0.0D, liability) + Math.max(0.0D, buffer);
    }

    /** What may leave an account without breaking its reserve. */
    public static double withdrawable(double balance, double reserve) {
        return Math.max(0.0D, balance - reserve);
    }

    /** The office's half of an address price, split exactly as purchases and refunds split it. */
    static double address_share(double address_price) {
        return Math.max(0.0D, address_price) / 2.0D;
    }
}
