package com.vodhanel.minecraft.va_postal.common;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.config.C_Arrays;
import com.vodhanel.minecraft.va_postal.config.C_Economy;
import com.vodhanel.minecraft.va_postal.config.C_Route;
import com.vodhanel.minecraft.va_postal.economy.DailyMath;
import com.vodhanel.minecraft.va_postal.economy.EconomyState;
import com.vodhanel.minecraft.va_postal.economy.EconomyState.Flow;
import com.vodhanel.minecraft.va_postal.economy.PostalEconomy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Postal day (docs/design/economy.md §6–§8): server-owned offices settle to their reserve, player-owned
 * offices pay upkeep from what they hold above it, and Central returns part of its surplus to offices that
 * worked, then the day's flows are logged. Every step is a transfer between existing accounts: nothing is
 * created or destroyed.
 */
public final class P_Day {
    private P_Day() {
    }

    public static void run() {
        if (!VA_postal.economy_configured) {
            return;
        }
        double swept = 0.0D;
        double seeded = 0.0D;
        double upkeep_collected = 0.0D;
        double arrears_total = 0.0D;

        // 1. Server-owned offices: down to their reserve (surplus to Central) or topped up to it.
        // 2. Player-owned offices: today's upkeep plus arrears, from what they hold above their reserve.
        for (String name : P_Bank.offices()) {
            P_Economy.verify_bank(name);
            P_Bank.Office o = P_Bank.office(name);
            if (o.owner == null) {
                if (o.balance > o.reserve) {
                    double amount = DailyMath.round2(o.balance - o.reserve);
                    if (PostalEconomy.office_to_central(name, amount)) {
                        EconomyState.record(Flow.SWEEP, amount);
                        swept += amount;
                    }
                } else {
                    seeded += seed_server_office(name);
                }
                continue;
            }
            double upkeep = DailyMath.upkeep(C_Economy.upkeep_base(), C_Economy.upkeep_per_address(), addresses(name),
                    C_Economy.upkeep_per_waypoint(), waypoints(name), C_Economy.upkeep_revenue_rate(),
                    EconomyState.revenue(name));
            double due = upkeep + EconomyState.arrears(name);
            double paid = DailyMath.payable(due, o.balance, o.reserve);
            if (paid > 0.0D && PostalEconomy.office_to_central(name, paid)) {
                EconomyState.record(Flow.UPKEEP, paid);
                upkeep_collected += paid;
            } else {
                paid = 0.0D;
            }
            EconomyState.set_arrears(name, due - paid);
            arrears_total += EconomyState.arrears(name);
        }

        // 3. Dividend: part of Central's surplus, to player-owned offices by work, capped by revenue.
        double central = P_Economy.central_balance();
        double target = P_Bank.central_target();
        double pool = DailyMath.pool(central, target, C_Economy.dividend_release_rate(), EconomyState.carry());
        Map<String, Double> work = new LinkedHashMap<>();
        Map<String, Double> revenue = new LinkedHashMap<>();
        boolean by_deliveries = "deliveries".equalsIgnoreCase(C_Economy.dividend_basis());
        for (String name : P_Bank.offices()) {
            if (com.vodhanel.minecraft.va_postal.config.C_Owner.get_owner_local_po_id(name) == null) {
                continue;
            }
            revenue.put(name, EconomyState.revenue(name));
            work.put(name, by_deliveries ? (double) EconomyState.deliveries(name) : EconomyState.revenue(name));
        }
        double dividends = 0.0D;
        for (Map.Entry<String, Double> e : DailyMath.allocate(pool, work, revenue, C_Economy.dividend_cap()).entrySet()) {
            if (PostalEconomy.central_to_office(e.getKey(), e.getValue())) {
                EconomyState.record(Flow.DIVIDEND, e.getValue());
                dividends += e.getValue();
                settle_arrears(e.getKey());
            }
        }
        EconomyState.set_carry(pool - dividends);

        double central_end = P_Economy.central_balance();
        double owes = P_Bank.central_liability();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("central_end", DailyMath.round2(central_end));
        summary.put("central_owes", DailyMath.round2(owes));
        summary.put("central_target", DailyMath.round2(target));
        summary.put("pool", pool);
        summary.put("carry", EconomyState.carry());
        summary.put("arrears", DailyMath.round2(arrears_total));
        EconomyState.close_day(summary);

        Util.cinform("[Postal] Postal day: Central " + P_Economy.ef(central_end) + " (owes " + P_Economy.ef(owes)
                + ", target " + P_Economy.ef(target) + "); upkeep " + P_Economy.ef(upkeep_collected)
                + ", swept " + P_Economy.ef(swept) + ", seeded " + P_Economy.ef(seeded)
                + ", dividends " + P_Economy.ef(dividends) + " of " + P_Economy.ef(pool)
                + ", arrears " + P_Economy.ef(arrears_total));
        if (central_end < owes) {
            Util.cinform(AnsiColor.RED + "[Postal] Central holds less than it owes in refunds ("
                    + P_Economy.ef(central_end) + " < " + P_Economy.ef(owes) + ").");
        }
    }

    /**
     * Tops a server-owned office up to its reserve from Central's money above what Central owes (a transfer,
     * so the closed economy holds). Returns the amount moved.
     */
    public static double seed_server_office(String office) {
        if (!VA_postal.economy_configured || !P_Economy.does_the_bank_exist(office)) {
            return 0.0D;
        }
        P_Bank.Office o = P_Bank.office(office);
        if (o.owner != null || o.balance >= o.reserve) {
            return 0.0D;
        }
        double available = P_Economy.central_balance() - P_Bank.central_liability();
        double amount = DailyMath.round2(Math.min(o.reserve - o.balance, Math.max(0.0D, available)));
        if (amount > 0.0D && PostalEconomy.central_to_office(office, amount)) {
            EconomyState.record(Flow.SEED, amount);
            return amount;
        }
        return 0.0D;
    }

    /** Pays an office's upkeep arrears from whatever it holds above its reserve. */
    public static void settle_arrears(String office) {
        double owed = EconomyState.arrears(office);
        if (owed <= 0.0D) {
            return;
        }
        P_Bank.Office o = P_Bank.office(office);
        double paid = DailyMath.payable(owed, o.balance, o.reserve);
        if (paid > 0.0D && PostalEconomy.office_to_central(office, paid)) {
            EconomyState.record(Flow.UPKEEP, paid);
            EconomyState.set_arrears(office, owed - paid);
        }
    }

    static int addresses(String office) {
        String[] list = C_Arrays.addresses_list(office);
        return list == null ? 0 : list.length;
    }

    static int waypoints(String office) {
        String[] list = C_Arrays.addresses_list(office);
        int count = 0;
        if (list != null) {
            for (String address : list) {
                count += Math.max(0, C_Route.route_waypoint_count(office, address));
            }
        }
        return count;
    }
}
